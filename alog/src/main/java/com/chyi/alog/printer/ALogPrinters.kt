package com.chyi.alog.printer

import com.chyi.alog.ALogDefaults

/**
 * 按构建类型解析默认 Printer 列表。
 *
 * Release（[debug] = false）只返回文件通道；Debug 才注入 [AndroidPrinter]。
 */
object ALogPrinters {
    @JvmStatic
    fun defaults(debug: Boolean, filePrinter: Printer?): List<Printer> {
        val out = ArrayList<Printer>(2)
        if (ALogDefaults.includeAndroidPrinter(debug)) {
            out.add(AndroidPrinter())
        }
        if (filePrinter != null) {
            out.add(filePrinter)
        }
        return out
    }
}
