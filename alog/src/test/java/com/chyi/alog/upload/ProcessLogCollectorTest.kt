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
    fun collectsOnlyRootAlogFiles() {
        val root = tmp.newFolder("alog")
        File(root, "keep.alog").writeText("a")
        File(root, "skip.txt").writeText("x")
        val nested = File(root, "nested").apply { mkdirs() }
        File(nested, "hidden.alog").writeText("b")
        val files = ProcessLogCollector.collectRootAlogFiles(root)
        assertEquals(1, files.size)
        assertEquals("keep.alog", files[0].name)
        assertTrue(files.none { it.name == "hidden.alog" })
    }
}
