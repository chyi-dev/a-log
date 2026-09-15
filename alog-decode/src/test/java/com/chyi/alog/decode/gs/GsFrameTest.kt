package com.chyi.alog.decode.gs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GsFrameTest {
    @Test
    fun parsesCommand1E() {
        val bytes = GsHex.parse("AA 55 02 1E 1F")!!
        val frames = GsFrame.scan(bytes)
        assertEquals(1, frames.size)
        val f = frames[0]
        assertTrue(f.isCommand)
        assertEquals(0x1E, f.command)
        assertEquals(2, f.len)
        assertTrue(f.data.isEmpty())
        assertTrue(f.checksumOk)
        assertEquals(0x1F, f.sum)
    }

    @Test
    fun parsesResponse1E() {
        val bytes = GsHex.parse("A5 5A 0A 1E 00 00 00 00 00 00 00 00 27")!!
        val frames = GsFrame.scan(bytes)
        assertEquals(1, frames.size)
        val f = frames[0]
        assertFalse(f.isCommand)
        assertEquals(0x1E, f.command)
        assertEquals(0x0A, f.len)
        assertEquals(8, f.data.size)
        assertTrue(f.checksumOk)
    }

    @Test
    fun detectsBadChecksum() {
        val bytes = GsHex.parse("AA 55 02 1E 00")!!
        val frames = GsFrame.scan(bytes)
        assertEquals(1, frames.size)
        assertFalse(frames[0].checksumOk)
        assertEquals(0x1F, frames[0].expectedSum)
    }

    @Test
    fun scansStickyPackets() {
        val bytes = GsHex.parse("AA 55 02 1E 1F A5 5A 0A 1E 00 00 00 00 00 00 00 00 27")!!
        val frames = GsFrame.scan(bytes)
        assertEquals(2, frames.size)
        assertTrue(frames[0].isCommand)
        assertFalse(frames[1].isCommand)
    }

    @Test
    fun truncatesIncompleteFrame() {
        val bytes = GsHex.parse("AA 55 0A 1E 00")!!
        assertTrue(GsFrame.scan(bytes).isEmpty())
    }

    @Test
    fun ignoresNonGsPrefixThenFindsFrame() {
        val bytes = GsHex.parse("46 46 20 AA 55 02 1E 1F")!!
        val frames = GsFrame.scan(bytes)
        assertEquals(1, frames.size)
        assertEquals(0x1E, frames[0].command)
    }
}
