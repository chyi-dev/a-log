package com.chyi.alog.store

import com.chyi.alog.ALogDefaults
import com.chyi.alog.printer.file.Writer
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * mmap 落盘实现，由 [com.chyi.alog.printer.file.FilePrinter] 在 [com.chyi.alog.printer.file.WriterMode.MMAP] 下创建。
 * 一般无需直接实例化。
 */
class MmapLogWriter(
    dir: File,
    namePrefix: String,
    maxFileSize: Long = ALogDefaults.MAX_FILE_SIZE,
    retainDays: Int = ALogDefaults.RETAIN_DAYS,
    maxTotalBytes: Long = ALogDefaults.MAX_TOTAL_BYTES,
    private val mmapSize: Int = ALogDefaults.MMAP_SIZE,
    private val onInternal: ((String) -> Unit)? = null,
    fileManager: LogFileManager? = null,
    nameGenerator: com.chyi.alog.printer.file.FileNameGenerator? = null,
    backupStrategy: com.chyi.alog.printer.file.BackupStrategy? = null,
    cleanStrategy: com.chyi.alog.printer.file.CleanStrategy? = null,
    private val pid: Int = 0,
    private val skipIfLocked: Boolean = false,
    private val flushWaitSeconds: Long = ALogDefaults.FLUSH_WAIT_SECONDS,
    cacheDir: File? = null,
) : Writer {
    private val logDir = dir
    private val resolvedCacheDir = cacheDir ?: dir
    private val files = fileManager ?: run {
        val resolvedBackup = com.chyi.alog.printer.file.FilePrinterDefaults.resolveBackup(backupStrategy)
        LogFileManager(
            dir = dir,
            namePrefix = namePrefix,
            maxFileSize = maxFileSize,
            retainDays = retainDays,
            maxTotalBytes = maxTotalBytes,
            nameGenerator = com.chyi.alog.printer.file.FilePrinterDefaults.resolveNameGenerator(
                nameGenerator,
                resolvedBackup,
                maxFileSize,
            ),
            backupStrategy = resolvedBackup,
            cleanStrategy = cleanStrategy ?: com.chyi.alog.printer.file.DefaultCleanStrategy(),
        )
    }
    private val mmapFile = File(resolvedCacheDir, "$namePrefix.mm")
    private val queue = LinkedBlockingQueue<Cmd>(1024)
    private val running = AtomicBoolean(true)
    private val dropped = AtomicInteger(0)
    private val worker = Thread({ loop() }, "alog-store").apply { isDaemon = true }

    private var channel: FileChannel? = null
    private var mapped: MappedByteBuffer? = null
    private var heapFallback: ByteBuffer? = null
    private var fileLock: java.nio.channels.FileLock? = null
    private val skippedLock = AtomicBoolean(false)
    private var seq = 0

    init {
        dir.mkdirs()
        resolvedCacheDir.mkdirs()
        worker.start()
        val opened = CountDownLatch(1)
        queue.put(Cmd.Init(opened))
        opened.await(5, TimeUnit.SECONDS)
    }

    override fun append(line: String) {
        if (!running.get()) return
        val payload = if (line.length > ALogDefaults.MAX_LINE_BYTES) {
            onInternal?.invoke("line truncated to ${ALogDefaults.MAX_LINE_BYTES}")
            line.substring(0, ALogDefaults.MAX_LINE_BYTES)
        } else {
            line
        }
        if (!queue.offer(Cmd.Append(payload))) {
            dropped.incrementAndGet()
            onInternal?.invoke("queue full, dropped=${dropped.get()}")
        }
    }

    override fun flush(sync: Boolean) {
        if (sync) {
            val latch = CountDownLatch(1)
            queue.put(Cmd.Flush(latch))
            latch.await(flushWaitSeconds, TimeUnit.SECONDS)
        } else {
            queue.offer(Cmd.Flush(null))
        }
    }

    override fun close() {
        running.set(false)
        val latch = CountDownLatch(1)
        queue.offer(Cmd.Close(latch))
        latch.await(flushWaitSeconds, TimeUnit.SECONDS)
        worker.join(1000)
    }

    override fun droppedCount(): Int = dropped.get()

    fun usedBytes(): Int {
        val buf = mapped ?: heapFallback ?: return 0
        return used(buf)
    }

    fun unsealedLineCount(): Int {
        val used = usedBytes()
        if (used <= 0) return 0
        val buf = mapped ?: heapFallback ?: return 0
        val body = ByteArray(used)
        val start = ALogDefaults.MMAP_HEADER
        for (i in 0 until used) {
            body[i] = buf.get(start + i)
        }
        return String(body, StandardCharsets.UTF_8).split('\n').count { it.isNotBlank() }
    }

    fun skippedLock(): Boolean = skippedLock.get()

    fun abandonWithoutSealForTest() {
        running.set(false)
        val latch = CountDownLatch(1)
        queue.put(Cmd.Abandon(latch))
        latch.await(flushWaitSeconds, TimeUnit.SECONDS)
        worker.join(1000)
    }

    fun awaitQueuedForTest() {
        val latch = CountDownLatch(1)
        queue.put(Cmd.Barrier(latch))
        latch.await(flushWaitSeconds, TimeUnit.SECONDS)
    }

    private fun loop() {
        while (true) {
            val cmd = queue.poll(200, TimeUnit.MILLISECONDS)
            when (cmd) {
                is Cmd.Init -> {
                    openBuffer()
                    if (!skippedLock.get()) {
                        recover()
                        files.cleanup()
                    }
                    cmd.done.countDown()
                }
                is Cmd.Append -> {
                    if (!skippedLock.get()) writeLine(cmd.line)
                }
                is Cmd.Flush -> {
                    if (!skippedLock.get()) seal(forceMapped = true)
                    cmd.done?.countDown()
                }
                is Cmd.Close -> {
                    if (!skippedLock.get()) seal(forceMapped = true)
                    closeBuffer()
                    cmd.done.countDown()
                    return
                }
                is Cmd.Abandon -> {
                    force()
                    closeBuffer()
                    cmd.done.countDown()
                    return
                }
                is Cmd.Barrier -> cmd.done.countDown()
                null -> Unit
            }
        }
    }

    private fun openBuffer() {
        try {
            if (!mmapFile.exists()) {
                mmapFile.parentFile.mkdirs()
                mmapFile.writeBytes(ByteArray(mmapSize))
            } else if (mmapFile.length() != mmapSize.toLong()) {
                mmapFile.writeBytes(ByteArray(mmapSize))
            }
            val raf = java.io.RandomAccessFile(mmapFile, "rw")
            channel = raf.channel
            fileLock = try {
                channel!!.tryLock()
            } catch (_: Throwable) {
                null
            }
            if (fileLock == null) {
                onInternal?.invoke("mmap locked by another process")
                if (skipIfLocked) {
                    skippedLock.set(true)
                    try {
                        channel?.close()
                    } catch (_: Throwable) {
                    }
                    channel = null
                    return
                }
            }
            mapped = channel!!.map(FileChannel.MapMode.READ_WRITE, 0, mmapSize.toLong())
            if (pid > 0) {
                File(resolvedCacheDir, "${mmapFile.nameWithoutExtension}.pid").writeText(pid.toString())
            }
        } catch (t: Throwable) {
            onInternal?.invoke("mmap failed, heap fallback: ${t.message}")
            heapFallback = ByteBuffer.allocate(mmapSize)
            channel = null
            mapped = null
        }
        val buf = buffer()
        if (!hasMagic(buf)) {
            resetHeader(buf, 0L)
        }
    }

    private fun recover() {
        val buf = buffer()
        val used = used(buf)
        if (used > 0) {
            seal(forceMapped = true)
        }
    }

    private fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        val buf = buffer()
        val used = used(buf)
        val bodyStart = ALogDefaults.MMAP_HEADER
        val bodyCap = bodyCapacity()
        if (used + bytes.size > bodyCap) {
            seal(forceMapped = false)
        }
        val after = used(buf)
        if (after + bytes.size > bodyCap) {
            dropped.incrementAndGet()
            onInternal?.invoke("line larger than mmap body, dropped")
            return
        }
        if (after == 0) {
            putLong(buf, 12, System.currentTimeMillis())
        }
        val pos = bodyStart + after
        for (i in bytes.indices) {
            buf.put(pos + i, bytes[i])
        }
        putInt(buf, 8, after + bytes.size)
        maybeSeal()
    }

    private fun maybeSeal() {
        val used = used(buffer())
        val bodyCap = mmapSize - ALogDefaults.MMAP_HEADER
        if (used >= ALogDefaults.PLAINTEXT_SEAL_BYTES || used * 3 >= bodyCap) {
            seal(forceMapped = false)
        }
    }

    private fun seal(forceMapped: Boolean) {
        val buf = buffer()
        val used = used(buf)
        if (used <= 0) {
            if (forceMapped) force()
            return
        }
        val body = ByteArray(used)
        val start = ALogDefaults.MMAP_HEADER
        for (i in 0 until used) {
            body[i] = buf.get(start + i)
        }
        val effectiveUsed = body.indexOf(0).let { if (it >= 0) it else body.size }
        if (effectiveUsed <= 0) {
            resetHeader(buf, 0L, clearBytes = used)
            if (forceMapped) force()
            return
        }
        val sealedBody = if (effectiveUsed == body.size) body else body.copyOf(effectiveUsed)
        val firstTs = getLong(buf, 12)
        try {
            ensureHeader()
            val payload = BlockCodec.deflate(sealedBody)
            val block = BlockCodec.encode(seq++, firstTs, payload, compressed = true)
            files.append(block)
        } catch (t: Throwable) {
            dropped.incrementAndGet()
            onInternal?.invoke("disk write failed, drop block: ${t.message}")
        }
        resetHeader(buf, 0L, clearBytes = used)
        if (forceMapped) force()
    }

    private fun ensureHeader() {
        if (!files.needsHeader()) return
        files.currentFile()
        files.append(FileHeader().toBytes())
        files.markHeaderWritten()
    }

    private fun resetHeader(buf: ByteBuffer, firstTs: Long, clearBytes: Int = 0) {
        if (clearBytes > 0) {
            val bodyStart = ALogDefaults.MMAP_HEADER
            val clear = clearBytes.coerceAtMost(bodyCapacity())
            for (i in 0 until clear) {
                buf.put(bodyStart + i, 0)
            }
        }
        buf.put(0, 'A'.code.toByte())
        buf.put(1, 'L'.code.toByte())
        buf.put(2, 'M'.code.toByte())
        buf.put(3, 'M'.code.toByte())
        buf.put(4, 1)
        buf.put(5, 0)
        buf.put(6, 0)
        buf.put(7, 0)
        putInt(buf, 8, 0)
        putLong(buf, 12, firstTs)
    }

    private fun hasMagic(buf: ByteBuffer): Boolean {
        return buf.get(0) == 'A'.code.toByte() &&
            buf.get(1) == 'L'.code.toByte() &&
            buf.get(2) == 'M'.code.toByte() &&
            buf.get(3) == 'M'.code.toByte()
    }

    private fun used(buf: ByteBuffer): Int = getInt(buf, 8).coerceAtLeast(0).coerceAtMost(bodyCapacity())

    private fun bodyCapacity(): Int = mmapSize - ALogDefaults.MMAP_HEADER

    private fun buffer(): ByteBuffer = mapped ?: heapFallback!!

    private fun force() {
        try {
            mapped?.force()
        } catch (_: Throwable) {
        }
    }

    private fun closeBuffer() {
        force()
        unmap(mapped)
        mapped = null
        try {
            channel?.close()
        } catch (_: Throwable) {
        }
        channel = null
        heapFallback = null
        try {
            fileLock?.release()
        } catch (_: Throwable) {
        }
        fileLock = null
    }

    private fun unmap(buffer: MappedByteBuffer?) {
        if (buffer == null) return
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val invokeCleaner = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer::class.java)
            invokeCleaner.invoke(unsafe, buffer)
        } catch (_: Throwable) {
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer) ?: return
                cleaner.javaClass.getMethod("clean").invoke(cleaner)
            } catch (_: Throwable) {
            }
        }
    }

    private fun putInt(buf: ByteBuffer, index: Int, value: Int) {
        buf.put(index, (value ushr 24).toByte())
        buf.put(index + 1, (value ushr 16).toByte())
        buf.put(index + 2, (value ushr 8).toByte())
        buf.put(index + 3, value.toByte())
    }

    private fun getInt(buf: ByteBuffer, index: Int): Int {
        return ((buf.get(index).toInt() and 0xFF) shl 24) or
            ((buf.get(index + 1).toInt() and 0xFF) shl 16) or
            ((buf.get(index + 2).toInt() and 0xFF) shl 8) or
            (buf.get(index + 3).toInt() and 0xFF)
    }

    private fun putLong(buf: ByteBuffer, index: Int, value: Long) {
        for (i in 0 until 8) {
            buf.put(index + i, (value ushr ((7 - i) * 8)).toByte())
        }
    }

    private fun getLong(buf: ByteBuffer, index: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf.get(index + i).toLong() and 0xFF)
        }
        return v
    }

    private sealed class Cmd {
        class Init(val done: CountDownLatch) : Cmd()
        class Append(val line: String) : Cmd()
        class Flush(val done: CountDownLatch?) : Cmd()
        class Close(val done: CountDownLatch) : Cmd()
        class Abandon(val done: CountDownLatch) : Cmd()
        class Barrier(val done: CountDownLatch) : Cmd()
    }

    companion object {
        fun recoverOrphans(logRoot: File, cacheRoot: File, livePids: Set<Int>, currentPid: Int = 0) {
            if (!cacheRoot.isDirectory) return
            val mmFiles = cacheRoot.listFiles { f -> f.isFile && f.name.endsWith(".mm") } ?: return
            for (mm in mmFiles) {
                val prefix = mm.nameWithoutExtension
                val pidFile = File(cacheRoot, "$prefix.pid")
                val pid = readPidFile(pidFile)
                if (pid != null && (pid == currentPid || pid in livePids)) continue
                var writer: MmapLogWriter? = null
                try {
                    writer = MmapLogWriter(
                        dir = logRoot,
                        namePrefix = prefix,
                        skipIfLocked = true,
                        cacheDir = cacheRoot,
                    )
                    if (writer.skippedLock()) continue
                    writer.flush(true)
                } catch (_: Throwable) {
                } finally {
                    try {
                        writer?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        private fun readPidFile(file: File): Int? {
            if (!file.isFile) return null
            return file.readText().trim().toIntOrNull()
        }
    }
}
