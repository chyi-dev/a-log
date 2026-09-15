package com.chyi.alog.sample.viewer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.chyi.alog.decode.AlogDecoder
import com.chyi.alog.decode.LegacyTxtFormatter
import com.chyi.alog.decode.gs.GsSerialAnnotator
import com.chyi.alog.sample.viewer.databinding.ActivityTxtViewBinding
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class TxtViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTxtViewBinding
    private lateinit var adapter: LogLineAdapter
    private var originalLines: List<String> = emptyList()
    private var annotatedLines: List<String>? = null
    private var showingAnnotated: Boolean = false
    private var sourceName: String = ""
    private var parsing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTxtViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        val file = File(path)
        sourceName = file.name
        binding.title.text = sourceName
        adapter = LogLineAdapter()
        binding.lineList.layoutManager = LinearLayoutManager(this)
        binding.lineList.adapter = adapter
        adapter.submitList(listOf(getString(R.string.decoding)))
        binding.btnSave.isEnabled = false
        binding.btnParseSerial.isEnabled = false
        binding.btnSave.setOnClickListener { saveTxt() }
        binding.btnParseSerial.setOnClickListener { onParseSerialClicked() }

        Thread {
            try {
                val jsonLines = AlogDecoder.decode(file)
                val txt = LegacyTxtFormatter.toLegacyTxt(jsonLines)
                originalLines = splitLegacyLines(txt)
                runOnUiThread {
                    showLines(originalLines)
                    binding.btnSave.isEnabled = true
                    binding.btnParseSerial.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    adapter.submitList(
                        listOf(getString(R.string.decode_failed, t.message ?: t.javaClass.simpleName)),
                    )
                }
            }
        }.start()
    }

    private fun onParseSerialClicked() {
        if (parsing) return
        if (showingAnnotated) {
            showingAnnotated = false
            showLines(originalLines)
            binding.btnParseSerial.text = getString(R.string.btn_parse_serial)
            return
        }
        val cached = annotatedLines
        if (cached != null) {
            showingAnnotated = true
            showLines(cached)
            binding.btnParseSerial.text = getString(R.string.btn_show_original)
            return
        }
        parsing = true
        binding.btnParseSerial.isEnabled = false
        binding.btnParseSerial.text = getString(R.string.parsing_serial)
        Thread {
            try {
                val annotated = GsSerialAnnotator.annotateLines(originalLines)
                runOnUiThread {
                    annotatedLines = annotated
                    showingAnnotated = true
                    showLines(annotated)
                    binding.btnParseSerial.text = getString(R.string.btn_show_original)
                    binding.btnParseSerial.isEnabled = true
                    parsing = false
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.parse_serial_failed, t.message ?: t.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                    binding.btnParseSerial.text = getString(R.string.btn_parse_serial)
                    binding.btnParseSerial.isEnabled = true
                    parsing = false
                }
            }
        }.start()
    }

    private fun showLines(lines: List<String>) {
        adapter.submitList(if (lines.isEmpty()) listOf("(empty)") else lines)
        binding.lineList.scrollToPosition(0)
    }

    private fun saveTxt() {
        try {
            val lines = if (showingAnnotated) annotatedLines ?: originalLines else originalLines
            val outName = sourceName.removeSuffix(".alog").removeSuffix(".ALOG") + ".txt"
            val out = File(SharedAlogDirs.filesDir(), outName)
            SharedAlogDirs.filesDir().mkdirs()
            BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { writer ->
                for (line in lines) {
                    writer.write(line)
                    writer.newLine()
                }
            }
            Toast.makeText(this, getString(R.string.saved_txt, out.absolutePath), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.save_failed, t.message), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"

        internal fun splitLegacyLines(txt: String): List<String> {
            if (txt.isEmpty()) return emptyList()
            val lines = txt.split('\n')
            return if (txt.endsWith('\n') && lines.isNotEmpty() && lines.last().isEmpty()) {
                lines.dropLast(1)
            } else {
                lines
            }
        }
    }
}
