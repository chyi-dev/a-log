package com.chyi.alog

import java.io.File

/** 日志目录与多进程文件名前缀工具。 */
object ALogPaths {
    /** mmap 缓存根目录：`filesDir/[ALogDefaults.CACHE_DIR_NAME]`。 */
    fun cacheRoot(filesDir: File): File = File(filesDir, ALogDefaults.CACHE_DIR_NAME)

    /**
     * 按进程生成文件名前缀。
     *
     * 主进程使用 [ALogDefaults.NAME_PREFIX]（`alog`）；
     * 其它进程为 `alog_<sanitize(processName)>`，避免多进程写同一文件。
     */
    fun namePrefix(processName: String, packageName: String): String {
        return if (processName == packageName) {
            ALogDefaults.NAME_PREFIX
        } else {
            "${ALogDefaults.NAME_PREFIX}_${sanitize(processName)}"
        }
    }

    /**
     * 将进程名转为文件名安全片段：取 `:` 后的后缀，并把 `:`、`/` 替换为 `_`。
     */
    fun sanitize(processName: String): String {
        val suffix = if (processName.contains(':')) {
            processName.substringAfter(':')
        } else {
            processName
        }
        return suffix.replace(':', '_').replace('/', '_')
    }
}
