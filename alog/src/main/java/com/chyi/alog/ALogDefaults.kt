package com.chyi.alog

/** 落盘与刷盘相关的默认值。可通过 [com.chyi.alog.printer.file.FilePrinter.Builder] 覆盖部分项。 */
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
    /** 同步 flush 最长等待秒数。 */
    const val FLUSH_WAIT_SECONDS = 60L
    /** mmap 缓存目录名，相对 `filesDir`。 */
    const val CACHE_DIR_NAME = "alog-cache"
    /**
     * mmap 异步队列容量（2 的幂）。需能吸收主线程 1 万条约 200B 的 burst，
     * 由后台线程排空；满时才丢弃。
     */
    const val MMAP_QUEUE_CAPACITY = 16384

    /**
     * Release 默认不得注入 [com.chyi.alog.printer.AndroidPrinter]（无 ALog Logcat）。
     * Debug 默认可同时注册 AndroidPrinter 与 FilePrinter。
     */
    @JvmStatic
    fun includeAndroidPrinter(debug: Boolean): Boolean = debug
}
