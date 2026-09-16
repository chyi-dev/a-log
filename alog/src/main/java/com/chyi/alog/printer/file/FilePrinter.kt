package com.chyi.alog.printer.file

import com.chyi.alog.ALogDefaults
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem
import com.chyi.alog.LogLevel
import com.chyi.alog.interceptor.Interceptor
import com.chyi.alog.printer.Printer
import com.chyi.alog.store.MmapLogWriter
import java.io.File

/**
 * 将日志写入 `.alog` 文件。通过 [Builder] 配置目录、轮转、压缩落盘与 Writer 实现。
 *
 * FATAL 级别会立即同步刷盘。
 */
class FilePrinter private constructor(
    private val writer: Writer,
    private val flattener: Flattener,
) : Printer {
    private var interceptors: List<Interceptor> = emptyList()

    internal fun mmapFastPath(): Boolean = writer is MmapLogWriter

    override fun attach(config: LogConfiguration) {
        interceptors = config.interceptors
    }

    /**
     * Release 热路径：调用线程只入队字段，拦截器与 JSON flatten 在 alog-store 执行。
     */
    fun enqueueRaw(
        level: Int,
        type: Int,
        tag: String,
        msg: String,
        ts: Long,
        throwable: Throwable?,
    ) {
        val w = writer
        if (w is MmapLogWriter) {
            w.enqueueRaw(level, type, tag, msg, ts, throwable, interceptors, flattener)
            if (level >= LogLevel.FATAL) {
                w.flush(true)
            }
            return
        }
        println(
            LogItem(level = level, type = type, tag = tag, msg = msg, ts = ts, throwable = throwable),
        )
    }

    /**
     * 一次入队 [count] 条。调用线程只提交一条 batch 任务；文案由 [msgAt] 在 alog-store 生成。
     */
    fun enqueueBatch(
        level: Int,
        type: Int,
        tag: String,
        count: Int,
        msgAt: (Int) -> String,
    ) {
        val w = writer
        if (w is MmapLogWriter) {
            w.enqueueBatch(level, type, tag, count, msgAt, interceptors, flattener)
            if (level >= LogLevel.FATAL) {
                w.flush(true)
            }
            return
        }
        var i = 0
        while (i < count) {
            println(
                LogItem(level = level, type = type, tag = tag, msg = msgAt(i), ts = System.currentTimeMillis()),
            )
            i++
        }
    }

    override fun println(item: LogItem) {
        val w = writer
        if (w is MmapLogWriter) {
            w.enqueue(item, flattener)
        } else {
            w.append(flattener.flatten(item))
        }
        if (item.level >= LogLevel.FATAL) {
            w.flush(true)
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
        /** 内部告警回调（如行过长截断），不会写入 `.alog`。 */
        fun onInternal(value: (String) -> Unit) = apply { onInternal = value }
        /** 自定义 `.alog` 文件名生成。 */
        fun fileNameGenerator(value: FileNameGenerator) = apply { fileNameGenerator = value }
        /** 何时轮转到下一个文件。 */
        fun backupStrategy(value: BackupStrategy) = apply { backupStrategy = value }
        /** 过期与超容量清理策略。 */
        fun cleanStrategy(value: CleanStrategy) = apply { cleanStrategy = value }

        /** 构建文件 Printer。MMAP 模式封块时 deflate 压缩后写入。 */
        fun build(): FilePrinter {
            val resolvedBackup = FilePrinterDefaults.resolveBackup(backupStrategy)
            val resolvedNameGenerator = FilePrinterDefaults.resolveNameGenerator(
                fileNameGenerator,
                resolvedBackup,
                maxFileSize,
            )
            val writer = writerOverride ?: when (writerMode) {
                WriterMode.SIMPLE -> SimpleWriter(
                    dir = folder,
                    namePrefix = namePrefix,
                    maxFileSize = maxFileSize,
                    retainDays = retainDays,
                    maxTotalBytes = maxTotalBytes,
                    nameGenerator = resolvedNameGenerator,
                    backupStrategy = resolvedBackup,
                    cleanStrategy = cleanStrategy,
                )
                WriterMode.MMAP -> MmapLogWriter(
                    dir = folder,
                    namePrefix = namePrefix,
                    maxFileSize = maxFileSize,
                    retainDays = retainDays,
                    maxTotalBytes = maxTotalBytes,
                    onInternal = onInternal,
                    nameGenerator = resolvedNameGenerator,
                    backupStrategy = resolvedBackup,
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
