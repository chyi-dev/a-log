package com.chyi.alog

import com.chyi.alog.printer.BackpressuredPrinter
import com.chyi.alog.printer.Printer

/**
 * 带类型 / tag 覆盖的日志写入器。通常由 [ALog.t] 或 [ALog.tag] 得到，也可继续链式调用。
 *
 * 与 [ALog] 相同的 overload：默认 tag、指定 tag、带 [Throwable]。低于配置 [LogConfiguration.logLevel] 的级别会被丢弃。
 */
class Logger internal constructor(
    private val config: LogConfiguration,
    private val printer: Printer,
    private val type: Int = LogType.CODE,
    private val tagOverride: String? = null,
) {
    /** 覆盖业务类型（不是 tag），返回新的 [Logger]。 */
    fun t(type: Int): Logger = Logger(config, printer, type, tagOverride)

    /** 覆盖 tag，返回新的 [Logger]。 */
    fun tag(tag: String): Logger = Logger(config, printer, type, tag)

    /** VERBOSE：使用配置中的默认 tag。 */
    fun v(msg: String) = println(LogLevel.VERBOSE, config.tag, msg, null)
    /** VERBOSE：指定 tag。 */
    fun v(tag: String, msg: String) = println(LogLevel.VERBOSE, tag, msg, null)
    /** VERBOSE：默认 tag，附带异常。 */
    fun v(msg: String, tr: Throwable) = println(LogLevel.VERBOSE, config.tag, msg, tr)

    /** DEBUG：使用配置中的默认 tag。 */
    fun d(msg: String) = println(LogLevel.DEBUG, config.tag, msg, null)
    /** DEBUG：指定 tag。 */
    fun d(tag: String, msg: String) = println(LogLevel.DEBUG, tag, msg, null)
    /** DEBUG：默认 tag，附带异常。 */
    fun d(msg: String, tr: Throwable) = println(LogLevel.DEBUG, config.tag, msg, tr)

    /** INFO：使用配置中的默认 tag。 */
    fun i(msg: String) = println(LogLevel.INFO, config.tag, msg, null)
    /** INFO：指定 tag。 */
    fun i(tag: String, msg: String) = println(LogLevel.INFO, tag, msg, null)
    /** INFO：默认 tag，附带异常。 */
    fun i(msg: String, tr: Throwable) = println(LogLevel.INFO, config.tag, msg, tr)

    /** WARN：使用配置中的默认 tag。 */
    fun w(msg: String) = println(LogLevel.WARN, config.tag, msg, null)
    /** WARN：指定 tag。 */
    fun w(tag: String, msg: String) = println(LogLevel.WARN, tag, msg, null)
    /** WARN：默认 tag，附带异常。 */
    fun w(msg: String, tr: Throwable) = println(LogLevel.WARN, config.tag, msg, tr)

    /** ERROR：使用配置中的默认 tag。 */
    fun e(msg: String) = println(LogLevel.ERROR, config.tag, msg, null)
    /** ERROR：指定 tag。 */
    fun e(tag: String, msg: String) = println(LogLevel.ERROR, tag, msg, null)
    /** ERROR：默认 tag，附带异常。 */
    fun e(msg: String, tr: Throwable) = println(LogLevel.ERROR, config.tag, msg, tr)

    /** FATAL：使用配置中的默认 tag。 */
    fun f(msg: String) = println(LogLevel.FATAL, config.tag, msg, null)
    /** FATAL：指定 tag。 */
    fun f(tag: String, msg: String) = println(LogLevel.FATAL, tag, msg, null)
    /** FATAL：默认 tag，附带异常。 */
    fun f(msg: String, tr: Throwable) = println(LogLevel.FATAL, config.tag, msg, tr)

    private fun println(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < config.logLevel) return
        if (printer is BackpressuredPrinter && !printer.acceptMore()) return
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
