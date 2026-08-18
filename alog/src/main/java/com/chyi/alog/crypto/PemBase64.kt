package com.chyi.alog.crypto

internal object PemBase64 {
    private val TABLE = IntArray(128) { -1 }.also { t ->
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in chars.indices) t[chars[i].code] = i
    }

    fun decode(pemBody: String): ByteArray {
        val filtered = pemBody.filter { it.code < 128 && TABLE[it.code] != -1 || it == '=' }
        val out = ArrayList<Byte>(filtered.length)
        var i = 0
        while (i < filtered.length) {
            val remaining = filtered.length - i
            if (remaining < 4) break
            val a = value(filtered[i])
            val b = value(filtered[i + 1])
            val c = value(filtered[i + 2])
            val d = value(filtered[i + 3])
            val triple = (a shl 18) or (b shl 12) or ((if (c < 0) 0 else c) shl 6) or (if (d < 0) 0 else d)
            out.add(((triple shr 16) and 0xFF).toByte())
            if (filtered[i + 2] != '=') out.add(((triple shr 8) and 0xFF).toByte())
            if (filtered[i + 3] != '=') out.add((triple and 0xFF).toByte())
            i += 4
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun value(ch: Char): Int {
        if (ch == '=') return -1
        return TABLE[ch.code]
    }
}
