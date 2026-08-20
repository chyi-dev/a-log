package com.chyi.alog.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MultiProcessMmapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun concurrentPrefixesDoNotCrossWrite() {
        val logDir = tmp.newFolder("log")
        val cacheDir = tmp.newFolder("cache")
        val main = MmapLogWriter(logDir, "alog", pid = 11, cacheDir = cacheDir)
        val push = MmapLogWriter(logDir, "alog_push", pid = 22, cacheDir = cacheDir)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        Thread {
            start.await()
            repeat(40) { i -> main.append("{\"msg\":\"main-$i\"}") }
            done.countDown()
        }.start()
        Thread {
            start.await()
            repeat(40) { i -> push.append("{\"msg\":\"push-$i\"}") }
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        main.flush(true)
        push.flush(true)
        main.close()
        push.close()

        val mainFiles = logDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("^alog_\\d{8}_\\d+\\.alog$"))
        }.orEmpty()
        val pushFiles = logDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("^alog_push_\\d{8}_\\d+\\.alog$"))
        }.orEmpty()
        assertTrue(mainFiles.isNotEmpty())
        assertTrue(pushFiles.isNotEmpty())

        val mainText = mainFiles.flatMap { AlogTestDecode.linesInFile(it) }.joinToString("")
        val pushText = pushFiles.flatMap { AlogTestDecode.linesInFile(it) }.joinToString("")
        assertTrue(mainText.contains("main-0"))
        assertTrue(pushText.contains("push-0"))
        assertFalse(mainText.contains("push-"))
        assertFalse(pushText.contains("main-"))

        assertTrue(File(cacheDir, "alog.mm").exists())
        assertTrue(File(cacheDir, "alog_push.mm").exists())
        assertEquals("11", File(cacheDir, "alog.pid").readText().trim())
        assertEquals("22", File(cacheDir, "alog_push.pid").readText().trim())
    }
}
