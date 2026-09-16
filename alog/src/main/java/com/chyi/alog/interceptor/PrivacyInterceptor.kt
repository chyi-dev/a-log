package com.chyi.alog.interceptor

import com.chyi.alog.LogItem

/**
 * 脱敏拦截器：掩码大陆手机号、`Bearer` token 以及 `token=` 取值。
 */
class PrivacyInterceptor : Interceptor {
    override fun intercept(item: LogItem): LogItem {
        val masked = mask(item.msg)
        return if (masked === item.msg) item else item.copy(msg = masked)
    }

    companion object {
        private val PHONE = Regex("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)")
        private val BEARER = Regex("(?i)(Bearer\\s+)\\S+")
        private val TOKEN = Regex("(?i)(token\\s*=\\s*)[^\\s&,;]+")

        /** 对单条消息做脱敏，可单独复用。 */
        fun mask(msg: String): String {
            if (!needsMask(msg)) return msg
            var out = PHONE.replace(msg, "$1****$2")
            out = BEARER.replace(out, "$1***")
            out = TOKEN.replace(out, "$1***")
            return out
        }

        /** 无 11 位手机号、无 Bearer/token 字样时跳过正则，避免主线程 burst 扫描。 */
        fun needsMask(msg: String): Boolean {
            var digitRun = 0
            var tokenHint = false
            for (i in 0 until msg.length) {
                val ch = msg[i]
                if (ch in '0'..'9') {
                    digitRun++
                    if (digitRun >= 11) return true
                } else {
                    digitRun = 0
                }
                if (ch == 'B' || ch == 'b' || ch == 't' || ch == 'T') {
                    tokenHint = true
                }
            }
            if (!tokenHint) return false
            return msg.contains("Bearer", ignoreCase = true) || msg.contains("token", ignoreCase = true)
        }
    }
}
