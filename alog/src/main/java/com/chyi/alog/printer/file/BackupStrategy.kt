package com.chyi.alog.printer.file

import java.io.File

/** 判断当前文件是否应轮转到下一个。 */
fun interface BackupStrategy {
    fun shouldRotate(file: File, maxFileSize: Long): Boolean
}
