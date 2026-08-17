package com.chyi.alog

object LogLevel {
    const val ALL = 0
    const val VERBOSE = 1
    const val DEBUG = 2
    const val INFO = 3
    const val WARN = 4
    const val ERROR = 5
    const val FATAL = 6
    const val NONE = 7

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
