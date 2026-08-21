package com.chyi.alog.store

data class FileHeader(
    val version: Int = 1,
) {
    fun toBytes(): ByteArray {
        val out = ByteArray(SIZE)
        MAGIC.copyInto(out)
        out[4] = version.toByte()
        return out
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x41, 0x4C, 0x47, 0x46) // ALGF
        const val SIZE = 5

        fun parse(bytes: ByteArray): Pair<FileHeader, Int>? {
            if (bytes.size < SIZE) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] ||
                bytes[2] != MAGIC[2] || bytes[3] != MAGIC[3]
            ) {
                return null
            }
            val version = bytes[4].toInt() and 0xFF
            return FileHeader(version = version) to SIZE
        }
    }
}
