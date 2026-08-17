package com.chyi.alog

object ProcessInfo {
    @Volatile
    var pid: Int = 0

    @Volatile
    var processName: String = "main"
}
