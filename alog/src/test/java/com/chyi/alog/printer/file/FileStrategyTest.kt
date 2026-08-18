package com.chyi.alog.printer.file

import com.chyi.alog.store.LogFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStrategyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun customNameGenerator() {
        val dir = tmp.newFolder("n")
        val names = DateFileNameGenerator().nextName(dir, "alog", 1_724_000_000_000L)
        assertTrue(names.startsWith("alog_"))
        assertTrue(names.endsWith(".alog"))
        val fixed = FileNameGenerator { _, _, _ -> "fixed.alog" }
        assertEquals("fixed.alog", fixed.nextName(dir, "alog", 0L))
    }

    @Test
    fun cleanStrategyDeletesOldestWhenOverCapacity() {
        val dir = tmp.newFolder("c")
        val old = File(dir, "old.alog").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val newer = File(dir, "new.alog").apply {
            writeText("newer-content")
            setLastModified(System.currentTimeMillis())
        }
        val deleted = DefaultCleanStrategy().selectForDeletion(
            files = listOf(old, newer),
            retainDays = 7,
            maxTotalBytes = 4,
        )
        assertTrue(deleted.contains(old))
    }

    @Test
    fun managerUsesCustomFileName() {
        val dir = tmp.newFolder("g")
        val mgr = LogFileManager(
            dir = dir,
            namePrefix = "alog",
            maxFileSize = 1024,
            retainDays = 7,
            maxTotalBytes = 64 * 1024,
            nameGenerator = FileNameGenerator { _, _, _ -> "fixed.alog" },
        )
        mgr.append("hello".toByteArray())
        assertTrue(File(dir, "fixed.alog").exists())
    }
}
