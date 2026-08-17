package com.chyi.alog.upload

import com.chyi.alog.ALog
import java.io.File

object ProcessLogCollector {
    fun flushAndCollect(alogRoot: File): List<File> {
        try {
            ALog.flush(true)
        } catch (_: Throwable) {
        }
        return collectRootAlogFiles(alogRoot)
    }

    fun collectRootAlogFiles(alogRoot: File): List<File> {
        if (!alogRoot.isDirectory) return emptyList()
        return alogRoot.listFiles { f -> f.isFile && f.name.endsWith(".alog") }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()
    }
}
