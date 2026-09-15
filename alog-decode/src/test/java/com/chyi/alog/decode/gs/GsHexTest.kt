package com.chyi.alog.decode.gs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GsHexTest {
    @Test
    fun parsesSpacedHex() {
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0x55, 0x02, 0x1E, 0x1F),
            GsHex.parse("AA 55 02 1E 1F"),
        )
    }

    @Test
    fun parsesCompactHex() {
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A, 0x0A, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x27),
            GsHex.parse("A55A0A1E000000000000000027"),
        )
    }

    @Test
    fun rejectsOddLengthAndNonHex() {
        assertNull(GsHex.parse("AA 5"))
        assertNull(GsHex.parse("ZZ 11"))
        assertNull(GsHex.parse(""))
    }
}
