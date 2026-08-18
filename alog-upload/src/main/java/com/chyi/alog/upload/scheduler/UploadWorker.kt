package com.chyi.alog.upload.scheduler

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.chyi.alog.ALog
import com.chyi.alog.ALogDefaults
import com.chyi.alog.upload.UploadDefaults
import com.chyi.alog.upload.UploadMeta
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
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val livePids = am.runningAppProcesses?.map { it.pid }?.toSet() ?: emptySet()
        val files = ALog.prepareForUpload(root, cacheRoot, livePids, Process.myPid())
        val uploader = LogUploader(
            baseUrl = baseUrl,
            token = token,
            auditDir = File(applicationContext.filesDir, "alog-audit"),
            meta = UploadMeta(
                appId = inputData.getString(KEY_APP_ID) ?: applicationContext.packageName,
                unionId = inputData.getString(KEY_UNION) ?: "anonymous",
                deviceId = inputData.getString(KEY_DEVICE) ?: "unknown",
                appVer = inputData.getString(KEY_APP_VER) ?: "1.0",
                buildVer = inputData.getString(KEY_BUILD_VER) ?: "1",
            ),
            chunkSize = chunkSize,
            maxBytes = maxBytes,
            recentDays = recentDays,
        )
        return try {
            uploader.upload(files, reason)
            if (reason == "fetch") {
                val pending = try {
                    uploader.pendingFetchTaskId(
                        inputData.getString(KEY_UNION) ?: "anonymous",
                        inputData.getString(KEY_DEVICE) ?: "unknown",
                    )
                } catch (_: Throwable) {
                    null
                }
                uploader.ackFetch(
                    pending
                        ?: inputData.getString(KEY_FETCH_TASK)
                        ?: "sample-fetch",
                )
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
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
    }
}
