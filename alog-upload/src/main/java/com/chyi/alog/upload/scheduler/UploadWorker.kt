package com.chyi.alog.upload.scheduler

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chyi.alog.ALog
import com.chyi.alog.ALogDefaults
import com.chyi.alog.upload.UploadDefaults
import com.chyi.alog.upload.UploadMeta
import com.chyi.alog.upload.persist.UploadGate
import com.chyi.alog.upload.protocol.LogUploader
import java.io.File

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            setForegroundAsync(foregroundInfo())
        } catch (_: Throwable) {
        }
        val root = File(inputData.getString(KEY_DIR) ?: return Result.failure())
        val cacheRoot = File(
            inputData.getString(KEY_CACHE_DIR)
                ?: File(applicationContext.filesDir, ALogDefaults.CACHE_DIR_NAME).absolutePath,
        )
        val baseUrl = inputData.getString(KEY_URL) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN) ?: "alog-dev"
        val reason = inputData.getString(KEY_REASON) ?: "manual"
        val recentDaysRaw = inputData.getInt(KEY_RECENT_DAYS, 2)
        val recentDays = if (recentDaysRaw < 0) null else recentDaysRaw
        val maxBytes = inputData.getLong(KEY_MAX_BYTES, UploadDefaults.MAX_UPLOAD_BYTES)
        val chunkSize = inputData.getInt(KEY_CHUNK_SIZE, UploadDefaults.CHUNK_SIZE)
        val unionId = inputData.getString(KEY_UNION) ?: "anonymous"
        val deviceId = inputData.getString(KEY_DEVICE) ?: "unknown"
        val auditDir = File(applicationContext.filesDir, "alog-audit")
        val uploader = LogUploader(
            baseUrl = baseUrl,
            token = token,
            auditDir = auditDir,
            meta = UploadMeta(
                appId = inputData.getString(KEY_APP_ID) ?: applicationContext.packageName,
                unionId = unionId,
                deviceId = deviceId,
                appVer = inputData.getString(KEY_APP_VER) ?: "1.0",
                buildVer = inputData.getString(KEY_BUILD_VER) ?: "1",
            ),
            chunkSize = chunkSize,
            maxBytes = maxBytes,
            recentDays = recentDays,
        )
        val gate = UploadGate(auditDir)
        return try {
            gate.withLock {
                if (reason == "fetch") {
                    fetchOnce(uploader, root, cacheRoot, unionId, deviceId)
                } else {
                    val files = prepareFiles(root, cacheRoot)
                    uploader.upload(files, reason)
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            if (reason == "fetch") {
                logFetch(phaseLog(t))
            }
            Result.retry()
        }
    }

    private fun fetchOnce(
        uploader: LogUploader,
        root: File,
        cacheRoot: File,
        unionId: String,
        deviceId: String,
    ): Result {
        return try {
            val files = if (uploader.hasPersistedFetchAck()) {
                emptyList()
            } else {
                prepareFiles(root, cacheRoot)
            }
            val result = uploader.runFetch(files, unionId, deviceId)
            if (result == null) {
                logFetch("fetch skipped: no pending task")
            } else if (result.uploadId.isEmpty()) {
                logFetch("fetch skipped ack: empty upload taskId=${result.fetchTaskId.orEmpty()}")
            } else {
                logFetch("fetch acked taskId=${result.fetchTaskId.orEmpty()} uploadId=${result.uploadId}")
            }
            Result.success()
        } catch (t: Throwable) {
            logFetch(phaseLog(t))
            Result.retry()
        }
    }

    private fun phaseLog(t: Throwable): String {
        val msg = t.message.orEmpty().ifBlank { t.javaClass.simpleName }
        return when {
            msg.startsWith("fetch pending lookup failed") ||
                msg.startsWith("fetch upload failed") ||
                msg.startsWith("fetch ack failed") -> msg
            else -> "fetch failed: $msg"
        }
    }

    private fun logFetch(message: String) {
        Log.i(LOG_TAG, message)
        try {
            ALog.i(message)
        } catch (_: Throwable) {
        }
    }

    private fun prepareFiles(root: File, cacheRoot: File): List<File> {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val livePids = am.runningAppProcesses?.map { it.pid }?.toSet() ?: emptySet()
        return ALog.prepareForUpload(root, cacheRoot, livePids, Process.myPid())
    }

    private fun foregroundInfo(): ForegroundInfo {
        val channelId = "alog-upload"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "ALog upload", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("ALog")
            .setContentText("正在上传日志")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1001, notification)
        }
    }

    companion object {
        const val KEY_DIR = "dir"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_REASON = "reason"
        const val KEY_APP_ID = "appId"
        const val KEY_UNION = "unionId"
        const val KEY_DEVICE = "deviceId"
        const val KEY_APP_VER = "appVer"
        const val KEY_BUILD_VER = "buildVer"
        const val KEY_CACHE_DIR = "cacheDir"
        const val KEY_FETCH_TASK = "fetchTask"
        const val KEY_RECENT_DAYS = "recentDays"
        const val KEY_MAX_BYTES = "maxBytes"
        const val KEY_CHUNK_SIZE = "chunkSize"
        private const val LOG_TAG = "ALogUpload"
    }
}
