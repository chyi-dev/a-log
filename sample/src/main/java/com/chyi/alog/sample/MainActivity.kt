package com.chyi.alog.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chyi.alog.ALog
import com.chyi.alog.LogType
import com.chyi.alog.sample.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application as ALogApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        if (!StorageAccess.hasAccess(this)) {
            StorageAccess.ensureAccess(this)
            toast("请授予存储权限，日志写入 Documents/a-log/files")
        } else {
            app.logDir.mkdirs()
        }

        if (!SampleLogPolicy.allowConsoleToggle()) {
            binding.btnConsoleOnly.isEnabled = false
            binding.btnBoth.isEnabled = false
        }

        binding.btnSingle.setOnClickListener {
            ALog.d("single debug")
            ALog.t(LogType.NETWORK).i("Http", "GET /ping 200")
            ALog.w("phone 13900001111 should be masked")
            toast("logged")
        }
        binding.btnBurst.setOnClickListener {
            runBurstWithFrameSample()
        }
        binding.btnFlush.setOnClickListener {
            ALog.flush(true)
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
        binding.btnReplay.setOnClickListener {
            replayBusinessLog()
        }
        binding.btnStress.setOnClickListener {
            stressLimit()
        }
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
            ALog.i(summary)
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        }
    }

    private fun runBurstWithFrameSample() {
        val choreographer = Choreographer.getInstance()
        val times = mutableListOf<Long>()
        val payload = "x".repeat(200)
        binding.txtBurstResult.text = getString(R.string.burst_sampling)
        choreographer.postFrameCallback { t0 ->
            times.add(t0)
            val start = System.nanoTime()
            ALog.i(10_000) { i -> "burst $i $payload" }
            val writeMs = (System.nanoTime() - start) / 1_000_000
            val mmapDropped = app.droppedCount()
            choreographer.postFrameCallback { t1 ->
                times.add(t1)
                choreographer.postFrameCallback { t2 ->
                    times.add(t2)
                    val stats = FrameJankStats.fromFrameTimes(times)
                    val msg = "burst10k writeMs=$writeMs mmapDropped=$mmapDropped mode=batch ${stats.summary()}"
                    Log.i("ALogBurst", msg)
                    ALog.i(msg)
                    binding.txtBurstResult.text = msg
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
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
                            ALog.t(LogType.BUSINESS_MIN).i(tag, msg)
                        } else {
                            ALog.i(line)
                        }
                        n++
                    }
                }
                ALog.flush(true)
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
                    repeat(perThread) { ALog.i(payload) }
                }.also { it.start() }
            }
            workers.forEach { it.join() }
            val writeMs = (System.nanoTime() - start) / 1_000_000
            val flushStart = System.nanoTime()
            ALog.flush(true)
            val flushMs = (System.nanoTime() - flushStart) / 1_000_000
            val attempted = threads * perThread
            val dropped = app.droppedCount()
            runOnUiThread {
                toast("attempted=$attempted dropped=$dropped writeMs=$writeMs flushMs=$flushMs")
            }
        }.start()
    }

    private fun toast(msg: String) {
        ALog.i(msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
