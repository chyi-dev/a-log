package com.chyi.alog.printer.file

import com.chyi.alog.store.LogFileManager
import java.io.File

/**
 * 按天命名、无 seq：`{prefix}_{yyyyMMdd}.alog`。
 * 与 [NeverBackupStrategy] 搭配使用，当天内不因大小拆文件。
 */
class DateOnlyFileNameGenerator : FileNameGenerator {
    override fun nextName(dir: File, prefix: String, nowMs: Long): String {
        return "${prefix}_${LogFileManager.dateStamp(nowMs)}.alog"
    }

    override fun isSameGeneratedName(file: File, prefix: String, nowMs: Long): Boolean {
        return file.name == "${prefix}_${LogFileManager.dateStamp(nowMs)}.alog"
    }

    override fun cleanupPattern(prefix: String): Regex {
        return Regex("^" + Regex.escape(prefix) + "_\\d{8}\\.alog$")
    }
}
