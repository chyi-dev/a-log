package com.chyi.alog.decode.gs

internal object GsBytes {
    fun u8(data: ByteArray, i: Int): Int = data[i].toInt() and 0xFF

    fun u16be(data: ByteArray, i: Int): Int =
        ((u8(data, i) shl 8) or u8(data, i + 1))

    fun u32be(data: ByteArray, i: Int): Long =
        ((u8(data, i).toLong() shl 24) or
            (u8(data, i + 1).toLong() shl 16) or
            (u8(data, i + 2).toLong() shl 8) or
            u8(data, i + 3).toLong())

    /** 附1: 32-bit from two big-endian 16-bit halves, low first then high. */
    fun u32FromL16H16(data: ByteArray, i: Int): Long {
        val lo = u16be(data, i).toLong()
        val hi = u16be(data, i + 2).toLong()
        return (hi shl 16) or lo
    }

    fun hex(data: ByteArray, start: Int = 0, end: Int = data.size): String =
        data.copyOfRange(start, end).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun bit(value: Long, bit: Int): Int = ((value shr bit) and 1L).toInt()

    fun bits(value: Long, from: Int, toInclusive: Int): Int {
        val width = toInclusive - from + 1
        return ((value shr from) and ((1L shl width) - 1)).toInt()
    }
}

/**
 * Print only set/non-default flags for a 32-bit status word.
 * Always includes [summary] when non-null.
 */
internal fun formatStatusBits(
    label: String,
    value: Long,
    summary: String?,
    flags: List<Pair<Int, String>>,
    multiBits: List<Triple<Int, Int, (Int) -> String?>> = emptyList(),
): List<String> {
    val lines = mutableListOf<String>()
    val head = buildString {
        append("%s=0x%08X".format(label, value))
        if (!summary.isNullOrBlank()) append(" $summary")
    }
    lines += head
    for ((bit, name) in flags) {
        if (GsBytes.bit(value, bit) == 1) {
            lines += "$name=是"
        }
    }
    for ((from, to, map) in multiBits) {
        val v = GsBytes.bits(value, from, to)
        val text = map(v)
        if (!text.isNullOrBlank()) lines += text
    }
    return lines
}
