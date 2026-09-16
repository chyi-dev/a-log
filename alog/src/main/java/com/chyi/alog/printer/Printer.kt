package com.chyi.alog.printer

import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem

/**
 * 日志输出通道。每条经拦截器处理后的 [LogItem] 都会交给全部已注册 Printer。
 *
 * [println] 必须实现；[flush] 与 [attach] 为可选钩子。
 */
fun interface Printer {
    /** 输出一条日志。 */
    fun println(item: LogItem)

    /**
     * 刷盘。
     *
     * @param sync `true` 时阻塞等待完成
     */
    fun flush(sync: Boolean) {}

    /** [com.chyi.alog.ALog.init] 时注入配置，可用于控制台装饰等。 */
    fun attach(config: LogConfiguration) {}
}

/** mmap 等有界队列：满时调用方应跳过分配。 */
interface BackpressuredPrinter {
    fun acceptMore(): Boolean
}
