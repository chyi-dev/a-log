package com.chyi.alog.upload.protocol

import com.chyi.alog.ALogDefaults
import com.chyi.alog.upload.UploadDefaults
import com.chyi.alog.upload.UploadMeta
import com.chyi.alog.upload.UploadResult
import com.chyi.alog.upload.collector.AlogFileCollector
import com.chyi.alog.upload.persist.ChunkStateStore
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

    fun upload(files: List<File>, reason: String): UploadResult {
        audit.line("start reason=$reason files=${files.joinToString { it.name }}")
        val selected = AlogFileCollector.select(files, maxBytes, recentDays)
        if (selected.isEmpty()) {
            audit.line("skip empty upload reason=$reason")
            sessionStore.clear()
            return UploadResult("", false)
        }
        val truncated = AlogFileCollector.truncated(files, selected, recentDays)
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
            sessionStore.clear()
            audit.line("complete uploadId=$uploadId")
            return UploadResult(uploadId, truncated)
        } catch (t: Throwable) {
            audit.line("failed uploadId=$uploadId ${t.message}")
            throw t
        }
    }

    fun pendingFetchTaskId(unionId: String, deviceId: String): String? {
        val qs = "unionId=${enc(unionId)}&deviceId=${enc(deviceId)}"
        val json = JSONObject(http("GET", "/logs/fetch-pending?$qs", ByteArray(0), "application/json"))
        val tasks = json.optJSONArray("tasks") ?: return null
        if (tasks.length() == 0) return null
        val id = tasks.getJSONObject(0).optString("taskId")
        return id.takeIf { it.isNotBlank() }
    }

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
                    .put("date", file.name.substringAfter('_').substringBefore('_')),
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

    fun ackFetch(taskId: String) {
        val body = JSONObject().put("taskId", taskId).put("ok", true).toString()
        http("POST", "/logs/fetch-ack", body.toByteArray(), "application/json")
    }

    private fun withRetries(label: String, block: () -> Unit) {
        var attempt = 0
        var delay = 500L
        while (true) {
            try {
                block()
                return
            } catch (t: Throwable) {
                attempt++
                audit.line("retry $label attempt=$attempt ${t.message}")
                if (attempt >= UploadDefaults.UPLOAD_RETRIES) throw t
                sleeper(delay)
                delay *= 2
            }
        }
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
        conn.requestMethod = method
        conn.doInput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", contentType)
        extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method != "GET") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code $path $text")
        }
        return if (text.isEmpty()) "{}" else text
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
