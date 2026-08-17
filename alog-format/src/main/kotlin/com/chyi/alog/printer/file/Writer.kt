package com.chyi.alog.printer.file

interface Writer {
    fun append(line: String)
    fun flush(sync: Boolean)
    fun close()
}
