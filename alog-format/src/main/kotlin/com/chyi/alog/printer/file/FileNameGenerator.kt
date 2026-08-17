package com.chyi.alog.printer.file

import java.io.File

fun interface FileNameGenerator {
    fun nextName(dir: File, prefix: String, nowMs: Long): String
}
