package com.chyi.alog.store

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

object BlockCodec {
    private val MAGIC: ByteArray = byteArrayOf(0x41, 0x4C, 0x47, 0x31) // ALG1
    private const val VERSION: Byte = 1
    const val FLAG_COMPRESSED = 0x01
    const val FIXED_HEADER = 22

    data class Block(
        val version: Int,
        val flags: Int,
        val seq: Int,
        val unixMs: Long,
        val payload: ByteArray,
        val offset: Int,
    )

    data class ScanResult(
        val blocks: List<Block>,
        val badOffsets: List<Int>,
    )

    fun encode(
        seq: Int,
        unixMs: Long,
        payload: ByteArray,
        compressed: Boolean,
    ): ByteArray {
        val flags = if (compressed) FLAG_COMPRESSED else 0
        val size = FIXED_HEADER + payload.size + 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION)
        buf.put(flags.toByte())
        buf.putInt(seq)
        buf.putLong(unixMs)
        buf.putInt(payload.size)
        buf.put(payload)
        val crcSrc = buf.array().copyOf(FIXED_HEADER + payload.size)
        buf.putInt(crc32(crcSrc))
        return buf.array()
    }

    fun scan(bytes: ByteArray, start: Int = 0): ScanResult {
        val blocks = mutableListOf<Block>()
        val bad = mutableListOf<Int>()
        var i = start
        while (i <= bytes.size - 4) {
            if (!matchMagic(bytes, i)) {
                i++
                continue
            }
            val parsed = parseAt(bytes, i)
            if (parsed == null) {
                bad.add(i)
                i++
                continue
            }
            blocks.add(parsed)
            i = parsed.offset + FIXED_HEADER + parsed.payload.size + 4
        }
        return ScanResult(blocks, bad)
    }

    fun deflate(plain: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(plain)
        deflater.finish()
        val out = ArrayList<Byte>()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            for (k in 0 until n) out.add(buf[k])
        }
        deflater.end()
        return ByteArray(out.size) { out[it] }
    }

    fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater(false)
        inflater.setInput(compressed)
        val out = ArrayList<Byte>()
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            for (k in 0 until n) out.add(buf[k])
        }
        inflater.end()
        return ByteArray(out.size) { out[it] }
    }

    private fun parseAt(bytes: ByteArray, offset: Int): Block? {
        if (offset + FIXED_HEADER > bytes.size) return null
        val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val version = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val seq = buf.int
        val unixMs = buf.long
        val payloadLen = buf.int
        if (payloadLen < 0) return null
        if (offset + FIXED_HEADER + payloadLen + 4 > bytes.size) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)
        val crc = buf.int
        val crcSrc = bytes.copyOfRange(offset, offset + FIXED_HEADER + payloadLen)
        if (crc != crc32(crcSrc)) return null
        return Block(version, flags, seq, unixMs, payload, offset)
    }

    private fun matchMagic(bytes: ByteArray, i: Int): Boolean {
        if (i + 4 > bytes.size) return false
        return bytes[i] == MAGIC[0] && bytes[i + 1] == MAGIC[1] &&
            bytes[i + 2] == MAGIC[2] && bytes[i + 3] == MAGIC[3]
    }

    fun crc32(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }
}
