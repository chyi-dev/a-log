package com.chyi.alog.printer.file

/**
 * 文件落盘实现。
 *
 * [MMAP] 为默认高性能路径（内存映射 + 后台线程）；
 * [SIMPLE] 同步直写文件，便于调试或无法使用 mmap 的场景。
 */
enum class WriterMode {
    MMAP,
    SIMPLE,
}
