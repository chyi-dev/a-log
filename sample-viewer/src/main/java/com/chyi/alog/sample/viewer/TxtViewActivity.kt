package com.chyi.alog.sample.viewer

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chyi.alog.decode.AlogDecoder
import com.chyi.alog.decode.LegacyTxtFormatter
import com.chyi.alog.sample.viewer.databinding.ActivityTxtViewBinding
import java.io.File

class TxtViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTxtViewBinding
    private var txtBody: String = ""
    private var sourceName: String = ""

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
        binding.txtContent.text = getString(R.string.decoding)
        binding.btnSave.isEnabled = false
        binding.btnSave.setOnClickListener { saveTxt() }

        Thread {
            try {
                val lines = AlogDecoder.decode(file)
                txtBody = LegacyTxtFormatter.toLegacyTxt(lines)
                runOnUiThread {
                    binding.txtContent.text = if (txtBody.isEmpty()) "(empty)" else txtBody
                    binding.btnSave.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.txtContent.text = getString(R.string.decode_failed, t.message ?: t.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun saveTxt() {
        try {
            val outName = sourceName.removeSuffix(".alog").removeSuffix(".ALOG") + ".txt"
            val out = File(SharedAlogDirs.filesDir(), outName)
            SharedAlogDirs.filesDir().mkdirs()
            out.writeText(txtBody, Charsets.UTF_8)
            Toast.makeText(this, getString(R.string.saved_txt, out.absolutePath), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.save_failed, t.message), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
