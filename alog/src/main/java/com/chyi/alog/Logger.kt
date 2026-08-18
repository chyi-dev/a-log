package com.chyi.alog

import com.chyi.alog.printer.Printer

class Logger internal constructor(
    private val config: LogConfiguration,
    private val printer: Printer,
    private val type: Int = LogType.CODE,
    private val tagOverride: String? = null,
) {
    fun t(type: Int): Logger = Logger(config, printer, type, tagOverride)

    fun tag(tag: String): Logger = Logger(config, printer, type, tag)

    fun v(msg: String) = println(LogLevel.VERBOSE, config.tag, msg, null)
    fun v(tag: String, msg: String) = println(LogLevel.VERBOSE, tag, msg, null)
    fun v(msg: String, tr: Throwable) = println(LogLevel.VERBOSE, config.tag, msg, tr)

    fun d(msg: String) = println(LogLevel.DEBUG, config.tag, msg, null)
    fun d(tag: String, msg: String) = println(LogLevel.DEBUG, tag, msg, null)
    fun d(msg: String, tr: Throwable) = println(LogLevel.DEBUG, config.tag, msg, tr)

    fun i(msg: String) = println(LogLevel.INFO, config.tag, msg, null)
    fun i(tag: String, msg: String) = println(LogLevel.INFO, tag, msg, null)
    fun i(msg: String, tr: Throwable) = println(LogLevel.INFO, config.tag, msg, tr)

    fun w(msg: String) = println(LogLevel.WARN, config.tag, msg, null)
    fun w(tag: String, msg: String) = println(LogLevel.WARN, tag, msg, null)
    fun w(msg: String, tr: Throwable) = println(LogLevel.WARN, config.tag, msg, tr)

    fun e(msg: String) = println(LogLevel.ERROR, config.tag, msg, null)
    fun e(tag: String, msg: String) = println(LogLevel.ERROR, tag, msg, null)
    fun e(msg: String, tr: Throwable) = println(LogLevel.ERROR, config.tag, msg, tr)

    fun f(msg: String) = println(LogLevel.FATAL, config.tag, msg, null)
    fun f(tag: String, msg: String) = println(LogLevel.FATAL, tag, msg, null)
    fun f(msg: String, tr: Throwable) = println(LogLevel.FATAL, config.tag, msg, tr)

    private fun println(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < config.logLevel) return
        val resolvedTag = tagOverride ?: tag
        var item = LogItem(
            level = level,
            type = type,
            tag = resolvedTag,
            msg = msg,
            ts = System.currentTimeMillis(),
            throwable = tr,
            pid = ProcessInfo.pid,
            tid = Thread.currentThread().id,
            process = ProcessInfo.processName,
            threadName = if (config.threadInfo) Thread.currentThread().name else "",
        )
        for (interceptor in config.interceptors) {
            item = interceptor.intercept(item) ?: return
        }
        printer.println(item)
    }
}
