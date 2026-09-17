package com.chyi.alog.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chyi.alog.upload.scheduler.UploadWorker

object ALogUpload {
    const val UNIQUE_WORK_NAME = "alog-upload"

    @JvmStatic
    fun enqueue(context: Context, config: UploadConfig, reason: String) {
        val network = if (reason == "manual") NetworkType.CONNECTED else NetworkType.UNMETERED
        val data = workDataOf(
            UploadWorker.KEY_DIR to config.logDir.absolutePath,
            UploadWorker.KEY_CACHE_DIR to config.cacheDir.absolutePath,
            UploadWorker.KEY_URL to config.baseUrl,
            UploadWorker.KEY_TOKEN to config.token,
            UploadWorker.KEY_REASON to reason,
            UploadWorker.KEY_APP_ID to config.meta.appId,
            UploadWorker.KEY_UNION to config.meta.unionId,
            UploadWorker.KEY_DEVICE to config.meta.deviceId,
            UploadWorker.KEY_APP_VER to config.meta.appVer,
            UploadWorker.KEY_BUILD_VER to config.meta.buildVer,
            UploadWorker.KEY_RECENT_DAYS to (config.recentDays ?: -1),
            UploadWorker.KEY_MAX_BYTES to config.maxBytes,
            UploadWorker.KEY_CHUNK_SIZE to config.chunkSize,
        )
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(data)
            .addTag(reason)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            request,
        )
    }
}
