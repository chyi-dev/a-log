package com.chyi.alog.printer.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimpleWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesPlainJsonLinesWithoutAlgfHeader() {
        val dir = tmp.newFolder("simple")
        val writer = SimpleWriter(dir, "alog", maxFileSize = 1024 * 1024)
        writer.append("{\"ts\":1,\"level\":\"I\",\"type\":\"code\",\"tag\":\"T\",\"msg\":\"plain\"}")
        writer.flush(true)
        writer.close()
        val alog = dir.listFiles { f -> f.name.endsWith(".alog") }!!.first()
        val text = alog.readText(Charsets.UTF_8)
        assertFalse(text.startsWith("ALGF"))
        assertTrue(text.contains("\"msg\":\"plain\""))
        assertEquals(1, text.trim().lines().size)
    }
}
