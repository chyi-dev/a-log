package com.chyi.alog.store

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MmapLogWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun recoversUnsealedBodyAfterReopen() {
        val dir = tmp.newFolder("alog")
        val writer = MmapLogWriter(dir, "alog")
        writer.append("{\"msg\":\"before-kill\"}")
        writer.close()

        val writer2 = MmapLogWriter(dir, "alog")
        writer2.append("{\"msg\":\"after\"}")
        writer2.flush(true)
        writer2.close()

        val alog = dir.listFiles { f -> f.name.endsWith(".alog") }!!.first()
        val bytes = alog.readBytes()
        val (header, start) = FileHeader.parse(bytes)!!
        val scan = BlockCodec.scan(bytes, start)
        val texts = scan.blocks.map { String(BlockCodec.inflate(it.payload), Charsets.UTF_8) }
        val all = texts.joinToString("")
        assertTrue(all.contains("before-kill"))
        assertTrue(all.contains("after"))
        assertTrue(header.wrappedDek.isEmpty())
    }
}
