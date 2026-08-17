package com.chyi.alog

import com.chyi.alog.interceptor.Interceptor

class LogConfiguration internal constructor(
    val logLevel: Int,
    val tag: String,
    val threadInfo: Boolean,
    val stackTraceDepth: Int,
    val border: Boolean,
    val interceptors: List<Interceptor>,
) {
    class Builder {
        private var logLevel: Int = LogLevel.ALL
        private var tag: String = "ALog"
        private var threadInfo: Boolean = false
        private var stackTraceDepth: Int = 0
        private var border: Boolean = false
        private val interceptors = mutableListOf<Interceptor>()

        fun logLevel(level: Int) = apply { logLevel = level }
        fun tag(tag: String) = apply { this.tag = tag }
        fun enableThreadInfo() = apply { threadInfo = true }
        fun enableStackTrace(depth: Int) = apply { stackTraceDepth = depth }
        fun enableBorder() = apply { border = true }
        fun addInterceptor(interceptor: Interceptor) = apply { interceptors.add(interceptor) }

        fun build(): LogConfiguration = LogConfiguration(
            logLevel = logLevel,
            tag = tag,
            threadInfo = threadInfo,
            stackTraceDepth = stackTraceDepth,
            border = border,
            interceptors = interceptors.toList(),
        )
    }
}
