package com.chyi.alog

import com.chyi.alog.store.MmapLogWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrepareForUploadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun collectsProcessSubdirAndRootAlogFiles() {
        val root = tmp.newFolder("alog")
        File(root, "legacy.alog").writeText("root")
        File(root, "skip.txt").writeText("x")
        val proc = File(root, "com.chyi.alog.sample").apply { mkdirs() }
        File(proc, "keep.alog").writeText("a")
        File(proc, "alog.mm").writeText("not-alog")
        val deeper = File(proc, "nested").apply { mkdirs() }
        File(deeper, "hidden.alog").writeText("b")
        val files = ALog.collectAlogFiles(root)
        val names = files.map { it.name }.toSet()
        assertEquals(2, files.size)
        assertTrue(names.contains("legacy.alog"))
        assertTrue(names.contains("keep.alog"))
        assertTrue(files.none { it.name == "hidden.alog" || it.name.endsWith(".mm") })
    }

    @Test
    fun recoversDeadProcessMmap() {
        val logRoot = tmp.newFolder("log")
        val cacheRoot = tmp.newFolder("cache")
        val writer = MmapLogWriter(logRoot, "alog", pid = 99999, cacheDir = cacheRoot)
        writer.append("{\"msg\":\"dead-proc\"}")
        writer.awaitQueuedForTest()
        writer.abandonWithoutSealForTest()
        MmapLogWriter.recoverOrphans(logRoot, cacheRoot, livePids = emptySet(), currentPid = 1)
        val files = ALog.collectAlogFiles(logRoot)
        assertTrue(files.any { it.length() > 0 })
    }

    @Test
    fun skipsLivePidDirectory() {
        val logRoot = tmp.newFolder("log")
        val cacheRoot = tmp.newFolder("cache")
        val writer = MmapLogWriter(logRoot, "alog", pid = 42, cacheDir = cacheRoot)
        writer.append("{\"msg\":\"live\"}")
        writer.awaitQueuedForTest()
        val before = logRoot.listFiles { f -> f.name.endsWith(".alog") }?.size ?: 0
        MmapLogWriter.recoverOrphans(logRoot, cacheRoot, livePids = setOf(42), currentPid = 1)
        val after = logRoot.listFiles { f -> f.name.endsWith(".alog") }?.size ?: 0
        assertEquals(before, after)
        writer.close()
    }

    @Test
    fun prepareForUploadWithoutInitDoesNotThrow() {
        ALog.resetForTest()
        val logRoot = tmp.newFolder("log")
        val cacheRoot = tmp.newFolder("cache")
        File(logRoot, "a.alog").writeText("x")
        val files = ALog.prepareForUpload(logRoot, cacheRoot, emptySet(), 1)
        assertEquals(1, files.size)
    }
}
