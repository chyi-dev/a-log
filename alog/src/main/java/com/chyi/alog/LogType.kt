package com.chyi.alog

/**
 * 日志业务类型。通过 [ALog.t] 指定，默认 [CODE]。
 *
 * 业务自定义类型应从 [BUSINESS_MIN] 起编号。
 */
object LogType {
    /** 代码日志（默认）。 */
    const val CODE = 1
    /** 网络相关。 */
    const val NETWORK = 2
    /** 用户操作 / 行为。 */
    const val ACTION = 3
    /** 框架内部告警。 */
    const val INTERNAL = 4

    /** 业务自定义类型的最小取值。 */
    const val BUSINESS_MIN = 10

    /** 内置类型的可读名称；自定义类型返回 `"t$type"`。 */
    fun nameOf(type: Int): String = when (type) {
        CODE -> "code"
        NETWORK -> "network"
        ACTION -> "action"
        INTERNAL -> "internal"
        else -> "t$type"
    }
}
