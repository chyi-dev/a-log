package com.chyi.alog.upload.collector

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object AlogFileCollector {
    fun select(
        files: List<File>,
        maxBytes: Long,
        recentDays: Int? = 2,
        nowMs: Long = System.currentTimeMillis(),
    ): List<File> {
        val eligible = files.filter { it.isFile && it.name.endsWith(".alog") && inWindow(it, recentDays, nowMs) }
            .sortedByDescending { it.lastModified() }
        val out = mutableListOf<File>()
        var total = 0L
        for (file in eligible) {
            if (total + file.length() > maxBytes) break
            out.add(file)
            total += file.length()
        }
        return out
    }

    fun truncated(files: List<File>, selected: List<File>, recentDays: Int?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val eligible = files.filter { it.isFile && it.name.endsWith(".alog") && inWindow(it, recentDays, nowMs) }
        return selected.size < eligible.size
    }

    internal fun inWindow(file: File, recentDays: Int?, nowMs: Long): Boolean {
        if (recentDays == null) return true
        val cutoff = nowMs - TimeUnit.DAYS.toMillis(recentDays.toLong())
        return fileTimestamp(file) >= cutoff
    }

    private fun fileTimestamp(file: File): Long {
        val datePart = file.name.substringAfter('_', "").substringBefore('_', "")
        if (datePart.length == 8 && datePart.all { it.isDigit() }) {
            return try {
                SimpleDateFormat("yyyyMMdd", Locale.US).parse(datePart)?.time ?: file.lastModified()
            } catch (_: Throwable) {
                file.lastModified()
            }
        }
        return file.lastModified()
    }
}
