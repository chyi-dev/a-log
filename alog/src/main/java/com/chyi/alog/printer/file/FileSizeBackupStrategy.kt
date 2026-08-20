package com.chyi.alog.printer.file

import java.io.File

/** 当前文件存在且大小达到 [maxFileSize] 时轮转。 */
class FileSizeBackupStrategy : BackupStrategy {
    override fun shouldRotate(file: File, maxFileSize: Long): Boolean {
        return file.exists() && file.length() >= maxFileSize
    }
}
