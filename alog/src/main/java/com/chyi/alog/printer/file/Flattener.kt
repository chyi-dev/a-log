package com.chyi.alog.printer.file

/** 将 [com.chyi.alog.LogItem] 序列化为写入文件的一行文本。 */
interface Flattener {
    /** 序列化为一行文本（通常不含末尾换行）。 */
    fun flatten(item: com.chyi.alog.LogItem): String
}
