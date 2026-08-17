package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

class BlacklistTagsInterceptor(
    private val tags: Set<String>,
) : Interceptor {
    override fun intercept(item: LogItem): LogItem? {
        if (item.tag in tags) return null
        return item
    }
}
