package com.chyi.alog.decode.gs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GsSerialAnnotatorTest {
    @Test
    fun annotatesSendReceiveAndKeepsBusinessLines() {
        val input = """
            2026-08-18 00:00:00.901 Coffee-Machine:/dev/ttyS4---发送：AA 55 02 1E 1F
            2026-08-18 00:00:00.925 Coffee-Machine:/dev/ttyS4---接收：A5 5A 0A 1E 00 00 00 00 00 00 00 00 27
            2026-08-18 00:00:02.859 Coffee-Machine:播放完毕，开启下一轮播放;datas.size();1
        """.trimIndent() + "\n"
        val out = GsSerialAnnotator.annotate(input)
        val lines = out.lines().filter { it.isNotEmpty() }
        assertTrue(lines[0].contains("发送：AA 55 02 1E 1F"))
        assertTrue(lines[1].startsWith("  "))
        assertTrue(lines[1].contains("0x1E"))
        assertTrue(lines[1].contains("查询主控运行状态"))
        assertTrue(lines[1].contains("SUM=ok"))
        assertTrue(lines[2].contains("接收：A5 5A"))
        assertTrue(lines[3].startsWith("  "))
        assertTrue(lines[3].contains("查询主控运行状态"))
        assertTrue(lines.any { it.endsWith("播放完毕，开启下一轮播放;datas.size();1") && !it.startsWith("  ") })
    }

    @Test
    fun annotatesCompactReceivedMessage() {
        val input = "2026-08-18 00:00:00.926 Coffee-Machine:查询主控运行状态_收到消息：A55A0A1E000000000000000027\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.contains("查询主控运行状态"))
        assertTrue(out.lines().any { it.startsWith("  ") && it.contains("0x1E") })
    }

    @Test
    fun marksNonGsFrame() {
        val input = "2026-08-18 00:00:06.282 Coffee-Machine:/dev/ttyS3---接收：46 46 20 0D 0A\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.contains("非高盛帧"))
    }

    @Test
    fun emptyData20HasNoModbusPayloadNote() {
        val input = "2026-08-18 00:00:01.045 Coffee-Machine:/dev/ttyS3---发送：AA 55 02 20 21\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.contains("0x20"))
        assertTrue(out.contains("无 Modbus 载荷") || out.contains("无Modbus载荷"))
    }

    @Test
    fun preservesTrailingNewline() {
        val input = "2026-08-18 00:00:00.901 Coffee-Machine:/dev/ttyS4---发送：AA 55 02 1E 1F\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.endsWith("\n"))
        assertFalse(out.endsWith("\n\n"))
    }

    @Test
    fun decodesPassthroughReadMainboardExample() {
        val input = "t Coffee-Machine:/dev/ttyS4---发送：AA 55 08 20 01 03 00 00 00 02 2D\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.contains("主控"))
        assertTrue(out.contains("START-REG") || out.contains("0x0000"))
    }

    @Test
    fun decodes0CMakeCompleteFixedMessage() {
        val input = "t Coffee-Machine:/dev/ttyS4---接收：A5 5A 03 0C 10 1E\n"
        val out = GsSerialAnnotator.annotate(input)
        assertTrue(out.contains("饮料制作完成"))
    }
}
