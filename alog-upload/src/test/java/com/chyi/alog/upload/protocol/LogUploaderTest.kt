package com.chyi.alog.upload.protocol

import com.chyi.alog.upload.UploadMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(log.contains("skip empty upload"))
    }

    @Test
    fun streamSha256MatchesWholeFile() {
        val file = tmp.newFile("x.alog")
        file.writeBytes(ByteArray(8 * 1024) { it.toByte() })
        val streamed = LogUploader.sha256(file)
        val expected = LogUploader.sha256(file.readBytes())
        assertEquals(expected, streamed)
        assertEquals(64, streamed.length)
    }
}

class AlogFileCollectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun truncatesWhenOverMaxBytes() {
        val old = tmp.newFile("alog_20200101_0.alog")
        old.writeBytes(ByteArray(100))
        old.setLastModified(1L)
        val newer = tmp.newFile("alog_20990101_0.alog")
        newer.writeBytes(ByteArray(100))
        newer.setLastModified(2L)
        val selected = com.chyi.alog.upload.collector.AlogFileCollector.select(
            listOf(old, newer),
            maxBytes = 150,
            recentDays = null,
        )
        assertEquals(1, selected.size)
        assertEquals(newer.name, selected[0].name)
        assertTrue(
            com.chyi.alog.upload.collector.AlogFileCollector.truncated(
                listOf(old, newer),
                selected,
                recentDays = null,
            ),
        )
    }

    @Test
    fun recentDaysDropsOldNamedFiles() {
        val old = tmp.newFile("alog_20200101_0.alog")
        old.writeText("old")
        val recent = tmp.newFile("alog_20991231_0.alog")
        recent.writeText("new")
        val selected = com.chyi.alog.upload.collector.AlogFileCollector.select(
            listOf(old, recent),
            maxBytes = 50_000,
            recentDays = 2,
            nowMs = SimpleDateFormatHolder.parse("20991231"),
        )
        assertEquals(listOf(recent.name), selected.map { it.name })
        assertFalse(
            com.chyi.alog.upload.collector.AlogFileCollector.inWindow(
                old,
                recentDays = 2,
                nowMs = SimpleDateFormatHolder.parse("20991231"),
            ),
        )
    }

    @Test
    fun recentDaysParsesDateOnlyFileNameWithoutSeq() {
        val old = tmp.newFile("alog_20200101.alog")
        old.writeText("old")
        old.setLastModified(System.currentTimeMillis())
        val recent = tmp.newFile("alog_20991231.alog")
        recent.writeText("new")
        recent.setLastModified(1L)
        val selected = com.chyi.alog.upload.collector.AlogFileCollector.select(
            listOf(old, recent),
            maxBytes = 50_000,
            recentDays = 2,
            nowMs = SimpleDateFormatHolder.parse("20991231"),
        )
        assertEquals(listOf(recent.name), selected.map { it.name })
        assertTrue(
            com.chyi.alog.upload.collector.AlogFileCollector.inWindow(
                recent,
                recentDays = 2,
                nowMs = SimpleDateFormatHolder.parse("20991231"),
            ),
        )
        assertFalse(
            com.chyi.alog.upload.collector.AlogFileCollector.inWindow(
                old,
                recentDays = 2,
                nowMs = SimpleDateFormatHolder.parse("20991231"),
            ),
        )
    }
}

private object SimpleDateFormatHolder {
    fun parse(yyyyMMdd: String): Long {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return fmt.parse(yyyyMMdd)!!.time
    }
}
