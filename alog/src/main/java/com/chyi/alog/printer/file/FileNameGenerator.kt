package com.chyi.alog.printer.file

import com.chyi.alog.store.LogFileManager
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

    /**
     * 当前文件是否仍属于 [nowMs] 对应的生成规则（例如同一天）。
     * 默认匹配 `{prefix}_{yyyyMMdd}_*.alog`（带 seq 的默认命名）。
     */
    fun isSameGeneratedName(file: File, prefix: String, nowMs: Long): Boolean {
        val todayPrefix = "${prefix}_${LogFileManager.dateStamp(nowMs)}_"
        return file.name.startsWith(todayPrefix) && file.name.endsWith(".alog")
    }

    /** 清理时匹配本前缀日志文件的正则。默认 `{prefix}_{yyyyMMdd}_{seq}.alog`。 */
    fun cleanupPattern(prefix: String): Regex {
        return Regex("^" + Regex.escape(prefix) + "_\\d{8}_\\d+\\.alog$")
    }
}
