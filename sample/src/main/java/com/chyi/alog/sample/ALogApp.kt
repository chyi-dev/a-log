package com.chyi.alog.sample

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.chyi.alog.ALog
import com.chyi.alog.ALogPaths
import com.chyi.alog.FlushIntents
import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogLevel
import com.chyi.alog.ProcessInfo
import com.chyi.alog.interceptor.PrivacyInterceptor
import com.chyi.alog.printer.ALogPrinters
import com.chyi.alog.printer.file.FilePrinter
import com.chyi.alog.upload.ALogUpload
import com.chyi.alog.upload.UploadConfig
import com.chyi.alog.upload.UploadMeta
import java.io.File
import java.util.UUID

class ALogApp : Application() {
    lateinit var logDir: File
        private set
    lateinit var alogCacheDir: File
        private set
    private var filePrinter: FilePrinter? = null

    override fun onCreate() {
        super.onCreate()
        ProcessInfo.pid = Process.myPid()
        ProcessInfo.processName = currentProcessName()
        logDir = SharedAlogDirs.filesDir()
        alogCacheDir = ALogPaths.cacheRoot(filesDir)
        val namePrefix = ALogPaths.namePrefix(ProcessInfo.processName, packageName)
        logDir.mkdirs()
        alogCacheDir.mkdirs()
        filePrinter = FilePrinter.Builder(logDir)
            .namePrefix(namePrefix)
            .cacheDir(alogCacheDir)
            .writerMode(com.chyi.alog.printer.file.WriterMode.MMAP)
            .onInternal { Log.w("ALogInternal", it) }
            .build()
        initLoggers(console = SampleLogPolicy.consoleOnLaunch(), file = true)
        if (ProcessInfo.processName == packageName) {
            startService(Intent(this, PushProcessService::class.java))
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
            .apply {
                if (BuildConfig.DEBUG) {
                    enableThreadInfo()
                    enableBorder()
                }
            }
            .addInterceptor(PrivacyInterceptor())
            .build()
        val printers = ALogPrinters.defaults(
            debug = SampleLogPolicy.enableAndroidPrinter(console),
            filePrinter = if (file) filePrinter else null,
        )
        ALog.init(config, *printers.toTypedArray())
        ALog.i("ALog ready console=$console file=$file process=${ProcessInfo.processName}")
    }

    fun droppedCount(): Int = filePrinter?.droppedCount() ?: 0

    fun startPushWrite() {
        startService(
            Intent(this, PushProcessService::class.java).setAction(PushProcessService.ACTION_WRITE),
        )
    }

    fun flushPushProcess() {
        startService(
            Intent(this, PushProcessService::class.java).setAction(FlushIntents.ACTION_FLUSH),
        )
    }

    fun describeMultiProcessFiles(): String {
        try {
            ALog.flush(true)
        } catch (_: Throwable) {
        }
        val alogs = ALog.collectAlogFiles(logDir).map { it.name }
        val mms = alogCacheDir.listFiles { f -> f.isFile && f.name.endsWith(".mm") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val hasMainAlog = alogs.any { it.matches(Regex("^alog_\\d{8}_\\d+\\.alog$")) }
        val hasPushAlog = alogs.any { it.matches(Regex("^alog_push_\\d{8}_\\d+\\.alog$")) }
        val hasMainMm = mms.contains("alog.mm")
        val hasPushMm = mms.contains("alog_push.mm")
        return "alog=$alogs mm=$mms mainAlog=$hasMainAlog pushAlog=$hasPushAlog mainMm=$hasMainMm pushMm=$hasPushMm"
    }

    fun enqueueUpload(reason: String) {
        flushPushProcess()
        ALogUpload.enqueue(
            this,
            UploadConfig(
                logDir = logDir,
                cacheDir = alogCacheDir,
                baseUrl = "http://192.168.1.70:8080",
                token = "alog-dev",
                meta = UploadMeta(
                    appId = packageName,
                    unionId = "demo-user",
                    deviceId = deviceId(),
                    appVer = BuildConfig.VERSION_NAME,
                    buildVer = BuildConfig.VERSION_CODE.toString(),
                ),
            ),
            reason,
        )
        ALog.i("upload enqueued reason=$reason")
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
