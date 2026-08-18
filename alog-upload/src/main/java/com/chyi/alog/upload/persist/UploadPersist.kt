package com.chyi.alog.upload.persist

import java.io.File

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

class UploadAuditLog(private val auditDir: File) {
    fun line(text: String) {
        auditDir.mkdirs()
        File(auditDir, "upload_audit.log").appendText("${System.currentTimeMillis()} $text\n")
    }
}
