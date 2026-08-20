package com.chyi.alog.printer.file

import com.chyi.alog.ALogDefaults
import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.crypto.CryptoConfig
import com.chyi.alog.crypto.RsaKeyWrap
import com.chyi.alog.printer.Printer
import com.chyi.alog.store.MmapLogWriter
import java.io.File

/**
 * 将日志写入 `.alog` 文件。通过 [Builder] 配置目录、轮转、加密与 Writer 实现。
 *
 * FATAL 级别会立即同步刷盘。
 */
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

    /**
     * 构建 [FilePrinter]。
     *
     * @param folder `.alog` 输出目录
     */
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

        /** 文件名前缀，默认 [com.chyi.alog.ALogDefaults.NAME_PREFIX]。多进程请用 [com.chyi.alog.ALogPaths.namePrefix]。 */
        fun namePrefix(value: String) = apply { namePrefix = value }
        /** 单个 `.alog` 最大字节数，默认 8MB。 */
        fun maxFileSize(value: Long) = apply { maxFileSize = value }
        /** 保留天数，默认 7。 */
        fun retainDays(value: Int) = apply { retainDays = value }
        /** 日志总容量上限，默认 64MB。 */
        fun maxTotalBytes(value: Long) = apply { maxTotalBytes = value }
        /** 将 [com.chyi.alog.LogItem] 序列化为行文本，默认 [JsonLineFlattener]。 */
        fun flattener(value: Flattener) = apply { flattener = value }
        /** 覆盖默认 Writer；设置后忽略 [writerMode]。 */
        fun writer(value: Writer) = apply { writerOverride = value }
        /** 落盘模式，默认 [WriterMode.MMAP]。 */
        fun writerMode(value: WriterMode) = apply { writerMode = value }
        /** 当前进程 pid，写入 mmap 锁文件；为 0 时使用 `Process.myPid()`。 */
        fun pid(value: Int) = apply { pid = value }
        /** mmap 缓存目录；不设置时与日志目录相同。 */
        fun cacheDir(value: File) = apply { cacheDir = value }
        /** RSA 公钥 PEM，与 [encrypt] 同时开启时用于封装文件 DEK。 */
        fun publicKeyPem(value: String?) = apply { publicKeyPem = value }
        /** 密钥标识，写入文件头，默认 `"dev-1"`。 */
        fun keyId(value: String) = apply { keyId = value }
        /** 是否加密落盘。需同时提供非空 [publicKeyPem]。 */
        fun encrypt(enabled: Boolean) = apply { encrypt = enabled }
        /** 内部告警回调（如行过长截断），不会写入 `.alog`。 */
        fun onInternal(value: (String) -> Unit) = apply { onInternal = value }
        /** 自定义 `.alog` 文件名生成。 */
        fun fileNameGenerator(value: FileNameGenerator) = apply { fileNameGenerator = value }
        /** 何时轮转到下一个文件。 */
        fun backupStrategy(value: BackupStrategy) = apply { backupStrategy = value }
        /** 过期与超容量清理策略。 */
        fun cleanStrategy(value: CleanStrategy) = apply { cleanStrategy = value }

        /** 构建文件 Printer。未提供公钥时即使 [encrypt] 为 true 也会以明文写入。 */
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

    /** mmap 队列满时丢弃的行数。 */
    fun droppedCount(): Int = writer.droppedCount()
}
