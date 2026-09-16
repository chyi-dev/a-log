package com.chyi.alog

import com.chyi.alog.printer.Printer
import com.chyi.alog.printer.PrinterSet
import com.chyi.alog.store.MmapLogWriter
import java.io.File

/**
 * ALog 门面。必须先调用 [init]，否则打日志会抛出 [IllegalStateException]。
 *
 * 每条日志经拦截器处理后，会广播到 [init] 时传入的全部 [Printer]。
 * 常用 overload：默认 tag、指定 tag、带 [Throwable]。
 *
 * ```
 * ALog.v/d/i/w/e/f(msg)
 * ALog.v/d/i/w/e/f(tag, msg)
 * ALog.v/d/i/w/e/f(msg, throwable)
 * ALog.t(LogType.NETWORK).e(tag, msg)
 * ALog.tag("Http").d("ok")
 * ```
 */
object ALog {
    @Volatile
    private var initialized = false
    private lateinit var config: LogConfiguration
    private lateinit var printer: Printer
    private lateinit var logger: Logger

    /**
     * 初始化门面。可传入一个或多个 [Printer]，不传入的通道不会有输出。
     *
     * Debug 常见组合：[com.chyi.alog.printer.ALogPrinters.defaults]（AndroidPrinter + 文件）；
     * Release 常见仅文件 Printer（[ALogDefaults.includeAndroidPrinter] 为 false）。
     *
     * @throws IllegalStateException 不会在此处抛出；未初始化时由后续日志方法抛出
     */
    @JvmStatic
    fun init(config: LogConfiguration, vararg printers: Printer) {
        this.config = config
        this.printer = PrinterSet(printers)
        this.printer.attach(config)
        this.logger = Logger(config, printer)
        initialized = true
    }

    /**
     * 指定业务类型（不是 tag），返回链式 [Logger]。
     *
     * @see LogType
     */
    @JvmStatic
    fun t(type: Int): Logger {
        assertInit()
        return logger.t(type)
    }

    /** 指定本次日志的 tag，返回链式 [Logger]。 */
    @JvmStatic
    fun tag(tag: String): Logger {
        assertInit()
        return logger.tag(tag)
    }

    /** VERBOSE：使用配置中的默认 tag。 */
    @JvmStatic
    fun v(msg: String) { assertInit(); logger.v(msg) }
    /** VERBOSE：指定 tag。 */
    @JvmStatic
    fun v(tag: String, msg: String) { assertInit(); logger.v(tag, msg) }
    /** VERBOSE：默认 tag，附带异常。 */
    @JvmStatic
    fun v(msg: String, tr: Throwable) { assertInit(); logger.v(msg, tr) }

    /** DEBUG：使用配置中的默认 tag。 */
    @JvmStatic
    fun d(msg: String) { assertInit(); logger.d(msg) }
    /** DEBUG：指定 tag。 */
    @JvmStatic
    fun d(tag: String, msg: String) { assertInit(); logger.d(tag, msg) }
    /** DEBUG：默认 tag，附带异常。 */
    @JvmStatic
    fun d(msg: String, tr: Throwable) { assertInit(); logger.d(msg, tr) }

    /** INFO：使用配置中的默认 tag。 */
    @JvmStatic
    fun i(msg: String) { assertInit(); logger.i(msg) }
    /** INFO：指定 tag。 */
    @JvmStatic
    fun i(tag: String, msg: String) { assertInit(); logger.i(tag, msg) }
    /** INFO：默认 tag，附带异常。 */
    @JvmStatic
    fun i(msg: String, tr: Throwable) { assertInit(); logger.i(msg, tr) }

    /** WARN：使用配置中的默认 tag。 */
    @JvmStatic
    fun w(msg: String) { assertInit(); logger.w(msg) }
    /** WARN：指定 tag。 */
    @JvmStatic
    fun w(tag: String, msg: String) { assertInit(); logger.w(tag, msg) }
    /** WARN：默认 tag，附带异常。 */
    @JvmStatic
    fun w(msg: String, tr: Throwable) { assertInit(); logger.w(msg, tr) }

    /** ERROR：使用配置中的默认 tag。 */
    @JvmStatic
    fun e(msg: String) { assertInit(); logger.e(msg) }
    /** ERROR：指定 tag。 */
    @JvmStatic
    fun e(tag: String, msg: String) { assertInit(); logger.e(tag, msg) }
    /** ERROR：默认 tag，附带异常。 */
    @JvmStatic
    fun e(msg: String, tr: Throwable) { assertInit(); logger.e(msg, tr) }

    /** FATAL：使用配置中的默认 tag。文件通道会对 FATAL 立即同步刷盘。 */
    @JvmStatic
    fun f(msg: String) { assertInit(); logger.f(msg) }
    /** FATAL：指定 tag。 */
    @JvmStatic
    fun f(tag: String, msg: String) { assertInit(); logger.f(tag, msg) }
    /** FATAL：默认 tag，附带异常。 */
    @JvmStatic
    fun f(msg: String, tr: Throwable) { assertInit(); logger.f(msg, tr) }

    /**
     * 刷盘。
     *
     * @param sync `true` 时阻塞等待落盘完成。
     */
    @JvmStatic
    fun flush(sync: Boolean) {
        assertInit()
        printer.flush(sync)
    }

    /**
     * 上传前准备：若已初始化则同步 [flush]，回收已死进程的 mmap，再收集可上传的 `.alog` 文件。
     *
     * 未调用 [init] 时不抛错。上传模块应在读文件前调用。
     *
     * @param logRoot 日志目录（及其一级子目录）
     * @param cacheRoot mmap 缓存目录
     * @param livePids 当前仍存活的进程 pid，这些进程的 mmap 不会被回收
     * @param currentPid 当前进程 pid
     * @return `logRoot` 及一级子目录下的 `*.alog`，不含 `.mm`
     */
    @JvmStatic
    fun prepareForUpload(
        logRoot: File,
        cacheRoot: File,
        livePids: Set<Int>,
        currentPid: Int = 0,
    ): List<File> {
        try {
            if (initialized) flush(true)
        } catch (_: Throwable) {
        }
        MmapLogWriter.recoverOrphans(logRoot, cacheRoot, livePids, currentPid)
        return collectAlogFiles(logRoot)
    }

    /**
     * 收集 [logRoot] 及其一级子目录下的 `*.alog` 文件（不含更深嵌套，也不含 `.mm`）。
     *
     * @return 按绝对路径排序的文件列表
     */
    @JvmStatic
    fun collectAlogFiles(logRoot: File): List<File> {
        if (!logRoot.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        logRoot.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        val dirs = logRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        }
        return out.sortedBy { it.absolutePath }
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
