package com.chyi.alog

/** 日志级别。低于 [LogConfiguration] 配置级别的日志会被丢弃。 */
object LogLevel {
    /** 输出全部级别。 */
    const val ALL = 0
    const val VERBOSE = 1
    const val DEBUG = 2
    const val INFO = 3
    const val WARN = 4
    const val ERROR = 5
    const val FATAL = 6
    /** 关闭全部输出。 */
    const val NONE = 7

    /** 将级别映射为单字母（V/D/I/W/E/F），未知级别返回 `"?"`。 */
    fun nameOf(level: Int): String = when (level) {
        VERBOSE -> "V"
        DEBUG -> "D"
        INFO -> "I"
        WARN -> "W"
        ERROR -> "E"
        FATAL -> "F"
        else -> "?"
    }
}
