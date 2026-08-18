package com.chyi.alog.upload

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogUploaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun skipEmptyUploadDoesNotThrow() {
        val audit = tmp.newFolder("audit")
        val uploader = LogUploader(
            baseUrl = "http://127.0.0.1:1",
            token = "alog-dev",
            auditDir = audit,
            meta = UploadMeta("app", "u", "d", "1.0", "1"),
        )
        val result = uploader.upload(emptyList(), "manual")
        assertEquals("", result.uploadId)
        val log = File(audit, "upload_audit.log").readText()
        assertEquals(true, log.contains("skip empty upload"))
    }
}
