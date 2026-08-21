package com.chyi.alog.sample.xlog

import android.util.Log as AndroidLog
import com.tencent.mars.xlog.Log
import com.tencent.mars.xlog.Xlog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object XLogFacade {
    private val opened = AtomicBoolean(false)
    @Volatile private var consoleEnabled = true
    @Volatile private var fileEnabled = true
    private var logDir: File? = null
    private var cacheDir: File? = null
    private var namePrefix: String = "xlog"
    private var encrypt: Boolean = false
    private var publicKeyHex: String = ""
    private val phone = Pattern.compile("(?<!\\d)(1\\d{10})(?!\\d)")

    fun open(
        logDir: File,
        cacheDir: File,
        namePrefix: String,
        debug: Boolean,
        encrypt: Boolean,
        publicKeyHex: String,
    ) {
        this.logDir = logDir
        this.cacheDir = cacheDir
        this.namePrefix = namePrefix
        this.encrypt = encrypt
        this.publicKeyHex = publicKeyHex.trim()
        if (encrypt && this.publicKeyHex.isEmpty()) {
            throw IllegalStateException("XLOG_ENCRYPT=true but xlog_public.hex is empty")
        }
        logDir.mkdirs()
        cacheDir.mkdirs()
        if (!opened.getAndSet(true)) {
            System.loadLibrary("c++_shared")
            System.loadLibrary("marsxlog")
        } else {
            try {
                Log.appenderClose()
            } catch (_: Throwable) {
            }
        }
        val level = if (debug) Xlog.LEVEL_DEBUG else Xlog.LEVEL_INFO
        val pubkey = if (encrypt) this.publicKeyHex else ""
        Xlog.open(
            false,
            level,
            Xlog.AppednerModeAsync,
            cacheDir.absolutePath,
            logDir.absolutePath,
            namePrefix,
            "",
        )
        Log.setLogImp(Xlog())
        fileEnabled = true
        setConsole(debug)
        i("XLog", "ready file=$fileEnabled console=$consoleEnabled encrypt=$encrypt prefix=$namePrefix")
    }

    fun reconfigure(console: Boolean, file: Boolean, debug: Boolean) {
        if (file && !fileEnabled) {
            val log = logDir ?: return
            val cache = cacheDir ?: return
            open(log, cache, namePrefix, debug, encrypt, publicKeyHex)
        } else if (!file && fileEnabled) {
            flush(true)
            try {
                Log.appenderClose()
            } catch (_: Throwable) {
            }
            fileEnabled = false
        }
        setConsole(console)
        i("XLog", "reconfigure console=$console file=$file")
    }

    private fun setConsole(open: Boolean) {
        consoleEnabled = open
        try {
            Log.setConsoleLogOpen(open)
        } catch (_: Throwable) {
        }
    }

    fun flush(@Suppress("UNUSED_PARAMETER") sync: Boolean) {
        if (!fileEnabled) return
        try {
            Log.appenderFlush()
            if (sync) {
                Thread.sleep(50)
            }
        } catch (_: Throwable) {
        }
    }

    fun d(tag: String, msg: String) = write(AndroidLog.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = write(AndroidLog.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(AndroidLog.WARN, tag, msg)

    private fun write(priority: Int, tag: String, msg: String) {
        val masked = phone.matcher(msg).replaceAll("1**********")
        if (fileEnabled) {
            when (priority) {
                AndroidLog.DEBUG -> Log.d(tag, masked)
                AndroidLog.WARN -> Log.w(tag, masked)
                else -> Log.i(tag, masked)
            }
        } else if (consoleEnabled) {
            AndroidLog.println(priority, tag, masked)
        }
    }

    fun listXlogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".xlog") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun totalXlogBytes(): Long = listXlogFiles().sumOf { it.length() }
}
