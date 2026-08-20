package com.chyi.alog

/**
 * 当前进程信息，写入 [LogItem.pid] / [LogItem.process]。
 *
 * 应在 Application 启动时赋值，例如 `Process.myPid()` 与当前进程名。
 */
object ProcessInfo {
    @Volatile
    var pid: Int = 0

    @Volatile
    var processName: String = "main"
}
