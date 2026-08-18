package com.chyi.alog.printer.file

interface Flattener {
    fun flatten(item: com.chyi.alog.LogItem): String
}
