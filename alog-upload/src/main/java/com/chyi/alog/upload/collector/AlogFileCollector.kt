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
        fromMs: Long? = null,
        toMs: Long? = null,
    ): List<File> {
        val eligible = files.filter {
            it.isFile && it.name.endsWith(".alog") && inRange(it, recentDays, nowMs, fromMs, toMs)
        }.sortedByDescending { it.lastModified() }
        val out = mutableListOf<File>()
        var total = 0L
        for (file in eligible) {
            if (total + file.length() > maxBytes) break
            out.add(file)
            total += file.length()
        }
        return out
    }

    fun truncated(
        files: List<File>,
        selected: List<File>,
        recentDays: Int?,
        nowMs: Long = System.currentTimeMillis(),
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Boolean {
        val eligible = files.filter {
            it.isFile && it.name.endsWith(".alog") && inRange(it, recentDays, nowMs, fromMs, toMs)
        }
        return selected.size < eligible.size
    }

    internal fun inRange(
        file: File,
        recentDays: Int?,
        nowMs: Long,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Boolean {
        if (fromMs != null || toMs != null) {
            return fileOverlaps(file, fromMs, toMs)
        }
        return inWindow(file, recentDays, nowMs)
    }

    internal fun inWindow(file: File, recentDays: Int?, nowMs: Long): Boolean {
        if (recentDays == null) return true
        val cutoff = nowMs - TimeUnit.DAYS.toMillis(recentDays.toLong())
        return fileTimestamp(file) >= cutoff
    }

    fun dateStampOf(name: String): String? = DATE_IN_NAME.find(name)?.value

    private fun fileOverlaps(file: File, fromMs: Long?, toMs: Long?): Boolean {
        val start = fileTimestamp(file)
        val end = start + TimeUnit.DAYS.toMillis(1) - 1
        if (fromMs != null && end < fromMs) return false
        if (toMs != null && start > toMs) return false
        return true
    }

    internal fun fileTimestamp(file: File): Long {
        val datePart = dateStampOf(file.name)
        if (datePart != null) {
            return try {
                SimpleDateFormat("yyyyMMdd", Locale.US).parse(datePart)?.time ?: file.lastModified()
            } catch (_: Throwable) {
                file.lastModified()
            }
        }
        return file.lastModified()
    }

    private val DATE_IN_NAME = Regex("(?<!\\d)\\d{8}(?!\\d)")
}
