package com.chyi.alog

/**
 * 一条经过拦截器处理、即将交给 [com.chyi.alog.printer.Printer] 的日志。
 *
 * [file]、[line]、[stackTrace] 当前门面不会填充；线程名仅在开启 [LogConfiguration.Builder.enableThreadInfo] 时写入。
 */
data class LogItem(
    /** 级别，见 [LogLevel]。 */
    val level: Int,
    /** 业务类型，见 [LogType]。 */
    val type: Int,
    val tag: String,
    val msg: String,
    /** 毫秒时间戳。 */
    val ts: Long,
    val throwable: Throwable? = null,
    val pid: Int = 0,
    val tid: Long = 0L,
    val process: String = "",
    /** 调用文件名。当前门面不填充。 */
    val file: String = "",
    /** 调用行号。当前门面不填充。 */
    val line: Int = 0,
    val threadName: String = "",
    /** 调用栈文本。当前门面不填充。 */
    val stackTrace: String = "",
)
