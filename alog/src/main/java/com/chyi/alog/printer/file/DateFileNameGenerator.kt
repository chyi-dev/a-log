package com.chyi.alog.printer.file

import com.chyi.alog.ALogDefaults
import com.chyi.alog.store.LogFileManager
import java.io.File

/**
 * 默认命名：`{prefix}_{yyyyMMdd}_{seq}.alog`。
 * 当天已有文件未满 [maxFileSize] 时复用该 seq。
 */
class DateFileNameGenerator(
    private val maxFileSize: Long = ALogDefaults.MAX_FILE_SIZE,
) : FileNameGenerator {
    override fun nextName(dir: File, prefix: String, nowMs: Long): String {
        val date = LogFileManager.dateStamp(nowMs)
        var seq = 0
        while (seq < 10_000) {
            val name = "${prefix}_${date}_${seq}.alog"
            val file = File(dir, name)
            if (!file.exists() || file.length() < maxFileSize) {
                return name
            }
            seq++
        }
        return "${prefix}_${date}_$seq.alog"
    }
}
