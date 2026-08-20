package com.chyi.alog.printer.file

import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType

/**
 * 默认序列化：每条日志一行 JSON，字段为 `ts`、`level`、`type`、`tag`、`msg`。
 * 装饰字段（线程名、调用栈、边框）不会写入。
 */
class JsonLineFlattener : Flattener {
    override fun flatten(item: LogItem): String {
        val msg = escape(item.msg)
        return "{" +
            "\"ts\":${item.ts}," +
            "\"level\":\"${LogLevel.nameOf(item.level)}\"," +
            "\"type\":\"${LogType.nameOf(item.type)}\"," +
            "\"tag\":\"${escape(item.tag)}\"," +
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
