package com.chyi.alog

import java.io.File

object ALogPaths {
    fun cacheRoot(filesDir: File): File = File(filesDir, ALogDefaults.CACHE_DIR_NAME)

    fun namePrefix(processName: String, packageName: String): String {
        return if (processName == packageName) {
            ALogDefaults.NAME_PREFIX
        } else {
            "${ALogDefaults.NAME_PREFIX}_${sanitize(processName)}"
        }
    }

    fun sanitize(processName: String): String {
        val suffix = if (processName.contains(':')) {
            processName.substringAfter(':')
        } else {
            processName
        }
        return suffix.replace(':', '_').replace('/', '_')
    }
}
