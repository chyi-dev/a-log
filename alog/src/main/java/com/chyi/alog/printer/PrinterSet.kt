package com.chyi.alog.printer

import com.chyi.alog.LogConfiguration
import com.chyi.alog.LogItem

class PrinterSet(private val printers: Array<out Printer>) : Printer, BackpressuredPrinter {
    override fun println(item: LogItem) {
        for (printer in printers) {
            printer.println(item)
        }
    }

    override fun acceptMore(): Boolean {
        if (printers.size == 1) {
            val only = printers[0]
            return if (only is BackpressuredPrinter) only.acceptMore() else true
        }
        return true
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
}
