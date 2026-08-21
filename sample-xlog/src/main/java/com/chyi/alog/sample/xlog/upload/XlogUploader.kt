package com.chyi.alog.sample.xlog.upload

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

class XlogUploader(
    private val baseUrl: String,
    private val token: String,
    private val appId: String,
    private val unionId: String,
    private val deviceId: String,
    private val appVer: String,
    private val buildVer: String,
    private val chunkSize: Int = 2 * 1024 * 1024,
    private val maxBytes: Long = 50L * 1024 * 1024,
) {
    fun upload(files: List<File>, reason: String): String {
        val selected = select(files)
        if (selected.isEmpty()) return ""
        val truncated = select(files, Long.MAX_VALUE).size > selected.size
        val init = negotiate(selected, reason, truncated)
        val uploadId = init.getString("uploadId")
        val filesJson = init.getJSONArray("files")
        for (i in 0 until filesJson.length()) {
            val item = filesJson.getJSONObject(i)
            if (item.optBoolean("skip")) continue
            val path = item.optString("path")
            val local = selected.firstOrNull { it.absolutePath == path }
                ?: selected.first { it.name == item.getString("name") }
            putChunks(uploadId, item.getString("fileId"), local)
        }
        http("POST", "/logs/uploads/$uploadId/complete", ByteArray(0), "application/json")
        return uploadId
    }

    fun pendingFetchTaskId(unionId: String, deviceId: String): String? {
        val qs = "unionId=${enc(unionId)}&deviceId=${enc(deviceId)}"
        val json = JSONObject(http("GET", "/logs/fetch-pending?$qs", ByteArray(0), "application/json"))
        val tasks = json.optJSONArray("tasks") ?: return null
        if (tasks.length() == 0) return null
        return tasks.getJSONObject(0).optString("taskId").takeIf { it.isNotBlank() }
    }

    fun ackFetch(taskId: String) {
        val body = JSONObject().put("taskId", taskId).put("ok", true).toString()
        http("POST", "/logs/fetch-ack", body.toByteArray(), "application/json")
    }

    private fun select(files: List<File>, limit: Long = maxBytes): List<File> {
        val eligible = files.filter { it.isFile && it.name.endsWith(".xlog") }
            .sortedByDescending { it.lastModified() }
        val out = mutableListOf<File>()
        var total = 0L
        for (f in eligible) {
            if (total + f.length() > limit) break
            out.add(f)
            total += f.length()
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
            .put("appId", appId)
            .put("unionId", unionId)
            .put("deviceId", deviceId)
            .put("appVer", appVer)
            .put("buildVer", buildVer)
            .put("platform", "android")
            .put("reason", reason)
            .put("formatVersion", 1)
            .put("maxBytes", maxBytes)
            .put("truncated", truncated)
            .put("files", arr)
        return JSONObject(http("POST", "/logs/uploads", body.toString().toByteArray(), "application/json"))
    }

    private fun putChunks(uploadId: String, fileId: String, file: File) {
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
                var attempt = 0
                var ok = false
                var delay = 500L
                while (attempt < 5 && !ok) {
                    try {
                        http(
                            "PUT",
                            "/logs/uploads/$uploadId/files/$fileId/chunks/$index",
                            chunk,
                            "application/octet-stream",
                            mapOf(
                                "Content-SHA256" to sha256(chunk),
                                "Content-Range" to "bytes $offset-${offset + n - 1}/$total",
                            ),
                        )
                        ok = true
                    } catch (t: Throwable) {
                        attempt++
                        if (attempt >= 5) throw t
                        TimeUnit.MILLISECONDS.sleep(delay)
                        delay *= 2
                    }
                }
                offset += n
                index++
            }
        }
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

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(FileInputStream(file), digest).use { stream ->
                val buf = ByteArray(64 * 1024)
                while (stream.read(buf) >= 0) {
                }
            }
            return hex(digest.digest())
        }

        fun sha256(bytes: ByteArray): String =
            hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun hex(digest: ByteArray): String =
            digest.joinToString("") { "%02x".format(it) }

        private fun enc(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
