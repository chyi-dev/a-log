package com.chyi.alog.store

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FileHeader(
    val version: Int = 1,
    val flags: Int = 0,
    val keyId: String = "",
    val wrappedDek: ByteArray = ByteArray(0),
) {
    fun toBytes(): ByteArray {
        val keyBytes = keyId.toByteArray(Charsets.UTF_8)
        val size = 4 + 1 + 1 + 2 + keyBytes.size + 2 + wrappedDek.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(version.toByte())
        buf.put(flags.toByte())
        buf.putShort(keyBytes.size.toShort())
        buf.put(keyBytes)
        buf.putShort(wrappedDek.size.toShort())
        buf.put(wrappedDek)
        return buf.array()
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x41, 0x4C, 0x47, 0x46) // ALGF
        const val FLAG_HAS_DEK = 0x01

        fun parse(bytes: ByteArray): Pair<FileHeader, Int>? {
            if (bytes.size < 8) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] ||
                bytes[2] != MAGIC[2] || bytes[3] != MAGIC[3]
            ) {
                return null
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.position(4)
            val version = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val keyLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < keyLen + 2) return null
            val keyBytes = ByteArray(keyLen)
            buf.get(keyBytes)
            val dekLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < dekLen) return null
            val dek = ByteArray(dekLen)
            buf.get(dek)
            val header = FileHeader(
                version = version,
                flags = flags,
                keyId = String(keyBytes, Charsets.UTF_8),
                wrappedDek = dek,
            )
            return header to buf.position()
        }
    }
}
