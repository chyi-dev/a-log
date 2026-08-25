package com.chyi.alog.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LegacyTxtFormatterTest {
    @Test
    fun formatsTagMsgAndFlattensNewlines() {
        val line = LegacyTxtFormatter.formatLine(
            LegacyTxtFormatter.Row(100L, "Coffee-Machine", "line\nwith\nbreaks"),
        )
        assertTrue(line.matches(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} Coffee-Machine:line with breaks$""")))
        assertEquals(expectedStamp(100L) + " Coffee-Machine:line with breaks", line)
    }

    @Test
    fun defaultTagWhenMissing() {
        val row = LegacyTxtFormatter.parseRow("""{"ts":100,"msg":"hello"}""")
        assertEquals("ALog", row.tag)
        assertEquals(100L, row.ts)
        assertEquals("hello", row.msg)
    }

    @Test
    fun nonJsonFallsBackToWholeLine() {
        val row = LegacyTxtFormatter.parseRow("not-json")
        assertEquals(0L, row.ts)
        assertEquals("ALog", row.tag)
        assertEquals("not-json", row.msg)
    }

    @Test
    fun sortsByTsTagMsgAndEndsWithNewline() {
        val txt = LegacyTxtFormatter.toLegacyTxt(
            listOf(
                """{"ts":300,"tag":"Main","msg":"late"}""",
                """{"ts":100,"tag":"Push","msg":"early"}""",
                """{"ts":200,"tag":"Main","msg":"mid"}""",
            ),
        )
        val lines = txt.split("\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("Push:early"))
        assertTrue(lines[1].endsWith("Main:mid"))
        assertTrue(lines[2].endsWith("Main:late"))
        assertTrue(txt.endsWith("\n"))
        assertEquals(
            expectedStamp(100L) + " Push:early\n" +
                expectedStamp(200L) + " Main:mid\n" +
                expectedStamp(300L) + " Main:late\n",
            txt,
        )
    }

    @Test
    fun emptyInputYieldsEmptyString() {
        assertEquals("", LegacyTxtFormatter.toLegacyTxt(emptyList()))
    }

    private fun expectedStamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        val rem = ((millis % 1000) + 1000) % 1000
        return sdf.format(Date(millis)) + ".%03d".format(Locale.US, rem)
    }
}
