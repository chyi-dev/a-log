package com.chyi.alog.store

import com.chyi.alog.printer.file.BackupStrategy
import com.chyi.alog.printer.file.CleanStrategy
import com.chyi.alog.printer.file.DateFileNameGenerator
import com.chyi.alog.printer.file.DefaultCleanStrategy
import com.chyi.alog.printer.file.FileNameGenerator
import com.chyi.alog.printer.file.FileSizeBackupStrategy
import java.io.File
import java.util.Calendar
import java.util.Locale

open class LogFileManager(
    private val dir: File,
    private val namePrefix: String,
    private val maxFileSize: Long,
    private val retainDays: Int,
    private val maxTotalBytes: Long,
    private val nameGenerator: FileNameGenerator = DateFileNameGenerator(maxFileSize),
    private val backupStrategy: BackupStrategy = FileSizeBackupStrategy(),
    private val cleanStrategy: CleanStrategy = DefaultCleanStrategy(),
) {
    private var current: File? = null
    private var headerWritten = false

    fun currentFile(): File {
        val existing = current
        val now = System.currentTimeMillis()
        if (existing != null && existing.exists() &&
            !backupStrategy.shouldRotate(existing, maxFileSize) &&
            isSameGeneratedName(existing, now)
        ) {
            return existing
        }
        dir.mkdirs()
        val next = File(dir, nameGenerator.nextName(dir, namePrefix, now))
        current = next
        headerWritten = next.exists() && next.length() > 0
        return next
    }

    fun needsHeader(): Boolean = !headerWritten

    fun markHeaderWritten() {
        headerWritten = true
    }

    open fun append(bytes: ByteArray) {
        val file = currentFile()
        file.appendBytes(bytes)
        if (backupStrategy.shouldRotate(file, maxFileSize)) {
            current = null
            headerWritten = false
        }
    }

    fun cleanup() {
        if (!dir.exists()) return
        val pattern = Regex("^" + Regex.escape(namePrefix) + "_\\d{8}_\\d+\\.alog$")
        val files = dir.listFiles { f -> f.isFile && pattern.matches(f.name) }?.toList().orEmpty()
        for (file in cleanStrategy.selectForDeletion(files, retainDays, maxTotalBytes)) {
            file.delete()
        }
    }

    private fun isSameGeneratedName(file: File, nowMs: Long): Boolean {
        val todayPrefix = "${namePrefix}_${dateStamp(nowMs)}_"
        return file.name.startsWith(todayPrefix) && file.name.endsWith(".alog")
    }

    companion object {
        fun dateStamp(ts: Long): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            return String.format(
                Locale.US,
                "%04d%02d%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}
