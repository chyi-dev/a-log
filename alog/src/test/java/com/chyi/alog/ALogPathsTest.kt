package com.chyi.alog

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ALogPathsTest {
    @Test
    fun mainProcessUsesBarePrefix() {
        val pkg = "com.chyi.alog.sample"
        assertEquals("alog", ALogPaths.namePrefix(pkg, pkg))
    }

    @Test
    fun pushProcessUsesSanitizedSuffix() {
        val pkg = "com.chyi.alog.sample"
        assertEquals("alog_push", ALogPaths.namePrefix("$pkg:push", pkg))
    }

    @Test
    fun cacheRootIsAlogCache() {
        val filesDir = File("/data/user/0/com.chyi.alog.sample/files")
        assertEquals(File(filesDir, ALogDefaults.CACHE_DIR_NAME), ALogPaths.cacheRoot(filesDir))
    }
}
