package com.chyi.alog.printer

import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem

fun interface Printer {
    fun println(item: LogItem)
    fun flush(sync: Boolean) {}
    fun attach(config: LogConfiguration) {}
}
