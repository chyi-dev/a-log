package com.chyi.alog.decode

import com.chyi.alog.store.BlockCodec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecodeCommandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun truncatedFileDoesNotCrash() {
        val payload = BlockCodec.deflate("{\"msg\":\"ok\"}\n".toByteArray())
        val block = BlockCodec.encode(0, 1L, payload, true)
        val file = tmp.newFile("t.alog")
        file.writeBytes(com.chyi.alog.store.FileHeader().toBytes() + block.copyOf(block.size / 2))
        val lines = DecodeCommand.decodeFile(file, null)
        assertTrue(lines.isEmpty() || lines.any { it.contains("ok") })
    }
}
