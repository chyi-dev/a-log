package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

/**
 * 按 tag 黑名单丢弃日志。命中则 [intercept] 返回 `null`。
 *
 * @param tags 要过滤的 tag 集合
 */
class BlacklistTagsInterceptor(
    private val tags: Set<String>,
) : Interceptor {
    override fun intercept(item: LogItem): LogItem? {
        if (item.tag in tags) return null
        return item
    }
}
