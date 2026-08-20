package com.chyi.alog

/** 落盘、加密与刷盘相关的默认值。可通过 [com.chyi.alog.printer.file.FilePrinter.Builder] 覆盖部分项。 */
object ALogDefaults {
    /** mmap 缓存大小，150KB。 */
    const val MMAP_SIZE = 150 * 1024
    /** mmap 文件头占用字节数。 */
    const val MMAP_HEADER = 20
    /** 单条日志最大字节数，超出截断并打 INTERNAL 告警。 */
    const val MAX_LINE_BYTES = 16 * 1024
    /** 单个 `.alog` 文件最大 8MB，按天 + seq 轮转。 */
    const val MAX_FILE_SIZE = 8L * 1024 * 1024
    /** 文件保留天数。 */
    const val RETAIN_DAYS = 7
    /** 日志总容量上限 64MB。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    /** 明文累计约这么多字节后触发刷盘。 */
    const val PLAINTEXT_SEAL_BYTES = 50 * 1024
    /** 日志文件名前缀。 */
    const val NAME_PREFIX = "alog"
    /** `.alog` 格式版本。 */
    const val FORMAT_VERSION = 1
    /** AES-GCM nonce 长度。 */
    const val GCM_NONCE_BYTES = 12
    /** AES-256 密钥长度。 */
    const val AES_KEY_BYTES = 32
    /** 同步 flush 最长等待秒数。 */
    const val FLUSH_WAIT_SECONDS = 60L
    /** mmap 缓存目录名，相对 `filesDir`。 */
    const val CACHE_DIR_NAME = "alog-cache"
}
