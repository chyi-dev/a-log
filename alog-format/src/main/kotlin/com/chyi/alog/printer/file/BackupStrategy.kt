package com.chyi.alog.printer.file

import java.io.File

fun interface BackupStrategy {
    fun shouldRotate(file: File, maxFileSize: Long): Boolean
}
