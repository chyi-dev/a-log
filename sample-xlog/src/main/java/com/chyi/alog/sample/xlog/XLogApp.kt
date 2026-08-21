package com.chyi.alog.sample.xlog

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chyi.alog.sample.xlog.upload.XlogUploadWorker
import java.io.File
import java.util.UUID

class XLogApp : Application() {
    lateinit var logDir: File
        private set
    lateinit var xlogCacheDir: File
        private set

    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName()
        val isPush = process.endsWith(":push")
        val prefix = if (isPush) "xlog_push" else "xlog"
        logDir = File(filesDir, "xlog")
        xlogCacheDir = File(filesDir, if (isPush) "xlog-cache-push" else "xlog-cache")
        logDir.mkdirs()
        xlogCacheDir.mkdirs()
        XLogFacade.open(logDir, xlogCacheDir, prefix, BuildConfig.DEBUG)
        XLogFacade.reconfigure(console = BuildConfig.DEBUG, file = true, debug = BuildConfig.DEBUG)
        if (!isPush && process == packageName) {
            startService(Intent(this, PushProcessService::class.java))
        }
        registerActivityLifecycleCallbacks(FlushLifecycle())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            XLogFacade.flush(true)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        XLogFacade.flush(true)
    }

    fun initLoggers(console: Boolean, file: Boolean) {
        XLogFacade.reconfigure(console, file, BuildConfig.DEBUG)
    }

    fun startPushWrite() {
        startService(
            Intent(this, PushProcessService::class.java).setAction(PushProcessService.ACTION_WRITE),
        )
    }

    fun flushPushProcess() {
        startService(
            Intent(this, PushProcessService::class.java).setAction(PushProcessService.ACTION_FLUSH),
        )
    }

    fun describeMultiProcessFiles(): String {
        XLogFacade.flush(true)
        val xlogs = logDir.listFiles { f -> f.isFile && f.name.endsWith(".xlog") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val caches = listOf(
            File(filesDir, "xlog-cache"),
            File(filesDir, "xlog-cache-push"),
        ).flatMap { dir ->
            dir.listFiles()?.map { "${dir.name}/${it.name}" }?.sorted().orEmpty()
        }
        val hasMain = xlogs.any { it.startsWith("xlog_") && !it.startsWith("xlog_push_") }
        val hasPush = xlogs.any { it.startsWith("xlog_push_") }
        return "xlog=$xlogs cache=$caches mainXlog=$hasMain pushXlog=$hasPush"
    }

    fun enqueueUpload(reason: String) {
        flushPushProcess()
        XLogFacade.flush(true)
        val network = if (reason == "manual") NetworkType.CONNECTED else NetworkType.UNMETERED
        val data = workDataOf(
            XlogUploadWorker.KEY_DIR to logDir.absolutePath,
            XlogUploadWorker.KEY_URL to "http://192.168.1.70:8081",
            XlogUploadWorker.KEY_TOKEN to "xlog-dev",
            XlogUploadWorker.KEY_REASON to reason,
            XlogUploadWorker.KEY_APP_ID to packageName,
            XlogUploadWorker.KEY_UNION to "demo-user",
            XlogUploadWorker.KEY_DEVICE to deviceId(),
            XlogUploadWorker.KEY_APP_VER to BuildConfig.VERSION_NAME,
            XlogUploadWorker.KEY_BUILD_VER to BuildConfig.VERSION_CODE.toString(),
        )
        val req = OneTimeWorkRequestBuilder<XlogUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(req)
        XLogFacade.i("XLog", "upload enqueued reason=$reason")
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("xlog", MODE_PRIVATE)
        val existing = prefs.getString("deviceId", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("deviceId", created).apply()
        return created
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return getProcessName()
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pid = Process.myPid()
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
    }
}
