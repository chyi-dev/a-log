package com.chyi.alog.upload.persist

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

class ChunkStateStore(private val auditDir: File) {
    fun load(uploadId: String, fileId: String): MutableSet<Int> {
        val stateFile = file(uploadId, fileId)
        if (!stateFile.exists()) return mutableSetOf()
        return stateFile.readText().lines().filter { it.isNotBlank() }.map { it.toInt() }.toMutableSet()
    }

    fun save(uploadId: String, fileId: String, done: Set<Int>) {
        auditDir.mkdirs()
        file(uploadId, fileId).writeText(done.sorted().joinToString("\n"))
    }

    private fun file(uploadId: String, fileId: String) = File(auditDir, "$uploadId-$fileId.state")
}

/** Remembers the last negotiated upload so a later retry can continue remaining chunks. */
class UploadSessionStore(private val auditDir: File) {
    fun load(): JSONObject? {
        val file = file()
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (_: Throwable) {
            null
        }
    }

    fun save(session: JSONObject) {
        auditDir.mkdirs()
        file().writeText(session.toString())
    }

    fun clear() {
        file().delete()
    }

    fun matches(session: JSONObject, fingerprints: List<FileFingerprint>): Boolean {
        val files = session.optJSONArray("files") ?: return false
        if (files.length() != fingerprints.size) return false
        val byName = fingerprints.associateBy { it.name }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val local = byName[item.optString("name")] ?: return false
            if (local.sha256 != item.optString("sha256")) return false
            if (local.size != item.optLong("size")) return false
        }
        return session.optString("uploadId").isNotBlank()
    }

    private fun file() = File(auditDir, "upload_session.json")
}

data class FileFingerprint(
    val name: String,
    val path: String,
    val size: Long,
    val sha256: String,
)

class UploadAuditLog(private val auditDir: File) {
    fun line(text: String) {
        auditDir.mkdirs()
        File(auditDir, "upload_audit.log").appendText("${System.currentTimeMillis()} $text\n")
    }
}

/** Serializes uploads that share [auditDir] (session/chunk files) across overlapping workers. */
class UploadGate(private val auditDir: File) {
    fun <T> withLock(block: () -> T): T {
        auditDir.mkdirs()
        RandomAccessFile(File(auditDir, "upload.lock"), "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }
}
