package com.chyi.alog.store

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

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
        assertTrue(header.version == 1)
    }

    @Test
    fun skipIfLockedLeavesOwnerWriterAlone() {
        val dir = tmp.newFolder("lock")
        val owner = MmapLogWriter(dir, "alog", pid = 7)
        owner.append("{\"msg\":\"owner\"}")
        owner.awaitQueuedForTest()
        val other = MmapLogWriter(dir, "alog", skipIfLocked = true)
        assertTrue(other.skippedLock())
        other.close()
        owner.flush(true)
        owner.close()
        val text = AlogTestDecode.linesInDir(dir).joinToString("")
        assertTrue(text.contains("owner"))
    }

    @Test
    fun recoverAfterAbandonKeepsJsonOnly() {
        val dir = tmp.newFolder("json-only")
        val writer = MmapLogWriter(dir, "alog")
        val payload = "x".repeat(1024)
        repeat(80) { i ->
            writer.append("{\"ts\":$i,\"level\":\"I\",\"type\":\"code\",\"tag\":\"T\",\"msg\":\"bulk-$i-$payload\"}")
        }
        writer.awaitQueuedForTest()
        writer.append("{\"ts\":999999,\"level\":\"I\",\"type\":\"code\",\"tag\":\"T\",\"msg\":\"leftover-json\"}")
        writer.awaitQueuedForTest()
        writer.abandonWithoutSealForTest()

        val writer2 = MmapLogWriter(dir, "alog")
        writer2.flush(true)
        writer2.close()

        val lines = decodeAllLines(dir)
        assertTrue(lines.any { it.contains("\"leftover-json\"") })
        assertTrue(lines.all { isValidJsonObjectLine(it) })
    }

    @Test
    fun recoverWithCorruptedUsedDoesNotEmitBinaryLines() {
        val dir = tmp.newFolder("corrupted-used")
        val writer = MmapLogWriter(dir, "alog")
        repeat(4) { i ->
            writer.append("{\"ts\":$i,\"level\":\"I\",\"type\":\"code\",\"tag\":\"T\",\"msg\":\"first-$i\"}")
        }
        writer.flush(true)
        writer.append("{\"ts\":100,\"level\":\"I\",\"type\":\"code\",\"tag\":\"T\",\"msg\":\"tail\"}")
        writer.awaitQueuedForTest()
        writer.abandonWithoutSealForTest()

        val mmapFile = File(dir, "alog.mm")
        forceUsed(mmapFile, 150 * 1024 - 1)

        val writer2 = MmapLogWriter(dir, "alog")
        writer2.flush(true)
        writer2.close()

        val lines = decodeAllLines(dir)
        assertTrue(lines.any { it.contains("\"tail\"") })
        assertTrue(lines.all { isValidJsonObjectLine(it) })

        val alogFiles = dir.listFiles { f -> f.name.endsWith(".alog") }!!.sortedBy { it.name }
        assertTrue(alogFiles.isNotEmpty())
        assertTrue(alogFiles.all { file ->
            val bytes = file.readBytes()
            FileHeader.parse(bytes) != null && hasValidBlockCrc(bytes)
        })
    }

    private fun decodeAllLines(dir: File): List<String> {
        val files = dir.listFiles { f -> f.name.endsWith(".alog") }!!.sortedBy { it.name }
        val all = mutableListOf<String>()
        for (alog in files) {
            val bytes = alog.readBytes()
            val parsed = FileHeader.parse(bytes)
            assertTrue("missing ALGF header in ${alog.name}", parsed != null)
            val (_, start) = parsed!!
            val blocks = BlockCodec.scan(bytes, start).blocks
            for (block in blocks) {
                val text = String(BlockCodec.inflate(block.payload), Charsets.UTF_8)
                text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { all.add(it) }
            }
        }
        return all
    }

    private fun isValidJsonObjectLine(line: String): Boolean {
        return line.startsWith("{") &&
            line.endsWith("}") &&
            line.contains("\"ts\":") &&
            line.contains("\"level\":") &&
            line.contains("\"type\":") &&
            line.contains("\"tag\":") &&
            line.contains("\"msg\":")
    }

    private fun forceUsed(mmapFile: File, used: Int) {
        RandomAccessFile(mmapFile, "rw").use { raf ->
            raf.seek(8)
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(used).array()
            raf.write(bytes)
        }
    }

    private fun hasValidBlockCrc(bytes: ByteArray): Boolean {
        val parsed = FileHeader.parse(bytes) ?: return false
        var i = parsed.second
        while (i <= bytes.size - 4) {
            if (!(bytes[i] == 'A'.code.toByte() &&
                    bytes[i + 1] == 'L'.code.toByte() &&
                    bytes[i + 2] == 'G'.code.toByte() &&
                    bytes[i + 3] == '1'.code.toByte())
            ) {
                i++
                continue
            }
            if (i + BlockCodec.FIXED_HEADER > bytes.size) return false
            val payloadLen = ByteBuffer.wrap(bytes, i + 18, 4).order(ByteOrder.BIG_ENDIAN).int
            if (payloadLen < 0) return false
            val end = i + BlockCodec.FIXED_HEADER + payloadLen + 4
            if (end > bytes.size) return false
            val crcStored = ByteBuffer.wrap(bytes, end - 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val crc = CRC32().apply { update(bytes, i, end - i - 4) }.value.toInt()
            if (crc != crcStored) return false
            i = end
        }
        return true
    }
}
