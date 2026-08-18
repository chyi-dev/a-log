package com.chyi.alog.upload

import com.chyi.alog.ALog
import java.io.File

object ProcessLogCollector {
    fun flushAndCollect(alogRoot: File): List<File> {
        try {
            ALog.flush(true)
        } catch (_: Throwable) {
        }
        return collectAlogFiles(alogRoot)
    }

    fun collectAlogFiles(alogRoot: File): List<File> {
        if (!alogRoot.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        alogRoot.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        val dirs = alogRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.let { out.addAll(it) }
        }
        return out.sortedBy { it.absolutePath }
    }
}
