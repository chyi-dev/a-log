package com.chyi.alog.sample.xlog

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chyi.alog.sample.xlog.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application as XLogApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        binding.btnSingle.setOnClickListener {
            XLogFacade.d("XLog", "single debug")
            XLogFacade.i("Http", "GET /ping 200")
            XLogFacade.w("XLog", "phone 13900001111 should be masked")
            toast("logged")
        }
        binding.btnBurst.setOnClickListener {
            val payload = "x".repeat(200)
            repeat(10_000) { i ->
                XLogFacade.d("XLog", "burst $i $payload")
            }
            toast("10000 lines queued")
        }
        binding.btnFlush.setOnClickListener {
            XLogFacade.flush(true)
            toast("flushed")
        }
        binding.btnConsoleOnly.setOnClickListener {
            app.initLoggers(console = true, file = false)
            toast("console only")
        }
        binding.btnFileOnly.setOnClickListener {
            app.initLoggers(console = false, file = true)
            toast("file only")
        }
        binding.btnBoth.setOnClickListener {
            app.initLoggers(console = true, file = true)
            toast("console + file")
        }
        binding.btnUpload.setOnClickListener {
            app.enqueueUpload("manual")
            toast("upload enqueued")
        }
        binding.btnFetch.setOnClickListener {
            app.enqueueUpload("fetch")
            toast("fetch enqueued")
        }
        binding.btnReplay.setOnClickListener { replayBusinessLog() }
        binding.btnStress.setOnClickListener { stressLimit() }
        binding.btnPushWrite.setOnClickListener {
            app.startPushWrite()
            toast("push write sent")
        }
        binding.btnPushFlush.setOnClickListener {
            app.flushPushProcess()
            toast("push flush sent")
        }
        binding.btnListFiles.setOnClickListener {
            val summary = app.describeMultiProcessFiles()
            XLogFacade.i("XLog", summary)
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        }
    }

    private fun replayBusinessLog() {
        toast("replaying…")
        Thread {
            var n = 0
            try {
                assets.open("log_data.txt").bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val parsed = parseBusinessLogLine(line)
                        if (parsed != null) {
                            val (tag, msg) = parsed
                            XLogFacade.i(tag, msg)
                        } else {
                            XLogFacade.i("XLog", line)
                        }
                        n++
                    }
                }
                XLogFacade.flush(true)
                runOnUiThread { toast("replayed $n lines") }
            } catch (t: Throwable) {
                runOnUiThread { toast("replay failed: ${t.message}") }
            }
        }.start()
    }

    private fun parseBusinessLogLine(line: String): Pair<String, String>? {
        val m = BUSINESS_LOG_LINE.matchEntire(line.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    companion object {
        private val BUSINESS_LOG_LINE = Regex(
            """^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ([^:]+):(.+)$"""
        )
    }

    private fun stressLimit() {
        toast("stress…")
        Thread {
            val payload = "x".repeat(64)
            val threads = 4
            val perThread = 20_000
            val start = System.nanoTime()
            val workers = (1..threads).map {
                Thread {
                    repeat(perThread) { XLogFacade.i("XLog", payload) }
                }.also { it.start() }
            }
            workers.forEach { it.join() }
            val writeMs = (System.nanoTime() - start) / 1_000_000
            val flushStart = System.nanoTime()
            XLogFacade.flush(true)
            val flushMs = (System.nanoTime() - flushStart) / 1_000_000
            val attempted = threads * perThread
            val fileSize = XLogFacade.totalXlogBytes()
            runOnUiThread {
                toast("attempted=$attempted fileSize=$fileSize writeMs=$writeMs flushMs=$flushMs")
            }
        }.start()
    }

    private fun toast(msg: String) {
        XLogFacade.i("XLog", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
