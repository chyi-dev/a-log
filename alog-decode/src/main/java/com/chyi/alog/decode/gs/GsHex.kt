package com.chyi.alog.decode.gs

/**
 * Hex string helpers for GS coffee-machine UART dumps (spaced or compact).
 * Protocol: GS_CF_PR_API_1.5F
 */
object GsHex {
    fun parse(raw: String): ByteArray? {
        val cleaned = raw.trim().replace(Regex("\\s+"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = cleaned[i * 2].digitToIntOrNull(16) ?: return null
            val lo = cleaned[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun toSpaced(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
