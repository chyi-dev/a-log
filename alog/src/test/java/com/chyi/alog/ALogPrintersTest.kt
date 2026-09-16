package com.chyi.alog

import com.chyi.alog.printer.ALogPrinters
import com.chyi.alog.printer.AndroidPrinter
import com.chyi.alog.printer.Printer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ALogPrintersTest {
    private val file = Printer { }

    @Test
    fun releaseDoesNotIncludeAndroidPrinter() {
        assertFalse(ALogDefaults.includeAndroidPrinter(debug = false))
        val printers = ALogPrinters.defaults(debug = false, filePrinter = file)
        assertEquals(listOf(file), printers)
        assertTrue(printers.none { it is AndroidPrinter })
    }

    @Test
    fun debugIncludesAndroidPrinterThenFile() {
        assertTrue(ALogDefaults.includeAndroidPrinter(debug = true))
        val printers = ALogPrinters.defaults(debug = true, filePrinter = file)
        assertEquals(2, printers.size)
        assertTrue(printers[0] is AndroidPrinter)
        assertEquals(file, printers[1])
    }

    @Test
    fun releaseWithNoFilePrinterIsEmpty() {
        assertTrue(ALogPrinters.defaults(debug = false, filePrinter = null).isEmpty())
    }
}
