package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

/**
 * 脱敏拦截器：掩码大陆手机号、`Bearer` token 以及 `token=` 取值。
 */
class PrivacyInterceptor : Interceptor {
    override fun intercept(item: LogItem): LogItem {
        return item.copy(msg = mask(item.msg))
    }

    companion object {
        private val PHONE = Regex("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)")
        private val BEARER = Regex("(?i)(Bearer\\s+)\\S+")
        private val TOKEN = Regex("(?i)(token\\s*=\\s*)[^\\s&,;]+")

        /** 对单条消息做脱敏，可单独复用。 */
        fun mask(msg: String): String {
            var out = PHONE.replace(msg, "$1****$2")
            out = BEARER.replace(out, "$1***")
            out = TOKEN.replace(out, "$1***")
            return out
        }
    }
}
