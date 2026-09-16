package com.chyi.alog.upload.protocol

import com.chyi.alog.upload.UploadMeta
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * P0 client protocol: weak-network chunk retry/resume, complete idempotency, hash 秒传.
 * Transport is mocked; behavior matches docs/spec/upload.md and alog-ingest.
 */
class LogUploaderProtocolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun chunkPutRetriesThenSucceeds() {
        val ingest = FakeIngest()
        ingest.failPutsRemaining = 2
        val file = alogFile("alog_20990101_0.alog", 2500)
        val result = uploader(ingest).upload(listOf(file), "manual")
        assertEquals(ingest.lastUploadId, result.uploadId)
        assertEquals(0, ingest.failPutsRemaining)
        assertEquals(3, ingest.putSuccesses.size)
        assertEquals(listOf(0, 1, 2), ingest.putSuccesses.map { it.index })
        assertEquals(5, ingest.putAttempts)
        assertTrue(auditText().contains("retry chunk"))
    }

    @Test
    fun failedUploadResumesRemainingChunksWithoutRenegotiate() {
        val ingest = FakeIngest()
        ingest.failPutIndexes[1] = 5
        val file = alogFile("alog_20990101_0.alog", 2500)
        val first = uploader(ingest)
        try {
            first.upload(listOf(file), "manual")
            fail("expected chunk 1 to exhaust retries")
        } catch (t: IllegalStateException) {
            assertTrue(t.message.orEmpty().contains("HTTP 503"))
        }
        assertEquals(1, ingest.negotiateCount)
        assertEquals(listOf(0), ingest.putSuccesses.map { it.index })

        ingest.failPutIndexes.clear()
        val second = uploader(ingest)
        val result = second.upload(listOf(file), "manual")
        assertEquals(ingest.lastUploadId, result.uploadId)
        assertEquals(1, ingest.negotiateCount)
        assertEquals(listOf(0, 1, 2), ingest.putSuccesses.map { it.index })
        assertTrue(auditText().contains("resume uploadId="))
        assertTrue(auditText().contains("continue skip chunk index=0"))
        assertEquals(1, ingest.completeCount)
    }

    @Test
    fun completeIsIdempotent() {
        val ingest = FakeIngest()
        val file = alogFile("alog_20990101_0.alog", 800)
        val client = uploader(ingest)
        val result = client.upload(listOf(file), "manual")
        assertEquals(1, ingest.completeCount)
        client.complete(result.uploadId)
        client.complete(result.uploadId)
        assertEquals(3, ingest.completeCount)
        assertEquals("done", ingest.lastCompleteStatus)
        assertEquals(1, ingest.assembleCount)
    }

    @Test
    fun completeRetriesThenSucceeds() {
        val ingest = FakeIngest()
        ingest.failCompleteRemaining = 2
        val file = alogFile("alog_20990101_0.alog", 800)
        uploader(ingest).upload(listOf(file), "manual")
        assertEquals(0, ingest.failCompleteRemaining)
        assertEquals(1, ingest.completeCount)
        assertTrue(auditText().contains("retry complete"))
    }

    @Test
    fun hashSkipDoesNotPutChunks() {
        val ingest = FakeIngest()
        val file = alogFile("alog_20990101_0.alog", 1800)
        val first = uploader(ingest).upload(listOf(file), "manual")
        assertFalse(first.uploadId.isEmpty())
        assertEquals(2, ingest.putSuccesses.size)
        assertEquals(1, ingest.completeCount)

        val second = uploader(ingest).upload(listOf(file), "manual")
        assertEquals(2, ingest.negotiateCount)
        assertTrue(ingest.lastSkip)
        assertEquals(2, ingest.putSuccesses.size)
        assertEquals(2, ingest.completeCount)
        assertTrue(auditText().contains("skip ${file.name}"))
        assertEquals(second.uploadId, ingest.lastUploadId)
        assertTrue(second.uploadId != first.uploadId)
    }

    @Test
    fun negotiateSendsDateFromPushFileName() {
        val ingest = FakeIngest()
        val file = alogFile("alog_push_20260916_0.alog", 800)
        uploader(ingest).upload(listOf(file), "manual")
        assertEquals("20260916", ingest.lastFileDate)
    }

    @Test
    fun fetchUploadsThenAcksPendingTaskWithUploadId() {
        val ingest = FakeIngest()
        ingest.pendingTasks.add(
            JSONObject()
                .put("taskId", "ft-real")
                .put("fromMs", 1)
                .put("toMs", 2)
                .put("maxBytes", 4096),
        )
        val file = alogFile("alog_20990101_0.alog", 800)
        val client = uploader(ingest)
        val pending = client.pendingFetchTask("u", "d")
        assertEquals("ft-real", pending!!.taskId)
        assertEquals(1L, pending.fromMs)
        assertEquals(2L, pending.toMs)
        assertEquals(4096L, pending.maxBytes)
        val result = client.upload(listOf(file), "fetch", byteLimit = pending.maxBytes)
        client.ackFetch(pending.taskId, result.uploadId, ok = true)
        assertEquals("ft-real", ingest.lastAckTaskId)
        assertEquals(result.uploadId, ingest.lastAckUploadId)
        assertTrue(ingest.lastAckOk)
        assertEquals(0, ingest.pendingTasks.size)
    }

    @Test
    fun fetchDoesNotAckWhenNoPendingTask() {
        val ingest = FakeIngest()
        val client = uploader(ingest)
        assertEquals(null, client.pendingFetchTask("u", "d"))
        assertEquals(null, ingest.lastAckTaskId)
    }

    private fun uploader(ingest: FakeIngest): LogUploader = LogUploader(
        baseUrl = "http://ingest.test",
        token = "alog-dev",
        auditDir = auditDir(),
        meta = UploadMeta("app", "u", "d", "1.0", "1"),
        chunkSize = 1024,
        recentDays = null,
        http = ingest,
        sleeper = { },
    )

    private fun auditDir(): File = File(tmp.root, "audit").apply { mkdirs() }

    private fun auditText(): String = File(auditDir(), "upload_audit.log").readText()

    private fun alogFile(name: String, size: Int): File {
        val file = File(tmp.root, name)
        file.writeBytes(ByteArray(size) { it.toByte() })
        return file
    }
}

private data class PutCall(val uploadId: String, val fileId: String, val index: Int, val body: ByteArray)

private class FakeIngest : HttpTransport {
    var chunkSize = 1024
    var failPutsRemaining = 0
    val failPutIndexes = mutableMapOf<Int, Int>()
    var failCompleteRemaining = 0
    var negotiateCount = 0
    var completeCount = 0
    var assembleCount = 0
    var lastUploadId = ""
    var lastCompleteStatus = ""
    var lastSkip = false
    var putAttempts = 0
    var lastFileDate = ""
    var lastAckTaskId: String? = null
    var lastAckUploadId: String? = null
    var lastAckOk = false
    val pendingTasks = mutableListOf<JSONObject>()
    val putSuccesses = mutableListOf<PutCall>()
    private val chunks = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private val tasks = mutableMapOf<String, JSONObject>()
    private val hashIndex = mutableMapOf<String, String>()

    override fun request(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String,
        extra: Map<String, String>,
    ): String {
        if (method == "GET" && path.startsWith("/logs/fetch-pending")) {
            return JSONObject().put("tasks", JSONArray(pendingTasks)).toString()
        }
        if (method == "POST" && path == "/logs/fetch-ack") {
            val ack = JSONObject(String(body, Charsets.UTF_8))
            val taskId = ack.optString("taskId")
            if (taskId.isBlank()) throw IllegalStateException("HTTP 400 taskId required")
            val idx = pendingTasks.indexOfFirst { it.optString("taskId") == taskId }
            if (idx < 0) throw IllegalStateException("HTTP 404 unknown taskId")
            pendingTasks.removeAt(idx)
            lastAckTaskId = taskId
            lastAckUploadId = ack.optString("uploadId").takeIf { it.isNotBlank() }
            lastAckOk = ack.optBoolean("ok", true)
            return JSONObject().put("ok", true).put("taskId", taskId).put("status", "acked").toString()
        }
        if (method == "POST" && path == "/logs/uploads") {
            return negotiate(JSONObject(String(body, Charsets.UTF_8)))
        }
        val put = Regex("""^/logs/uploads/([^/]+)/files/([^/]+)/chunks/(\d+)$""").matchEntire(path)
        if (method == "PUT" && put != null) {
            return putChunk(put.groupValues[1], put.groupValues[2], put.groupValues[3].toInt(), body, extra)
        }
        val complete = Regex("""^/logs/uploads/([^/]+)/complete$""").matchEntire(path)
        if (method == "POST" && complete != null) {
            return complete(complete.groupValues[1])
        }
        throw IllegalStateException("HTTP 404 $path")
    }

    private fun negotiate(body: JSONObject): String {
        negotiateCount++
        val uploadId = "u-$negotiateCount"
        lastUploadId = uploadId
        val filesIn = body.getJSONArray("files")
        val filesOut = JSONArray()
        var anySkip = false
        for (i in 0 until filesIn.length()) {
            val item = filesIn.getJSONObject(i)
            val digest = item.getString("sha256")
            val skip = digest in hashIndex
            anySkip = anySkip || skip
            val fileId = if (skip) hashIndex.getValue(digest) else "f-$negotiateCount-$i"
            lastFileDate = item.optString("date")
            filesOut.put(
                JSONObject()
                    .put("fileId", fileId)
                    .put("name", item.getString("name"))
                    .put("path", item.optString("path"))
                    .put("sha256", digest)
                    .put("skip", skip)
                    .put("size", item.getLong("size")),
            )
        }
        lastSkip = anySkip
        val record = JSONObject()
            .put("uploadId", uploadId)
            .put("chunkSize", chunkSize)
            .put("status", "negotiating")
            .put("files", filesOut)
        tasks[uploadId] = record
        return record.toString()
    }

    private fun putChunk(
        uploadId: String,
        fileId: String,
        index: Int,
        body: ByteArray,
        extra: Map<String, String>,
    ): String {
        putAttempts++
        if (failPutsRemaining > 0) {
            failPutsRemaining--
            throw IllegalStateException("HTTP 503 $uploadId chunk $index")
        }
        val remaining = failPutIndexes[index]
        if (remaining != null && remaining > 0) {
            failPutIndexes[index] = remaining - 1
            throw IllegalStateException("HTTP 503 $uploadId chunk $index")
        }
        val expected = extra["Content-SHA256"].orEmpty().lowercase()
        if (expected.isNotEmpty() && sha256(body) != expected) {
            throw IllegalStateException("HTTP 409 chunk sha256 mismatch")
        }
        tasks[uploadId] ?: throw IllegalStateException("HTTP 404 unknown uploadId")
        chunks.getOrPut("$uploadId/$fileId") { mutableMapOf() }[index] = body
        putSuccesses.add(PutCall(uploadId, fileId, index, body))
        return JSONObject().put("ok", true).put("index", index).toString()
    }

    private fun complete(uploadId: String): String {
        if (failCompleteRemaining > 0) {
            failCompleteRemaining--
            throw IllegalStateException("HTTP 503 complete")
        }
        val record = tasks[uploadId] ?: throw IllegalStateException("HTTP 404 unknown uploadId")
        completeCount++
        if (record.optString("status") == "done") {
            lastCompleteStatus = "done"
            return JSONObject().put("status", "done").put("uploadId", uploadId).toString()
        }
        assembleCount++
        val files = record.getJSONArray("files")
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            if (item.optBoolean("skip")) continue
            val fileId = item.getString("fileId")
            val expected = item.getString("sha256")
            val parts = chunks["$uploadId/$fileId"] ?: emptyMap()
            val blob = assemble(parts)
            val digest = sha256(blob)
            if (digest != expected) {
                throw IllegalStateException("HTTP 409 file sha256 mismatch")
            }
            hashIndex[digest] = fileId
        }
        record.put("status", "done")
        lastCompleteStatus = "done"
        return JSONObject()
            .put("status", "done")
            .put("uploadId", uploadId)
            .toString()
    }

    private fun assemble(parts: Map<Int, ByteArray>): ByteArray {
        if (parts.isEmpty()) return ByteArray(0)
        val max = parts.keys.maxOrNull() ?: return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        for (i in 0..max) {
            out.write(parts[i] ?: throw IllegalStateException("HTTP 409 missing chunk $i"))
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
