package com.chyi.alog.upload

import com.chyi.alog.ALog
import com.chyi.alog.store.MmapLogWriter
import java.io.File

object ProcessLogCollector {
    fun flushAndCollect(
        logRoot: File,
        cacheRoot: File,
        livePids: Set<Int> = emptySet(),
        currentPid: Int = 0,
    ): List<File> {
        try {
            ALog.flush(true)
        } catch (_: Throwable) {
        }
        recoverDeadMmaps(logRoot, cacheRoot, livePids, currentPid)
        return collectAlogFiles(logRoot)
    }

    fun recoverDeadMmaps(logRoot: File, cacheRoot: File, livePids: Set<Int>, currentPid: Int = 0) {
        if (!cacheRoot.isDirectory) return
        val mmFiles = cacheRoot.listFiles { f -> f.isFile && f.name.endsWith(".mm") } ?: return
        for (mm in mmFiles) {
            val prefix = mm.nameWithoutExtension
            val pidFile = File(cacheRoot, "$prefix.pid")
            val pid = readPidFile(pidFile)
            if (pid != null && (pid == currentPid || pid in livePids)) continue
            var writer: MmapLogWriter? = null
            try {
                writer = MmapLogWriter(dir = logRoot, namePrefix = prefix, skipIfLocked = true, cacheDir = cacheRoot)
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

    fun collectAlogFiles(logRoot: File): List<File> {
        if (!logRoot.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        logRoot.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        val dirs = logRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        }
        return out.sortedBy { it.absolutePath }
    }

    private fun readPidFile(file: File): Int? {
        if (!file.isFile) return null
        return file.readText().trim().toIntOrNull()
    }
}
