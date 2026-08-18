package com.chyi.alog.printer.file

import com.chyi.alog.ALogDefaults
import com.chyi.alog.store.LogFileManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class SimpleWriter(
    dir: File,
    namePrefix: String,
    maxFileSize: Long = ALogDefaults.MAX_FILE_SIZE,
    retainDays: Int = ALogDefaults.RETAIN_DAYS,
    maxTotalBytes: Long = ALogDefaults.MAX_TOTAL_BYTES,
    nameGenerator: FileNameGenerator? = null,
    backupStrategy: BackupStrategy? = null,
    cleanStrategy: CleanStrategy? = null,
) : Writer {
    private val files = LogFileManager(
        dir = dir,
        namePrefix = namePrefix,
        maxFileSize = maxFileSize,
        retainDays = retainDays,
        maxTotalBytes = maxTotalBytes,
        nameGenerator = nameGenerator ?: DateFileNameGenerator(maxFileSize),
        backupStrategy = backupStrategy ?: FileSizeBackupStrategy(),
        cleanStrategy = cleanStrategy ?: DefaultCleanStrategy(),
    )
    private val lock = Any()

    init {
        dir.mkdirs()
        files.cleanup()
    }

    override fun append(line: String) {
        val payload = if (line.length > ALogDefaults.MAX_LINE_BYTES) {
            line.substring(0, ALogDefaults.MAX_LINE_BYTES)
        } else {
            line
        }
        synchronized(lock) {
            files.append((payload + "\n").toByteArray(StandardCharsets.UTF_8))
        }
    }

    override fun flush(sync: Boolean) {
        if (!sync) return
        synchronized(lock) {
            val file = files.currentFile()
            if (!file.isFile) return
            RandomAccessFile(file, "rw").use { raf ->
                raf.fd.sync()
            }
        }
    }

    override fun close() {
        flush(true)
    }
}
