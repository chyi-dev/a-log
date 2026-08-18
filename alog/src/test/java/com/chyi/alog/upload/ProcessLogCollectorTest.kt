package com.chyi.alog.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessLogCollectorTest {
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
        val files = ProcessLogCollector.collectAlogFiles(root)
        val names = files.map { it.name }.toSet()
        assertEquals(2, files.size)
        assertTrue(names.contains("legacy.alog"))
        assertTrue(names.contains("keep.alog"))
        assertTrue(files.none { it.name == "hidden.alog" || it.name.endsWith(".mm") })
    }
}
