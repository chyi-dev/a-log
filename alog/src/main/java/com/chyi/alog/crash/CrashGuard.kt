package com.chyi.alog.crash

import com.chyi.alog.ALog
import com.chyi.alog.LogType
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashGuard {
    const val FLAG_FILE = "alog_crash_flag"

    fun install(filesDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                ALog.t(LogType.CRASH).f("uncaught in ${thread.name}: $sw")
                ALog.flush(true)
                File(filesDir, FLAG_FILE).writeText(System.currentTimeMillis().toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun consumePendingCrash(filesDir: File): Boolean {
        val flag = File(filesDir, FLAG_FILE)
        if (!flag.exists()) return false
        flag.delete()
        return true
    }
}
