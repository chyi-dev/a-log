package com.chyi.alog

data class LogItem(
    val level: Int,
    val type: Int,
    val tag: String,
    val msg: String,
    val ts: Long,
    val throwable: Throwable? = null,
    val pid: Int = 0,
    val tid: Long = 0L,
    val process: String = "",
    val file: String = "",
    val line: Int = 0,
    val threadName: String = "",
    val stackTrace: String = "",
)
