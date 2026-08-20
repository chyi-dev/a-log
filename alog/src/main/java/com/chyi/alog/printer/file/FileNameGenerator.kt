package com.chyi.alog.printer.file

import java.io.File

/** 生成下一个 `.alog` 文件名。 */
fun interface FileNameGenerator {
    /**
     * @param dir 日志目录
     * @param prefix 文件名前缀
     * @param nowMs 当前时间毫秒
     * @return 不含路径的文件名
     */
    fun nextName(dir: File, prefix: String, nowMs: Long): String
}
