package com.chyi.alog.printer

import android.util.Log
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType
import com.chyi.alog.formatter.BorderFormatter

/**
 * 输出到 Android Logcat。
 *
 * @param autoSeparate 为 `true`（默认）时，超过约 4KB 的消息会分段打印。
 */
class AndroidPrinter(
    private val autoSeparate: Boolean = true,
) : Printer {
    private var config: LogConfiguration? = null

    override fun attach(config: LogConfiguration) {
        this.config = config
    }

    override fun println(item: LogItem) {
        val cfg = config
        val body = buildMessage(item, cfg)
        val androidLevel = toAndroidLevel(item.level)
        if (!autoSeparate || body.length <= MAX_LEN) {
            Log.println(androidLevel, item.tag, body)
            return
        }
        var start = 0
        var part = 1
        while (start < body.length) {
            val end = (start + MAX_LEN).coerceAtMost(body.length)
            Log.println(androidLevel, item.tag, "[$part] ${body.substring(start, end)}")
            start = end
            part++
        }
    }

    private fun buildMessage(item: LogItem, cfg: LogConfiguration?): String {
        val typeLabel = "type=${LogType.nameOf(item.type)}"
        val sections = mutableListOf<String>()
        if (cfg?.threadInfo == true && item.threadName.isNotEmpty()) {
            sections.add("Thread: ${item.threadName}")
        }
        if (cfg != null && cfg.stackTraceDepth > 0 && item.stackTrace.isNotEmpty()) {
            sections.add(item.stackTrace)
        }
        val msg = if (item.throwable != null) {
            item.msg + '\n' + Log.getStackTraceString(item.throwable)
        } else {
            item.msg
        }
        sections.add("$typeLabel $msg")
        return if (cfg?.border == true) BorderFormatter.format(sections) else sections.joinToString("\n")
    }

    private fun toAndroidLevel(level: Int): Int = when (level) {
        LogLevel.VERBOSE -> Log.VERBOSE
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR, LogLevel.FATAL -> Log.ERROR
        else -> Log.DEBUG
    }

    companion object {
        private const val MAX_LEN = 4000
    }
}
