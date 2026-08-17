package com.chyi.alog

import com.chyi.alog.printer.Printer
import com.chyi.alog.printer.PrinterSet

object ALog {
    @Volatile
    private var initialized = false
    private lateinit var config: LogConfiguration
    private lateinit var printer: Printer
    private lateinit var logger: Logger

    @JvmStatic
    fun init(config: LogConfiguration, vararg printers: Printer) {
        this.config = config
        this.printer = PrinterSet(printers)
        this.printer.attach(config)
        this.logger = Logger(config, printer)
        initialized = true
    }

    @JvmStatic
    fun t(type: Int): Logger {
        assertInit()
        return logger.t(type)
    }

    @JvmStatic
    fun tag(tag: String): Logger {
        assertInit()
        return logger.tag(tag)
    }

    @JvmStatic
    fun v(msg: String) { assertInit(); logger.v(msg) }
    @JvmStatic
    fun v(tag: String, msg: String) { assertInit(); logger.v(tag, msg) }
    @JvmStatic
    fun v(msg: String, tr: Throwable) { assertInit(); logger.v(msg, tr) }

    @JvmStatic
    fun d(msg: String) { assertInit(); logger.d(msg) }
    @JvmStatic
    fun d(tag: String, msg: String) { assertInit(); logger.d(tag, msg) }
    @JvmStatic
    fun d(msg: String, tr: Throwable) { assertInit(); logger.d(msg, tr) }

    @JvmStatic
    fun i(msg: String) { assertInit(); logger.i(msg) }
    @JvmStatic
    fun i(tag: String, msg: String) { assertInit(); logger.i(tag, msg) }
    @JvmStatic
    fun i(msg: String, tr: Throwable) { assertInit(); logger.i(msg, tr) }

    @JvmStatic
    fun w(msg: String) { assertInit(); logger.w(msg) }
    @JvmStatic
    fun w(tag: String, msg: String) { assertInit(); logger.w(tag, msg) }
    @JvmStatic
    fun w(msg: String, tr: Throwable) { assertInit(); logger.w(msg, tr) }

    @JvmStatic
    fun e(msg: String) { assertInit(); logger.e(msg) }
    @JvmStatic
    fun e(tag: String, msg: String) { assertInit(); logger.e(tag, msg) }
    @JvmStatic
    fun e(msg: String, tr: Throwable) { assertInit(); logger.e(msg, tr) }

    @JvmStatic
    fun f(msg: String) { assertInit(); logger.f(msg) }
    @JvmStatic
    fun f(tag: String, msg: String) { assertInit(); logger.f(tag, msg) }
    @JvmStatic
    fun f(msg: String, tr: Throwable) { assertInit(); logger.f(msg, tr) }

    @JvmStatic
    fun flush(sync: Boolean) {
        assertInit()
        printer.flush(sync)
    }

    internal fun resetForTest() {
        initialized = false
    }

    private fun assertInit() {
        if (!initialized) {
            throw IllegalStateException("Do you forget to initialize ALog?")
        }
    }
}
