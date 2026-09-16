package com.chyi.alog.printer.file

import com.chyi.alog.ALog
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogLevel
import com.chyi.alog.interceptor.PrivacyInterceptor
import org.junit.After
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
        val printer = FilePrinter.Builder(tmp.newFolder("fp-burst"))
            .writerMode(WriterMode.MMAP)
            .pid(1)
            .onInternal { internals.incrementAndGet() }
            .build()
        ALog.init(
            LogConfiguration.Builder()
                .logLevel(LogLevel.INFO)
                .tag("ALog")
                .enableThreadInfo()
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
        printer.flush(true)
        System.out.println(
            "filePrinterBurst10k callerMs=$callerMs dropped=${printer.droppedCount()} internals=${internals.get()}",
        )
        assertTrue("Release burst must not flatten/regex/Logcat on caller, callerMs=$callerMs", callerMs < 40)
        assertTrue(
            "queue-full INTERNAL must be rate-limited, internals=${internals.get()}",
            internals.get() <= 16,
        )
    }
}
