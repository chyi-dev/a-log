package com.chyi.alog.printer.file

import java.io.File

/** 选择应删除的过期或超容量日志文件。 */
fun interface CleanStrategy {
    /**
     * @param files 当前目录下的候选日志文件
     * @param retainDays 保留天数
     * @param maxTotalBytes 总容量上限
     * @return 应删除的文件
     */
    fun selectForDeletion(files: List<File>, retainDays: Int, maxTotalBytes: Long): List<File>
}
