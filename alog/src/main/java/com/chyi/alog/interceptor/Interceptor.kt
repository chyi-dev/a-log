package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

fun interface Interceptor {
    fun intercept(item: LogItem): LogItem?
}
