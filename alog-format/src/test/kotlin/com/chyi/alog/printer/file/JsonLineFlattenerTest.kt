package com.chyi.alog.printer.file

import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonLineFlattenerTest {
    @Test
    fun keepsOnlyTsLevelTypeTagMsg() {
        val line = JsonLineFlattener().flatten(
            LogItem(
                level = LogLevel.INFO,
                type = LogType.CODE,
                tag = "ALog",
                msg = "hello \"x\"",
                ts = 1730000000123L,
                pid = 12345,
                tid = 67890L,
                process = "com.chyi.alog.sample",
                file = "MainActivity.kt",
                line = 76,
            ),
        )
        assertEquals(
            "{\"ts\":1730000000123,\"level\":\"I\",\"type\":\"code\",\"tag\":\"ALog\",\"msg\":\"hello \\\"x\\\"\"}",
            line,
        )
        assertFalse(line.contains("\"pid\""))
        assertFalse(line.contains("\"tid\""))
        assertFalse(line.contains("\"process\""))
        assertFalse(line.contains("\"file\""))
        assertFalse(line.contains("\"line\""))
        assertTrue(line.contains("\"msg\":\"hello \\\"x\\\"\""))
    }
}
