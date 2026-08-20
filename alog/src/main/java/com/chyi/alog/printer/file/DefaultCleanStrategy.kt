package com.chyi.alog.printer.file

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 默认清理：删除超过 [retainDays] 的文件；
 * 剩余总量仍超过 [maxTotalBytes] 时，按修改时间从旧到新继续删除。
 */
class DefaultCleanStrategy : CleanStrategy {
    override fun selectForDeletion(
        files: List<File>,
        retainDays: Int,
        maxTotalBytes: Long,
    ): List<File> {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retainDays.toLong())
        val expired = files.filter { it.lastModified() < cutoff }
        val remain = files.filter { it !in expired }.sortedBy { it.lastModified() }
        val extra = mutableListOf<File>()
        var total = remain.sumOf { it.length() }
        var i = 0
        while (total > maxTotalBytes && i < remain.size) {
            extra.add(remain[i])
            total -= remain[i].length()
            i++
        }
        return expired + extra
    }
}
