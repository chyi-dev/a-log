package com.chyi.alog.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyInterceptorTest {
    @Test
    fun burstPayloadSkipsRegex() {
        val msg = "burst 42 " + "x".repeat(200)
        assertFalse(PrivacyInterceptor.needsMask(msg))
        assertSame(msg, PrivacyInterceptor.mask(msg))
    }

    @Test
    fun stillMasksPhoneAndTokens() {
        val msg = "phone 13812345678 token=abcdef Bearer xyz"
        assertTrue(PrivacyInterceptor.needsMask(msg))
        val masked = PrivacyInterceptor.mask(msg)
        assertTrue(masked.contains("138****5678"))
        assertTrue(masked.contains("token=***"))
        assertTrue(masked.contains("Bearer ***"))
        assertEquals("138****5678", Regex("138\\*\\*\\*\\*5678").find(masked)!!.value)
    }
}
