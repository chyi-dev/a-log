package com.chyi.alog.upload

data class UploadMeta(
    val appId: String,
    val unionId: String,
    val deviceId: String,
    val appVer: String,
    val buildVer: String,
)

data class UploadResult(val uploadId: String, val truncated: Boolean)

data class UploadConfig(
    val logDir: java.io.File,
    val cacheDir: java.io.File,
    val baseUrl: String,
    val token: String,
    val meta: UploadMeta,
    val maxBytes: Long = UploadDefaults.MAX_UPLOAD_BYTES,
    val chunkSize: Int = UploadDefaults.CHUNK_SIZE,
    val recentDays: Int? = 2,
)
