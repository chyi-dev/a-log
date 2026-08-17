package com.chyi.alog

import com.chyi.alog.interceptor.BlacklistTagsInterceptor
import com.chyi.alog.interceptor.PrivacyInterceptor
import com.chyi.alog.printer.Printer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ALogFacadeTest {
    private val captured = mutableListOf<LogItem>()
    private val printer = Printer { item -> captured.add(item) }

    @After
    fun tearDown() {
        ALog.resetForTest()
        captured.clear()
    }

    @Test(expected = IllegalStateException::class)
    fun notInitializedThrows() {
        ALog.d("nope")
    }

    @Test
    fun interceptorsDropAndMask() {
        val config = LogConfiguration.Builder()
            .tag("ALog")
            .addInterceptor(BlacklistTagsInterceptor(setOf("secret")))
            .addInterceptor(PrivacyInterceptor())
            .build()
        ALog.init(config, printer)
        ALog.d("secret", "should drop")
        ALog.d("phone 13812345678 token=abcdef Bearer xyz")
        assertEquals(1, captured.size)
        assertTrue(captured[0].msg.contains("138****5678"))
        assertTrue(captured[0].msg.contains("token=***"))
        assertTrue(captured[0].msg.contains("Bearer ***"))
        assertEquals(LogType.CODE, captured[0].type)
    }

    @Test
    fun typeOverride() {
        ALog.init(LogConfiguration.Builder().build(), printer)
        ALog.t(LogType.NETWORK).e("Http", "timeout")
        assertEquals(LogType.NETWORK, captured[0].type)
        assertEquals("Http", captured[0].tag)
    }
}
