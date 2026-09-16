package com.chyi.alog.sample

import com.chyi.alog.ALogDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameJankStatsTest {
    @Test
    fun steady60FpsIsNotJank() {
        val vsync = FrameJankStats.VSYNC_NS
        val times = (0L..3L).map { it * vsync }
        val stats = FrameJankStats.fromFrameTimes(times)
        assertEquals(4, stats.frameCount)
        assertEquals(0, stats.jankFrames)
        assertEquals(0, stats.droppedFrames)
        assertTrue(stats.maxFrameMs < 17.0)
    }

    @Test
    fun longFrameCountsDroppedVsyncs() {
        val vsync = FrameJankStats.VSYNC_NS
        val stats = FrameJankStats.fromFrameTimes(listOf(0L, 50_000_000L, 66_666_667L))
        assertEquals(1, stats.jankFrames)
        assertEquals((50_000_000L / vsync - 1).toInt(), stats.droppedFrames)
        assertEquals(50.0, stats.maxFrameMs, 0.1)
        assertTrue(stats.summary().contains("dropped="))
    }

    @Test
    fun singleTimestampIsEmpty() {
        val stats = FrameJankStats.fromFrameTimes(listOf(1L))
        assertEquals(0, stats.droppedFrames)
        assertEquals(0L, stats.durationMs)
    }
}

class SampleLogPolicyTest {
    @Test
    fun launchConsoleFollowsBuildType() {
        assertEquals(BuildConfig.DEBUG, SampleLogPolicy.consoleOnLaunch())
        assertEquals(BuildConfig.DEBUG, SampleLogPolicy.allowConsoleToggle())
        assertFalse(SampleLogPolicy.enableAndroidPrinter(consoleRequested = false))
        assertEquals(BuildConfig.DEBUG, SampleLogPolicy.enableAndroidPrinter(consoleRequested = true))
        assertEquals(BuildConfig.DEBUG, ALogDefaults.includeAndroidPrinter(BuildConfig.DEBUG))
    }
}
