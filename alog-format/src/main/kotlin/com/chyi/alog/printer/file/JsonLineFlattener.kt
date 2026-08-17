package com.chyi.alog.printer.file

import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType

class JsonLineFlattener : Flattener {
    override fun flatten(item: LogItem): String {
        val msg = escape(item.msg)
        return "{" +
            "\"ts\":${item.ts}," +
            "\"level\":\"${LogLevel.nameOf(item.level)}\"," +
            "\"type\":\"${LogType.nameOf(item.type)}\"," +
            "\"pid\":${item.pid}," +
            "\"tid\":${item.tid}," +
            "\"process\":\"${escape(item.process)}\"," +
            "\"tag\":\"${escape(item.tag)}\"," +
            "\"file\":\"${escape(item.file)}\"," +
            "\"line\":${item.line}," +
            "\"msg\":\"$msg\"" +
            "}"
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
