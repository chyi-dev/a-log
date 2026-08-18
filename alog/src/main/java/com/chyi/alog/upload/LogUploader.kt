package com.chyi.alog.upload

import com.chyi.alog.ALogDefaults
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LogUploader(
    private val baseUrl: String,
    private val token: String,
    private val auditDir: File,
    private val meta: UploadMeta,
    private val chunkSize: Int = ALogDefaults.CHUNK_SIZE,
    private val maxBytes: Long = ALogDefaults.MAX_UPLOAD_BYTES,
) {
    fun upload(files: List<File>, reason: String): UploadResult {
        audit("start reason=$reason files=${files.joinToString { it.name }}")
        val selected = select(files)
        if (selected.isEmpty()) {
            audit("skip empty upload reason=$reason")
            return UploadResult("", false)
        }
        val truncated = selected.sumOf { it.length() } > maxBytes || selected.size < files.size
        val init = negotiate(selected, reason, truncated)
        val uploadId = init.getString("uploadId")
        val filesJson = init.getJSONArray("files")
        for (i in 0 until filesJson.length()) {
            val item = filesJson.getJSONObject(i)
            if (item.optBoolean("skip")) {
                audit("skip ${item.getString("name")}")
                continue
            }
            val path = item.optString("path")
            val local = selected.firstOrNull { it.absolutePath == path }
                ?: selected.first { it.name == item.getString("name") }
            putChunks(uploadId, item.getString("fileId"), local)
        }
        complete(uploadId)
        audit("complete uploadId=$uploadId")
        return UploadResult(uploadId, truncated)
    }

    fun pendingFetchTaskId(unionId: String, deviceId: String): String? {
        val qs = "unionId=${enc(unionId)}&deviceId=${enc(deviceId)}"
        val json = JSONObject(http("GET", "/logs/fetch-pending?$qs", ByteArray(0), "application/json"))
        val tasks = json.optJSONArray("tasks") ?: return null
        if (tasks.length() == 0) return null
        val id = tasks.getJSONObject(0).optString("taskId")
        return id.takeIf { it.isNotBlank() }
    }

    private fun select(files: List<File>): List<File> {
        val sorted = files.filter { it.isFile && it.name.endsWith(".alog") }.sortedByDescending { it.lastModified() }
        val out = mutableListOf<File>()
        var total = 0L
        for (file in sorted) {
            if (total + file.length() > maxBytes) break
            out.add(file)
            total += file.length()
        }
        return out
    }

    private fun negotiate(files: List<File>, reason: String, truncated: Boolean): JSONObject {
        val arr = JSONArray()
        for (file in files) {
            arr.put(
                JSONObject()
                    .put("name", file.name)
                    .put("path", file.absolutePath)
                    .put("size", file.length())
                    .put("sha256", sha256(file))
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
        val stateFile = File(auditDir, "$uploadId-$fileId.state")
        val done = if (stateFile.exists()) stateFile.readText().lines().filter { it.isNotBlank() }.map { it.toInt() }.toMutableSet() else mutableSetOf()
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
                if (index !in done) {
                    var attempt = 0
                    var ok = false
                    var delay = 500L
                    while (attempt < ALogDefaults.UPLOAD_RETRIES && !ok) {
                        try {
                            val sha = sha256(chunk)
                            http(
                                "PUT",
                                "/logs/uploads/$uploadId/files/$fileId/chunks/$index",
                                chunk,
                                "application/octet-stream",
                                mapOf(
                                    "Content-SHA256" to sha,
                                    "Content-Range" to "bytes $offset-${offset + n - 1}/$total",
                                ),
                            )
                            ok = true
                            done.add(index)
                            stateFile.writeText(done.sorted().joinToString("\n"))
                        } catch (t: Throwable) {
                            attempt++
                            if (attempt >= ALogDefaults.UPLOAD_RETRIES) throw t
                            TimeUnit.MILLISECONDS.sleep(delay)
                            delay *= 2
                        }
                    }
                }
                offset += n
                index++
            }
        }
    }

    private fun complete(uploadId: String) {
        http("POST", "/logs/uploads/$uploadId/complete", ByteArray(0), "application/json")
    }

    fun ackFetch(taskId: String) {
        val body = JSONObject().put("taskId", taskId).put("ok", true).toString()
        http("POST", "/logs/fetch-ack", body.toByteArray(), "application/json")
    }

    private fun http(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String> = emptyMap(),
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

    private fun audit(line: String) {
        auditDir.mkdirs()
        File(auditDir, "upload_audit.log").appendText("${System.currentTimeMillis()} $line\n")
    }

    companion object {
        fun sha256(file: File): String = sha256(file.readBytes())

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun enc(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

data class UploadMeta(
    val appId: String,
    val unionId: String,
    val deviceId: String,
    val appVer: String,
    val buildVer: String,
)

data class UploadResult(val uploadId: String, val truncated: Boolean)
