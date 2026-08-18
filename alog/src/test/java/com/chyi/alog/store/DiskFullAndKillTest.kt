package com.chyi.alog.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DiskFullAndKillTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun diskFullDropsNewBlockKeepsOldFile() {
        val dir = tmp.newFolder("full")
        val old = File(dir, "keep.alog").apply { writeText("keep-me") }
        val failing = object : LogFileManager(dir, "alog", 8L * 1024 * 1024, 7, 64L * 1024 * 1024) {
            override fun append(bytes: ByteArray) {
                throw IOException("ENOSPC")
            }
        }
        val writer = MmapLogWriter(dir, "alog", fileManager = failing)
        writer.append("{\"msg\":\"new\"}")
        writer.flush(true)
        writer.close()
        assertTrue(writer.droppedCount() >= 1)
        assertTrue(old.exists())
        assertEquals("keep-me", old.readText())
    }

    @Test
    fun recoversWithoutClose() {
        val dir = tmp.newFolder("kill")
        val writer = MmapLogWriter(dir, "alog")
        writer.append("{\"msg\":\"before-kill\"}")
        writer.awaitQueuedForTest()
        writer.abandonWithoutSealForTest()

        val writer2 = MmapLogWriter(dir, "alog")
        writer2.flush(true)
        writer2.close()

        val alog = dir.listFiles { f -> f.name.endsWith(".alog") }!!.first()
        val bytes = alog.readBytes()
        val (_, start) = FileHeader.parse(bytes)!!
        val scan = BlockCodec.scan(bytes, start)
        val texts = scan.blocks.map { String(BlockCodec.inflate(it.payload), Charsets.UTF_8) }
        assertTrue(texts.joinToString("").contains("before-kill"))
    }
}
