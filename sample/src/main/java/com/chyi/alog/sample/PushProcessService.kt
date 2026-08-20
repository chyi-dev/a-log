package com.chyi.alog.sample

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chyi.alog.ALog
import com.chyi.alog.FlushIntents
import com.chyi.alog.LogType

class PushProcessService : Service() {
    override fun onCreate() {
        super.onCreate()
        ALog.t(LogType.INTERNAL).i("push-service alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WRITE -> {
                repeat(WRITE_COUNT) { i ->
                    ALog.t(LogType.INTERNAL).i("Push", "push-marker-$i")
                }
            }
            FlushIntents.ACTION_FLUSH -> {
                ALog.flush(true)
                ALog.t(LogType.INTERNAL).i("push-service flushed")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WRITE = "com.chyi.alog.sample.PUSH_WRITE"
        const val WRITE_COUNT = 20
    }
}
