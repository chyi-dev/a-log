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

    @Test
    fun cleanupDoesNotDeleteOtherProcessPrefix() {
        val dir = tmp.newFolder("iso")
        val own = File(dir, "alog_20200101_0.alog").apply {
            writeText("main")
            setLastModified(1_000L)
        }
        val other = File(dir, "alog_push_20200101_0.alog").apply {
            writeText("push")
            setLastModified(1_000L)
        }
        val stray = File(dir, "unrelated.alog").apply {
            writeText("stray")
            setLastModified(1_000L)
        }
        val mgr = LogFileManager(
            dir = dir,
            namePrefix = "alog",
            maxFileSize = 1024,
            retainDays = 7,
            maxTotalBytes = 64 * 1024,
        )
        mgr.cleanup()
        assertTrue(!own.exists())
        assertTrue(other.exists())
        assertTrue(stray.exists())
    }

    @Test
    fun neverBackupUsesDateOnlyNameWithoutSeq() {
        val dir = tmp.newFolder("nb")
        val now = System.currentTimeMillis()
        val stamp = LogFileManager.dateStamp(now)
        val mgr = LogFileManager(
            dir = dir,
            namePrefix = "alog",
            maxFileSize = 64,
            retainDays = 7,
            maxTotalBytes = 64 * 1024,
            nameGenerator = DateOnlyFileNameGenerator(),
            backupStrategy = NeverBackupStrategy(),
        )
        repeat(20) {
            mgr.append(ByteArray(32) { 'x'.code.toByte() })
        }
        val alogs = dir.listFiles { f -> f.name.endsWith(".alog") }!!.toList()
        assertEquals(1, alogs.size)
        assertEquals("alog_${stamp}.alog", alogs[0].name)
        assertTrue(alogs[0].length() > 64)
    }

    @Test
    fun dateOnlyGeneratorMatchesSameDayAndCleansWithoutSeq() {
        val dir = tmp.newFolder("do")
        val gen = DateOnlyFileNameGenerator()
        val day1 = 1_724_000_000_000L
        val day2 = day1 + 86_400_000L
        val name1 = gen.nextName(dir, "alog", day1)
        assertTrue(name1.matches(Regex("""alog_\d{8}\.alog""")))
        assertTrue(!name1.contains(Regex("""_\d+\.alog$""").pattern) || name1.matches(Regex("""alog_\d{8}\.alog""")))
        val file = File(dir, name1).apply {
            writeText("day1")
            setLastModified(1_000L)
        }
        assertTrue(gen.isSameGeneratedName(file, "alog", day1))
        assertTrue(!gen.isSameGeneratedName(file, "alog", day2))

        val other = File(dir, "alog_push_${LogFileManager.dateStamp(day1)}.alog").apply {
            writeText("push")
            setLastModified(1_000L)
        }
        val mgr = LogFileManager(
            dir = dir,
            namePrefix = "alog",
            maxFileSize = 1024,
            retainDays = 7,
            maxTotalBytes = 64 * 1024,
            nameGenerator = gen,
            backupStrategy = NeverBackupStrategy(),
        )
        mgr.cleanup()
        assertTrue(!file.exists())
        assertTrue(other.exists())
    }

    @Test
    fun defaultShardingKeepsSeqInName() {
        val dir = tmp.newFolder("seq")
        val name = DateFileNameGenerator(maxFileSize = 1024).nextName(dir, "alog", 1_724_000_000_000L)
        assertTrue(name.matches(Regex("""alog_\d{8}_\d+\.alog""")))
    }
}
