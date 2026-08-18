package com.chyi.alog.printer.file

import com.chyi.alog.ALogDefaults
import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.crypto.CryptoConfig
import com.chyi.alog.crypto.RsaKeyWrap
import com.chyi.alog.printer.Printer
import com.chyi.alog.store.MmapLogWriter
import java.io.File

class FilePrinter private constructor(
    private val writer: Writer,
    private val flattener: Flattener,
) : Printer {

    override fun println(item: LogItem) {
        writer.append(flattener.flatten(item))
        if (item.level >= LogLevel.FATAL) {
            writer.flush(true)
        }
    }

    override fun flush(sync: Boolean) {
        writer.flush(sync)
    }

    class Builder(private val folder: File) {
        private var namePrefix: String = ALogDefaults.NAME_PREFIX
        private var maxFileSize: Long = ALogDefaults.MAX_FILE_SIZE
        private var retainDays: Int = ALogDefaults.RETAIN_DAYS
        private var maxTotalBytes: Long = ALogDefaults.MAX_TOTAL_BYTES
        private var flattener: Flattener = JsonLineFlattener()
        private var writerOverride: Writer? = null
        private var writerMode: WriterMode = WriterMode.MMAP
        private var publicKeyPem: String? = null
        private var keyId: String = "dev-1"
        private var encrypt: Boolean = false
        private var onInternal: ((String) -> Unit)? = null
        private var fileNameGenerator: FileNameGenerator? = null
        private var backupStrategy: BackupStrategy? = null
        private var cleanStrategy: CleanStrategy? = null
        private var pid: Int = 0
        private var cacheDir: File? = null

        fun namePrefix(value: String) = apply { namePrefix = value }
        fun maxFileSize(value: Long) = apply { maxFileSize = value }
        fun retainDays(value: Int) = apply { retainDays = value }
        fun maxTotalBytes(value: Long) = apply { maxTotalBytes = value }
        fun flattener(value: Flattener) = apply { flattener = value }
        fun writer(value: Writer) = apply { writerOverride = value }
        fun writerMode(value: WriterMode) = apply { writerMode = value }
        fun pid(value: Int) = apply { pid = value }
        fun cacheDir(value: File) = apply { cacheDir = value }
        fun publicKeyPem(value: String?) = apply { publicKeyPem = value }
        fun keyId(value: String) = apply { keyId = value }
        fun encrypt(enabled: Boolean) = apply { encrypt = enabled }
        fun onInternal(value: (String) -> Unit) = apply { onInternal = value }
        fun fileNameGenerator(value: FileNameGenerator) = apply { fileNameGenerator = value }
        fun backupStrategy(value: BackupStrategy) = apply { backupStrategy = value }
        fun cleanStrategy(value: CleanStrategy) = apply { cleanStrategy = value }

        fun build(): FilePrinter {
            val crypto = if (encrypt && !publicKeyPem.isNullOrBlank()) {
                CryptoConfig(true, keyId, RsaKeyWrap.parsePublicPem(publicKeyPem!!))
            } else {
                CryptoConfig(false, keyId, null)
            }
            val writer = writerOverride ?: when (writerMode) {
                WriterMode.SIMPLE -> SimpleWriter(
                    dir = folder,
                    namePrefix = namePrefix,
                    maxFileSize = maxFileSize,
                    retainDays = retainDays,
                    maxTotalBytes = maxTotalBytes,
                    nameGenerator = fileNameGenerator,
                    backupStrategy = backupStrategy,
                    cleanStrategy = cleanStrategy,
                )
                WriterMode.MMAP -> MmapLogWriter(
                    dir = folder,
                    namePrefix = namePrefix,
                    maxFileSize = maxFileSize,
                    retainDays = retainDays,
                    maxTotalBytes = maxTotalBytes,
                    crypto = crypto,
                    onInternal = onInternal,
                    nameGenerator = fileNameGenerator,
                    backupStrategy = backupStrategy,
                    cleanStrategy = cleanStrategy,
                    pid = if (pid != 0) pid else android.os.Process.myPid(),
                    cacheDir = cacheDir,
                )
            }
            return FilePrinter(writer, flattener)
        }
    }

    fun droppedCount(): Int = writer.droppedCount()
}
