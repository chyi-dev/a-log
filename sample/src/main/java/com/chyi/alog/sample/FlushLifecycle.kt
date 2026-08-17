package com.chyi.alog.sample

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.chyi.alog.ALog

class FlushLifecycle : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {
        ALog.flush(true)
    }
    override fun onActivityStopped(activity: Activity) {
        ALog.flush(true)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
