package com.chyi.alog.printer.file

import com.chyi.alog.ALog
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogLevel
import com.chyi.alog.interceptor.PrivacyInterceptor
import com.chyi.alog.store.AlogTestDecode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FilePrinterBurstTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        ALog.resetForTest()
    }

    @Test
    fun tenThousandInfoLinesEnqueueWithoutBlockingCaller() {
        val internals = AtomicInteger(0)
        val dir = tmp.newFolder("fp-burst")
        val printer = FilePrinter.Builder(dir)
            .writerMode(WriterMode.MMAP)
            .pid(1)
            .onInternal { internals.incrementAndGet() }
            .build()
        ALog.init(
            LogConfiguration.Builder()
                .logLevel(LogLevel.INFO)
                .tag("ALog")
                .addInterceptor(PrivacyInterceptor())
                .build(),
            printer,
        )
        val payload = "x".repeat(200)
        val start = System.nanoTime()
        repeat(10_000) { i ->
            ALog.i("burst $i $payload")
        }
        val callerMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        ALog.i("phone 13812345678 should be masked")
        printer.flush(true)
        val decoded = AlogTestDecode.linesInDir(dir)
        val burstLines = decoded.count { it.contains("\"msg\":\"burst ") }
        System.out.println(
            "filePrinterBurst10k callerMs=$callerMs dropped=${printer.droppedCount()} " +
                "internals=${internals.get()} decoded=${decoded.size} burstLines=$burstLines",
        )
        assertTrue(
            "caller must stay fast while delivering 10k lines, callerMs=$callerMs",
            callerMs < 40,
        )
        assertEquals("mmap queue must absorb 10k burst, dropped=${printer.droppedCount()}", 0, printer.droppedCount())
        assertEquals("all burst lines must be written", 10_000, burstLines)
        assertTrue(decoded.any { it.contains("138****5678") })
        assertTrue("INTERNAL should stay quiet on a 10k burst, internals=${internals.get()}", internals.get() <= 2)
    }

    @Test
    fun tenThousandInfoLinesViaBatchApiReturnsImmediately() {
        val internals = AtomicInteger(0)
        val dir = tmp.newFolder("fp-batch")
        val printer = FilePrinter.Builder(dir)
            .writerMode(WriterMode.MMAP)
            .pid(1)
            .onInternal { internals.incrementAndGet() }
            .build()
        ALog.init(
            LogConfiguration.Builder()
                .logLevel(LogLevel.INFO)
                .tag("ALog")
                .addInterceptor(PrivacyInterceptor())
                .build(),
            printer,
        )
        val payload = "x".repeat(200)
        val start = System.nanoTime()
        ALog.i(10_000) { i -> "burst $i $payload" }
        val callerMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        printer.flush(true)
        val decoded = AlogTestDecode.linesInDir(dir)
        val burstLines = decoded.count { it.contains("\"msg\":\"burst ") }
        System.out.println(
            "filePrinterBatch10k callerMs=$callerMs dropped=${printer.droppedCount()} " +
                "internals=${internals.get()} decoded=${decoded.size} burstLines=$burstLines",
        )
        assertTrue(
            "batch submit must return immediately, callerMs=$callerMs",
            callerMs < 40,
        )
        assertEquals("batch uses one queue slot, dropped=${printer.droppedCount()}", 0, printer.droppedCount())
        assertEquals("all burst lines must be written", 10_000, burstLines)
        assertTrue("INTERNAL should stay quiet on a 10k batch, internals=${internals.get()}", internals.get() <= 2)
    }
}
