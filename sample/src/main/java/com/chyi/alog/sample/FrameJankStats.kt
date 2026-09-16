package com.chyi.alog.sample

import java.util.Locale

/**
 * Choreographer 时间戳抽样：把相邻 vsync 间隔换算成 jank / 掉帧。
 * 供 sample「主线程 1 万条」burst 验收打印，也可单测。
 */
data class FrameJankStats(
    val frameCount: Int,
    val durationMs: Long,
    val jankFrames: Int,
    val droppedFrames: Int,
    val maxFrameMs: Double,
) {
    fun summary(): String = String.format(
        Locale.US,
        "frames=%d durationMs=%d jank=%d dropped=%d maxFrameMs=%.1f",
        frameCount,
        durationMs,
        jankFrames,
        droppedFrames,
        maxFrameMs,
    )

    companion object {
        const val VSYNC_NS = 16_666_667L

        fun fromFrameTimes(timesNs: List<Long>, vsyncNs: Long = VSYNC_NS): FrameJankStats {
            if (timesNs.size < 2) {
                return FrameJankStats(timesNs.size, 0L, 0, 0, 0.0)
            }
            var jank = 0
            var dropped = 0
            var maxDt = 0L
            for (i in 1 until timesNs.size) {
                val dt = timesNs[i] - timesNs[i - 1]
                if (dt > maxDt) maxDt = dt
                if (dt > vsyncNs) {
                    jank++
                    dropped += (dt / vsyncNs - 1).toInt().coerceAtLeast(0)
                }
            }
            val durationMs = (timesNs.last() - timesNs.first()) / 1_000_000
            return FrameJankStats(
                frameCount = timesNs.size,
                durationMs = durationMs,
                jankFrames = jank,
                droppedFrames = dropped,
                maxFrameMs = maxDt / 1_000_000.0,
            )
        }
    }
}
