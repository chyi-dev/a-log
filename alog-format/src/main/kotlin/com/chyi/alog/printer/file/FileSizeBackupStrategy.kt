package com.chyi.alog.printer.file

import java.io.File

class FileSizeBackupStrategy : BackupStrategy {
    override fun shouldRotate(file: File, maxFileSize: Long): Boolean {
        return file.exists() && file.length() >= maxFileSize
    }
}
