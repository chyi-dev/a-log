package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

/**
 * 日志拦截器。按 [com.chyi.alog.LogConfiguration.Builder.addInterceptor] 注册顺序执行。
 *
 * 返回改写后的 [LogItem] 继续后续拦截器与打印；返回 `null` 则丢弃该条，不再往后。
 */
fun interface Interceptor {
    fun intercept(item: LogItem): LogItem?
}
