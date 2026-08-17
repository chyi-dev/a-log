package com.chyi.alog.sample

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chyi.alog.ALog
import com.chyi.alog.LogType
import com.chyi.alog.upload.FlushIntents

class PushProcessService : Service() {
    override fun onCreate() {
        super.onCreate()
        ALog.t(LogType.INTERNAL).i("push-service alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FlushIntents.ACTION_FLUSH) {
            ALog.flush(true)
            ALog.t(LogType.INTERNAL).i("push-service flushed")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
