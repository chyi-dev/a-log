package com.chyi.alog.sample.xlog

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PushProcessService : Service() {
    override fun onCreate() {
        super.onCreate()
        XLogFacade.i("Push", "push-service alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WRITE -> {
                repeat(WRITE_COUNT) { i ->
                    XLogFacade.i("Push", "push-marker-$i")
                }
            }
            ACTION_FLUSH -> {
                XLogFacade.flush(true)
                XLogFacade.i("Push", "push-service flushed")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WRITE = "com.chyi.alog.sample.xlog.PUSH_WRITE"
        const val ACTION_FLUSH = "com.chyi.alog.sample.xlog.PUSH_FLUSH"
        const val WRITE_COUNT = 20
    }
}
