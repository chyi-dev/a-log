package com.chyi.alog.printer

import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem
import com.chyi.alog.printer.file.FilePrinter

class PrinterSet(private val printers: Array<out Printer>) : Printer {
    override fun println(item: LogItem) {
        for (printer in printers) {
            printer.println(item)
        }
    }

    override fun flush(sync: Boolean) {
        for (printer in printers) {
            printer.flush(sync)
        }
    }

    override fun attach(config: LogConfiguration) {
        for (printer in printers) {
            printer.attach(config)
        }
    }

    internal fun singleMmapFilePrinter(): FilePrinter? {
        if (printers.size != 1) return null
        val only = printers[0] as? FilePrinter ?: return null
        return only.takeIf { it.mmapFastPath() }
    }
}
