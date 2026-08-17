package com.chyi.alog.printer.file

import java.io.File

fun interface CleanStrategy {
    fun selectForDeletion(files: List<File>, retainDays: Int, maxTotalBytes: Long): List<File>
}
