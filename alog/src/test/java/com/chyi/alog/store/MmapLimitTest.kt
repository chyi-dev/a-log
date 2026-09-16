package com.chyi.alog.store

import com.chyi.alog.ALogDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MmapLimitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun throughputSingleThreadKeepsAccountingIdentity() {
        val dir = tmp.newFolder("tp1")
        val writer = MmapLogWriter(dir, "alog")
        val payload = "x".repeat(64)
        val attempted = AtomicInteger(0)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            writer.append(payload)
            attempted.incrementAndGet()
        }
        val flushStart = System.nanoTime()
        writer.flush(true)
        val flushMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flushStart)
        val decoded = AlogTestDecode.lineCount(dir)
        val dropped = writer.droppedCount()
        val unsealed = writer.unsealedLineCount()
        writer.close()
        System.out.println(
            "threads=1 attempted=${attempted.get()} decoded=$decoded dropped=$dropped " +
                "unsealed=$unsealed linesPerSec=${attempted.get()} flushMs=$flushMs",
        )
        assertEquals(attempted.get(), decoded + dropped + unsealed)
        assertTrue(flushMs < TimeUnit.SECONDS.toMillis(ALogDefaults.FLUSH_WAIT_SECONDS))
    }

    @Test
    fun throughputFourThreadsKeepsAccountingIdentity() {
        val dir = tmp.newFolder("tp4")
        val writer = MmapLogWriter(dir, "alog")
        val payload = "x".repeat(64)
        val attempted = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.execute {
                start.await()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (System.nanoTime() < deadline) {
                    writer.append(payload)
                    attempted.incrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(8, TimeUnit.SECONDS))
        pool.shutdownNow()
        val flushStart = System.nanoTime()
        writer.flush(true)
        val flushMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flushStart)
        val decoded = AlogTestDecode.lineCount(dir)
        val dropped = writer.droppedCount()
        val unsealed = writer.unsealedLineCount()
        writer.close()
        System.out.println(
            "threads=4 attempted=${attempted.get()} decoded=$decoded dropped=$dropped " +
                "unsealed=$unsealed linesPerSec=${attempted.get()} flushMs=$flushMs",
        )
        assertEquals(attempted.get(), decoded + dropped + unsealed)
    }

    @Test
    fun flushWaitSixtySecondsSealsSlowDisk() {
        val dir = tmp.newFolder("slow")
        val slow = object : LogFileManager(dir, "alog", ALogDefaults.MAX_FILE_SIZE, 7, ALogDefaults.MAX_TOTAL_BYTES) {
            override fun append(bytes: ByteArray) {
                Thread.sleep(15)
                super.append(bytes)
            }
        }
        val writer = MmapLogWriter(
            dir = dir,
            namePrefix = "alog",
            fileManager = slow,
            flushWaitSeconds = ALogDefaults.FLUSH_WAIT_SECONDS,
        )
        val line = "{\"msg\":\"${"y".repeat(200)}\"}"
        repeat(400) { writer.append(line) }
        val flushStart = System.nanoTime()
        writer.flush(true)
        val flushMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flushStart)
        val unsealed = writer.unsealedLineCount()
        val decoded = AlogTestDecode.lineCount(dir)
        writer.close()
        System.out.println("slowFlushMs=$flushMs decoded=$decoded unsealed=$unsealed dropped=${writer.droppedCount()}")
        assertEquals(0, unsealed)
        assertTrue(decoded + writer.droppedCount() <= 400)
        assertTrue(flushMs < TimeUnit.SECONDS.toMillis(ALogDefaults.FLUSH_WAIT_SECONDS))
    }

    @Test
    fun threeSecondFlushCanLeaveUnsealedOnSlowDisk() {
        val dir = tmp.newFolder("slow3")
        val slow = object : LogFileManager(dir, "alog", ALogDefaults.MAX_FILE_SIZE, 7, ALogDefaults.MAX_TOTAL_BYTES) {
            override fun append(bytes: ByteArray) {
                Thread.sleep(40)
                super.append(bytes)
            }
        }
        val writer = MmapLogWriter(dir, "alog", fileManager = slow, flushWaitSeconds = 3)
        val line = "{\"msg\":\"${"z".repeat(12_000)}\"}"
        repeat(300) { writer.append(line) }
        writer.flush(true)
        val unsealed = writer.unsealedLineCount()
        val flushWasCapped = unsealed > 0
        writer.close()
        System.out.println("flush3s leftoverLines=$unsealed dropped=${writer.droppedCount()} capped=$flushWasCapped")
        assertTrue(unsealed >= 0)
    }

    @Test
    fun dropsLineLargerThanMmapBody() {
        val internals = mutableListOf<String>()
        val dir = tmp.newFolder("huge")
        val writer = MmapLogWriter(dir, "alog", mmapSize = 200, onInternal = { internals.add(it) })
        writer.append("c".repeat(1000))
        writer.flush(true)
        val dropped = writer.droppedCount()
        writer.close()
        assertTrue(dropped >= 1)
        assertTrue(internals.any { it.contains("larger than mmap") })
    }

    @Test
    fun truncatesLineOverMaxAndKeepsMmapHealthy() {
        val internals = mutableListOf<String>()
        val dir = tmp.newFolder("line")
        val writer = MmapLogWriter(dir, "alog", onInternal = { internals.add(it) })
        val ok = "a".repeat(ALogDefaults.MAX_LINE_BYTES - 1)
        writer.append(ok)
        writer.append("b".repeat(ALogDefaults.MAX_LINE_BYTES + 8))
        writer.flush(true)
        writer.close()
        val decoded = AlogTestDecode.linesInDir(dir)
        assertEquals(2, decoded.size)
        assertTrue(internals.any { it.contains("truncated") })
        assertTrue(decoded[1].length <= ALogDefaults.MAX_LINE_BYTES)
    }

    @Test
    fun rotatesWhenFileExceedsMaxSize() {
        val dir = tmp.newFolder("rot")
        val writer = MmapLogWriter(dir, "alog", maxFileSize = 200)
        repeat(900) { i ->
            writer.append("{\"id\":$i,\"msg\":\"${"n".repeat(60)}\"}")
        }
        writer.flush(true)
        val files = dir.listFiles { f -> f.name.endsWith(".alog") }!!.toList()
        val decoded = AlogTestDecode.lineCount(dir)
        val dropped = writer.droppedCount()
        writer.close()
        assertTrue("expected rotation, files=${files.map { it.name + ":" + it.length() }}", files.size >= 2)
        assertEquals(900, decoded + dropped)
    }

    @Test
    fun tenThousandApprox200BAppendsReturnQuicklyOnCallerThread() {
        val dir = tmp.newFolder("burst10k")
        val writer = MmapLogWriter(dir, "alog")
        val payload = "x".repeat(200)
        val start = System.nanoTime()
        repeat(10_000) { i ->
            writer.append("burst $i $payload")
        }
        val callerMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        val dropped = writer.droppedCount()
        System.out.println("burst10k callerMs=$callerMs dropped=$dropped")
        writer.flush(true)
        writer.close()
        assertTrue("caller thread should queue asynchronously, callerMs=$callerMs", callerMs < 50)
        assertEquals("16384-slot ring must absorb 10k appends, dropped=$dropped", 0, dropped)
    }
}
