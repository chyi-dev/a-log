package com.chyi.alog.printer.file

import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePrinterTest {
    @Test
    fun flattensToJsonViaWriter() {
        val lines = mutableListOf<String>()
        val writer = object : Writer {
            override fun append(line: String) { lines.add(line) }
            override fun flush(sync: Boolean) {}
            override fun close() {}
        }
        val printer = FilePrinter.Builder(java.io.File("."))
            .writer(writer)
            .build()
        printer.println(
            LogItem(
                level = LogLevel.DEBUG,
                type = LogType.CODE,
                tag = "T",
                msg = "hello",
                ts = 1L,
            ),
        )
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"msg\":\"hello\""))
        assertTrue(lines[0].contains("\"tag\":\"T\""))
        assertTrue(!lines[0].contains("┌"))
    }
}
