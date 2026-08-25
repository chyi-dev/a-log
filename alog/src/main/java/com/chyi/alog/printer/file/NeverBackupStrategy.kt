package com.chyi.alog.printer.file

import java.io.File

/** 从不按大小轮转；一天内始终写入同一文件（通常配合 [DateOnlyFileNameGenerator]）。 */
class NeverBackupStrategy : BackupStrategy {
    override fun shouldRotate(file: File, maxFileSize: Long): Boolean = false
}
