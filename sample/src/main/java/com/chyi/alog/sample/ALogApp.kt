package com.chyi.alog.sample

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chyi.alog.ALog
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogLevel
import com.chyi.alog.LogType
import com.chyi.alog.ProcessInfo
import com.chyi.alog.crash.CrashGuard
import com.chyi.alog.interceptor.PrivacyInterceptor
import com.chyi.alog.printer.AndroidPrinter
import com.chyi.alog.printer.file.FilePrinter
import com.chyi.alog.upload.UploadWorker
import java.io.File
import java.util.UUID

class ALogApp : Application() {
    lateinit var logDir: File
        private set
    lateinit var publicKeyPem: String
        private set
    private var filePrinter: FilePrinter? = null

    override fun onCreate() {
        super.onCreate()
        ProcessInfo.pid = Process.myPid()
        ProcessInfo.processName = currentProcessName()
        publicKeyPem = assets.open("alog_public.pem").bufferedReader().readText()
        logDir = File(filesDir, "alog/${ProcessInfo.processName.replace(':', '_')}")
        logDir.mkdirs()
        filePrinter = FilePrinter.Builder(logDir)
            .namePrefix("alog")
            .encrypt(!BuildConfig.DEBUG)
            .publicKeyPem(publicKeyPem)
            .keyId("dev-1")
            .onInternal { Log.w("ALogInternal", it) }
            .build()
        initLoggers(console = BuildConfig.DEBUG, file = true)
        CrashGuard.install(filesDir)
        startService(Intent(this, PushProcessService::class.java))
        if (CrashGuard.consumePendingCrash(filesDir)) {
            ALog.t(LogType.CRASH).w("pending crash flag found, will upload crash window")
            enqueueUpload("crash")
        }
        registerActivityLifecycleCallbacks(FlushLifecycle())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            flushQuietly()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        flushQuietly()
    }

    fun initLoggers(console: Boolean, file: Boolean) {
        val config = LogConfiguration.Builder()
            .logLevel(if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.INFO)
            .tag("ALog")
            .enableThreadInfo()
            .enableStackTrace(1)
            .enableBorder()
            .addInterceptor(PrivacyInterceptor())
            .build()
        val printers = mutableListOf<com.chyi.alog.printer.Printer>()
        if (console) printers.add(AndroidPrinter(autoSeparate = true))
        if (file) {
            printers.add(filePrinter!!)
        }
        ALog.init(config, *printers.toTypedArray())
        ALog.i("ALog ready console=$console file=$file process=${ProcessInfo.processName}")
    }

    fun enqueueUpload(reason: String) {
        val network = if (reason == "manual") NetworkType.CONNECTED else NetworkType.UNMETERED
        val data = workDataOf(
            UploadWorker.KEY_DIR to File(filesDir, "alog").absolutePath,
            UploadWorker.KEY_URL to "http://192.168.1.70:8080",
            UploadWorker.KEY_TOKEN to "alog-dev",
            UploadWorker.KEY_REASON to reason,
            UploadWorker.KEY_APP_ID to packageName,
            UploadWorker.KEY_UNION to "demo-user",
            UploadWorker.KEY_DEVICE to deviceId(),
            UploadWorker.KEY_APP_VER to BuildConfig.VERSION_NAME,
            UploadWorker.KEY_BUILD_VER to BuildConfig.VERSION_CODE.toString(),
        )
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(request)
        ALog.i("upload enqueued reason=$reason network=$network")
    }

    private fun flushQuietly() {
        try {
            ALog.flush(true)
        } catch (_: Throwable) {
        }
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("alog", MODE_PRIVATE)
        val existing = prefs.getString("deviceId", null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("deviceId", created).apply()
        return created
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pid = Process.myPid()
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
    }
}
