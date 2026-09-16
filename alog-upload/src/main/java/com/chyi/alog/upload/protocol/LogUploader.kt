package com.chyi.alog.upload.protocol

import com.chyi.alog.ALogDefaults
import com.chyi.alog.upload.UploadDefaults
import com.chyi.alog.upload.UploadMeta
import com.chyi.alog.upload.UploadResult
import com.chyi.alog.upload.collector.AlogFileCollector
import com.chyi.alog.upload.persist.ChunkStateStore
import com.chyi.alog.upload.persist.FetchAckStore
import com.chyi.alog.upload.persist.FileFingerprint
import com.chyi.alog.upload.persist.UploadAuditLog
import com.chyi.alog.upload.persist.UploadSessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun interface HttpTransport {
    fun request(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String>,
    ): String
}

data class FetchTask(
    val taskId: String,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val maxBytes: Long? = null,
)

private fun JSONObject.nullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

class LogUploader(
    private val baseUrl: String,
    private val token: String,
    private val auditDir: File,
    private val meta: UploadMeta,
    private val chunkSize: Int = UploadDefaults.CHUNK_SIZE,
    private val maxBytes: Long = UploadDefaults.MAX_UPLOAD_BYTES,
    private val recentDays: Int? = 2,
) {
    private val audit = UploadAuditLog(auditDir)
    private val chunkState = ChunkStateStore(auditDir)
    private val sessionStore = UploadSessionStore(auditDir)
    private val fetchAckStore = FetchAckStore(auditDir)
    private var transport: HttpTransport = HttpTransport { method, path, body, contentType, extra ->
        openConnection(method, path, body, contentType, extra)
    }
    private var sleeper: (Long) -> Unit = { TimeUnit.MILLISECONDS.sleep(it) }

    internal constructor(
        baseUrl: String,
        token: String,
        auditDir: File,
        meta: UploadMeta,
        chunkSize: Int,
        recentDays: Int?,
        http: HttpTransport,
        sleeper: (Long) -> Unit,
    ) : this(
        baseUrl = baseUrl,
        token = token,
        auditDir = auditDir,
        meta = meta,
        chunkSize = chunkSize,
        recentDays = recentDays,
    ) {
        this.transport = http
        this.sleeper = sleeper
    }

    fun upload(
        files: List<File>,
        reason: String,
        fromMs: Long? = null,
        toMs: Long? = null,
        byteLimit: Long? = null,
        fetchTaskId: String? = null,
    ): UploadResult {
        audit.line("start reason=$reason files=${files.joinToString { it.name }}")
        val limit = byteLimit ?: maxBytes
        val selected = AlogFileCollector.select(files, limit, recentDays, fromMs = fromMs, toMs = toMs)
        if (selected.isEmpty()) {
            audit.line("skip empty upload reason=$reason")
            sessionStore.clear()
            return UploadResult("", false, fetchTaskId)
        }
        val truncated = AlogFileCollector.truncated(files, selected, recentDays, fromMs = fromMs, toMs = toMs)
        val fingerprints = selected.map {
            FileFingerprint(it.name, it.absolutePath, it.length(), sha256(it))
        }
        val init = resumeOrNegotiate(selected, fingerprints, reason, truncated)
        val uploadId = init.getString("uploadId")
        val filesJson = init.getJSONArray("files")
        try {
            for (i in 0 until filesJson.length()) {
                val item = filesJson.getJSONObject(i)
                if (item.optBoolean("skip")) {
                    audit.line("skip ${item.getString("name")}")
                    continue
                }
                val path = item.optString("path")
                val local = selected.firstOrNull { it.absolutePath == path }
                    ?: selected.first { it.name == item.getString("name") }
                putChunks(uploadId, item.getString("fileId"), local)
            }
            complete(uploadId)
            if (!fetchTaskId.isNullOrBlank()) {
                fetchAckStore.saveAckPending(fetchTaskId, uploadId)
                audit.line("fetch ack pending taskId=$fetchTaskId uploadId=$uploadId")
            }
            sessionStore.clear()
            audit.line("complete uploadId=$uploadId")
            return UploadResult(uploadId, truncated, fetchTaskId)
        } catch (t: Throwable) {
            audit.line("failed uploadId=$uploadId ${t.message}")
            throw t
        }
    }

    /**
     * Fetch loop: look up a real pending task, upload only if one exists, ack only after
     * a successful (non-empty) upload. Never acks placeholder ids.
     *
     * If a previous run completed upload but failed ack, [fetchAckStore] resumes at
     * ack-only (no second negotiate/upload).
     */
    fun runFetch(files: List<File>, unionId: String, deviceId: String): UploadResult? {
        resumePersistedAck()?.let { return it }

        val pending = pendingFetchTask(unionId, deviceId)
        if (pending == null) {
            audit.line("skip fetch: no pending task")
            return null
        }
        audit.line("fetch pending taskId=${pending.taskId}")
        val result = try {
            upload(
                files,
                "fetch",
                fromMs = pending.fromMs,
                toMs = pending.toMs,
                byteLimit = pending.maxBytes,
                fetchTaskId = pending.taskId,
            )
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.startsWith("fetch ack failed") || msg.startsWith("fetch pending lookup failed")) {
                throw t
            }
            audit.line("fetch upload failed taskId=${pending.taskId} ${t.message}")
            throw IllegalStateException("fetch upload failed: ${t.message}", t)
        }
        if (result.uploadId.isEmpty()) {
            audit.line("skip fetch ack: empty upload taskId=${pending.taskId}")
            return result
        }
        ackFetch(pending.taskId, result.uploadId, ok = true)
        return result
    }

    internal fun resumePersistedAck(): UploadResult? {
        val saved = fetchAckStore.load() ?: return null
        audit.line("resume fetch ack taskId=${saved.taskId} uploadId=${saved.uploadId}")
        ackFetch(saved.taskId, saved.uploadId, ok = true)
        return UploadResult(saved.uploadId, truncated = false, fetchTaskId = saved.taskId)
    }

    fun hasPersistedFetchAck(): Boolean = fetchAckStore.load() != null

    fun pendingFetchTask(unionId: String, deviceId: String): FetchTask? {
        val qs = "unionId=${enc(unionId)}&deviceId=${enc(deviceId)}"
        val json = try {
            JSONObject(http("GET", "/logs/fetch-pending?$qs", ByteArray(0), "application/json"))
        } catch (t: Throwable) {
            val wrapped = IllegalStateException("fetch pending lookup failed: ${t.message}", t)
            audit.line(wrapped.message.orEmpty())
            throw wrapped
        }
        val tasks = json.optJSONArray("tasks") ?: return null
        if (tasks.length() == 0) return null
        val obj = tasks.getJSONObject(0)
        val id = obj.optString("taskId")
        if (id.isBlank()) return null
        return FetchTask(
            taskId = id,
            fromMs = obj.nullableLong("fromMs"),
            toMs = obj.nullableLong("toMs"),
            maxBytes = obj.nullableLong("maxBytes")?.takeIf { it > 0 },
        )
    }

    fun pendingFetchTaskId(unionId: String, deviceId: String): String? =
        pendingFetchTask(unionId, deviceId)?.taskId

    private fun resumeOrNegotiate(
        selected: List<File>,
        fingerprints: List<FileFingerprint>,
        reason: String,
        truncated: Boolean,
    ): JSONObject {
        val saved = sessionStore.load()
        if (saved != null && sessionStore.matches(saved, fingerprints)) {
            audit.line("resume uploadId=${saved.getString("uploadId")}")
            return saved
        }
        val init = negotiate(selected, fingerprints, reason, truncated)
        sessionStore.save(init)
        return init
    }

    private fun negotiate(
        files: List<File>,
        fingerprints: List<FileFingerprint>,
        reason: String,
        truncated: Boolean,
    ): JSONObject {
        val arr = JSONArray()
        for (fp in fingerprints) {
            val file = files.first { it.absolutePath == fp.path }
            arr.put(
                JSONObject()
                    .put("name", fp.name)
                    .put("path", fp.path)
                    .put("size", fp.size)
                    .put("sha256", fp.sha256)
                    .put("date", AlogFileCollector.dateStampOf(file.name).orEmpty()),
            )
        }
        val body = JSONObject()
            .put("appId", meta.appId)
            .put("unionId", meta.unionId)
            .put("deviceId", meta.deviceId)
            .put("appVer", meta.appVer)
            .put("buildVer", meta.buildVer)
            .put("platform", "android")
            .put("reason", reason)
            .put("formatVersion", ALogDefaults.FORMAT_VERSION)
            .put("maxBytes", maxBytes)
            .put("truncated", truncated)
            .put("files", arr)
        return JSONObject(http("POST", "/logs/uploads", body.toString().toByteArray(), "application/json"))
    }

    private fun putChunks(uploadId: String, fileId: String, file: File) {
        val done = chunkState.load(uploadId, fileId)
        RandomAccessFile(file, "r").use { raf ->
            val total = file.length()
            var index = 0
            var offset = 0L
            val buf = ByteArray(chunkSize)
            while (offset < total) {
                val n = minOf(chunkSize.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, n)
                val chunk = buf.copyOf(n)
                if (index in done) {
                    audit.line("continue skip chunk index=$index fileId=$fileId")
                } else {
                    val start = offset
                    withRetries("chunk $fileId#$index") {
                        val sha = sha256(chunk)
                        http(
                            "PUT",
                            "/logs/uploads/$uploadId/files/$fileId/chunks/$index",
                            chunk,
                            "application/octet-stream",
                            mapOf(
                                "Content-SHA256" to sha,
                                "Content-Range" to "bytes $start-${start + n - 1}/$total",
                            ),
                        )
                    }
                    done.add(index)
                    chunkState.save(uploadId, fileId, done)
                }
                offset += n
                index++
            }
        }
    }

    internal fun complete(uploadId: String) {
        withRetries("complete $uploadId") {
            http("POST", "/logs/uploads/$uploadId/complete", ByteArray(0), "application/json")
        }
    }

    fun ackFetch(taskId: String, uploadId: String? = null, ok: Boolean = true) {
        val body = JSONObject()
            .put("taskId", taskId)
            .put("ok", ok)
        if (!uploadId.isNullOrBlank()) {
            body.put("uploadId", uploadId)
        }
        try {
            withRetries("fetch-ack $taskId") {
                http("POST", "/logs/fetch-ack", body.toString().toByteArray(), "application/json")
            }
            audit.line("fetch acked taskId=$taskId uploadId=${uploadId.orEmpty()}")
            fetchAckStore.clear()
        } catch (t: Throwable) {
            if (ok && isNonRetriable(t)) {
                audit.line("fetch ack already applied taskId=$taskId ${t.message}")
                fetchAckStore.clear()
                return
            }
            audit.line("fetch ack failed taskId=$taskId uploadId=${uploadId.orEmpty()} ${t.message}")
            throw IllegalStateException("fetch ack failed: ${t.message}", t)
        }
    }

    private fun withRetries(label: String, block: () -> Unit) {
        var attempt = 0
        var delay = 500L
        while (true) {
            try {
                block()
                return
            } catch (t: Throwable) {
                if (isNonRetriable(t)) throw t
                attempt++
                audit.line("retry $label attempt=$attempt ${t.message}")
                if (attempt >= UploadDefaults.UPLOAD_RETRIES) throw t
                sleeper(delay)
                delay *= 2
            }
        }
    }

    private fun isNonRetriable(t: Throwable): Boolean {
        val message = t.message.orEmpty()
        return "HTTP 400" in message || "HTTP 401" in message || "HTTP 404" in message || "HTTP 409" in message
    }

    private fun http(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String> = emptyMap(),
    ): String = transport.request(method, path, body, contentType, extra)

    private fun openConnection(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String>,
    ): String {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.doInput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Connection", "close")
            extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val other = if (code in 200..299) conn.errorStream else conn.inputStream
            try {
                other?.close()
            } catch (_: Throwable) {
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code $path $text")
            }
            return if (text.isEmpty()) "{}" else text
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(FileInputStream(file), digest).use { stream ->
                val buf = ByteArray(64 * 1024)
                while (stream.read(buf) >= 0) {
                    // digest updated by DigestInputStream
                }
            }
            return hex(digest.digest())
        }

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return hex(digest)
        }

        private fun hex(digest: ByteArray): String =
            digest.joinToString("") { "%02x".format(it) }

        private fun enc(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
