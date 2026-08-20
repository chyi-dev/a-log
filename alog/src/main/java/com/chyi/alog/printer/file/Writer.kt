package com.chyi.alog.printer.file

/** 将已 flatten 的日志行写入文件。可通过 [FilePrinter.Builder.writer] 替换默认实现。 */
interface Writer {
    /** 追加一行（不含或已含换行由实现约定；内置实现会自行补 `\n`）。 */
    fun append(line: String)

    /**
     * 刷盘。
     *
     * @param sync `true` 时尽量保证数据落盘
     */
    fun flush(sync: Boolean)

    /** 关闭写入并刷盘。 */
    fun close()

    /** mmap 队列满时丢弃的行数；直写实现默认为 0。 */
    fun droppedCount(): Int = 0
}
