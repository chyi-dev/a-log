package com.chyi.alog

/** 跨进程请求刷盘的广播 action。 */
object FlushIntents {
    /** 收到后应对当前进程调用 [ALog.flush]。 */
    const val ACTION_FLUSH = "com.chyi.alog.FLUSH"
}
