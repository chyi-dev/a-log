package com.chyi.alog.decode

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 与 ingest `to_legacy_line` / `rows_to_legacy_txt` 对齐的明文 txt 格式：
 * `YYYY-MM-dd HH:mm:ss.SSS tag:msg`，按 (ts, tag, msg) 排序，每行末尾含 `\n`。
 */
object LegacyTxtFormatter {
    private val NUMBER_FIELD = Regex(""""(\w+)"\s*:\s*(-?\d+)""")
    private val STRING_FIELD = Regex(""""(\w+)"\s*:\s*"((?:\\.|[^"\\])*)"""")

    fun toLegacyTxt(jsonLines: List<String>): String {
        val rows = jsonLines.map { parseRow(it) }
            .sortedWith(compareBy({ it.ts }, { it.tag }, { it.msg }))
        return rows.joinToString("") { formatLine(it) + "\n" }
    }

    internal data class Row(val ts: Long, val tag: String, val msg: String)

    internal fun parseRow(line: String): Row {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) {
            return Row(0L, "ALog", trimmed)
        }
        val numbers = LinkedHashMap<String, Long>()
        for (m in NUMBER_FIELD.findAll(trimmed)) {
            numbers[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: continue
        }
        val strings = LinkedHashMap<String, String>()
        for (m in STRING_FIELD.findAll(trimmed)) {
            strings[m.groupValues[1]] = unescapeJson(m.groupValues[2])
        }
        val ts = numbers["ts"] ?: 0L
        val tag = strings["tag"]?.takeIf { it.isNotBlank() } ?: "ALog"
        val msg = strings["msg"] ?: ""
        return Row(ts, tag, msg)
    }

    internal fun formatLine(row: Row): String {
        val millis = row.ts
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        val stamp = sdf.format(Date(millis)) + ".%03d".format(Locale.US, positiveMod(millis, 1000L))
        val tag = row.tag.ifBlank { "ALog" }
        val msg = row.msg
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
        return "$stamp $tag:$msg"
    }

    private fun positiveMod(value: Long, mod: Long): Long {
        val r = value % mod
        return if (r < 0) r + mod else r
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(next)
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }
}
