package com.chyi.alog

import com.chyi.alog.interceptor.Interceptor

/**
 * 日志运行时配置。通过 [Builder] 构造后传给 [ALog.init]。
 *
 * 线程名、调用栈、边框等装饰字段仅用于控制台展示，禁止写入 `.alog` 文件。
 */
class LogConfiguration internal constructor(
    val logLevel: Int,
    val tag: String,
    val threadInfo: Boolean,
    val stackTraceDepth: Int,
    val border: Boolean,
    val interceptors: List<Interceptor>,
) {
    /** 构建 [LogConfiguration]。 */
    class Builder {
        private var logLevel: Int = LogLevel.ALL
        private var tag: String = "ALog"
        private var threadInfo: Boolean = false
        private var stackTraceDepth: Int = 0
        private var border: Boolean = false
        private val interceptors = mutableListOf<Interceptor>()

        /** 低于此级别的日志会被丢弃。取值范围见 [LogLevel.ALL] 到 [LogLevel.NONE]。 */
        fun logLevel(level: Int) = apply { logLevel = level }

        /** 默认 tag，未指定时为 `"ALog"`。 */
        fun tag(tag: String) = apply { this.tag = tag }

        /** 仅 [com.chyi.alog.printer.AndroidPrinter] 展示线程名。 */
        fun enableThreadInfo() = apply { threadInfo = true }

        /**
         * 仅 [com.chyi.alog.printer.AndroidPrinter] 展示调用栈。
         *
         * @param depth 展示的栈帧深度
         */
        fun enableStackTrace(depth: Int) = apply { stackTraceDepth = depth }

        /** 仅 [com.chyi.alog.printer.AndroidPrinter] 画边框。 */
        fun enableBorder() = apply { border = true }

        /**
         * 添加拦截器。按注册顺序执行，任一返回 `null` 则丢弃该条日志。
         *
         * 拦截器可改写 [LogItem]，但装饰字段不应写入 `.alog`。
         */
        fun addInterceptor(interceptor: Interceptor) = apply { interceptors.add(interceptor) }

        /** 生成不可变配置。 */
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
