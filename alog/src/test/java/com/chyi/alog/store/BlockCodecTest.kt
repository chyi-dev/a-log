package com.chyi.alog.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockCodecTest {
    @Test
    fun roundTrip() {
        val payload = BlockCodec.deflate("{\"msg\":\"hi\"}\n".toByteArray())
        val encoded = BlockCodec.encode(1, 1000L, payload, compressed = true)
        val scan = BlockCodec.scan(encoded)
        assertEquals(1, scan.blocks.size)
        val plain = String(BlockCodec.inflate(scan.blocks[0].payload), Charsets.UTF_8)
        assertTrue(plain.contains("hi"))
    }

    @Test
    fun skipsCorruptMiddle() {
        val a = BlockCodec.encode(0, 1L, BlockCodec.deflate("A\n".toByteArray()), true)
        val b = BlockCodec.encode(1, 2L, BlockCodec.deflate("B\n".toByteArray()), true)
        val c = BlockCodec.encode(2, 3L, BlockCodec.deflate("C\n".toByteArray()), true)
        val joined = a + b + c
        val damaged = joined.copyOf()
        val mid = a.size + b.size / 2
        for (i in mid until mid + 32.coerceAtMost(b.size / 3)) {
            damaged[i] = 0
        }
        val scan = BlockCodec.scan(damaged)
        assertTrue(scan.badOffsets.isNotEmpty())
        val texts = scan.blocks.map { String(BlockCodec.inflate(it.payload), Charsets.UTF_8) }
        assertTrue(texts.any { it.contains("A") })
        assertTrue(texts.any { it.contains("C") })
    }
}
