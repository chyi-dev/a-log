package com.chyi.alog

object LogType {
    const val CODE = 1
    const val NETWORK = 2
    const val ACTION = 3
    const val INTERNAL = 4

    const val BUSINESS_MIN = 10

    fun nameOf(type: Int): String = when (type) {
        CODE -> "code"
        NETWORK -> "network"
        ACTION -> "action"
        INTERNAL -> "internal"
        else -> "t$type"
    }
}
