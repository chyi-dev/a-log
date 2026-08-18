package com.chyi.alog

object ALogDefaults {
    const val MMAP_SIZE = 150 * 1024
    const val MMAP_HEADER = 20
    const val MAX_LINE_BYTES = 16 * 1024
    const val MAX_FILE_SIZE = 8L * 1024 * 1024
    const val RETAIN_DAYS = 7
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val PLAINTEXT_SEAL_BYTES = 50 * 1024
    const val CHUNK_SIZE = 2 * 1024 * 1024
    const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
    const val UPLOAD_RETRIES = 5
    const val NAME_PREFIX = "alog"
    const val FORMAT_VERSION = 1
    const val GCM_NONCE_BYTES = 12
    const val AES_KEY_BYTES = 32
    const val FLUSH_WAIT_SECONDS = 60L
    const val CACHE_DIR_NAME = "alog-cache"
}
