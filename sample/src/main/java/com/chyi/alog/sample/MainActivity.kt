package com.chyi.alog.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
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

        binding.btnSingle.setOnClickListener {
            ALog.d("single debug")
            ALog.t(LogType.NETWORK).i("Http", "GET /ping 200")
            ALog.w("phone 13900001111 should be masked")
            toast("logged")
        }
        binding.btnBurst.setOnClickListener {
            val payload = "x".repeat(200)
            repeat(10_000) { i ->
                ALog.d("burst $i $payload")
            }
            toast("10000 lines queued")
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
        binding.btnCrash.setOnClickListener {
            throw RuntimeException("sample crash for ALog")
        }
    }

    private fun replayBusinessLog() {
        toast("replaying…")
        Thread {
            var n = 0
            try {
                assets.open("log_data.txt").bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        ALog.i(line)
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
