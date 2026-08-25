package com.chyi.alog.sample.viewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.chyi.alog.sample.viewer.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val files = mutableListOf<File>()
    private lateinit var adapter: ArrayAdapter<String>
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pathHint.text = SharedAlogDirs.filesDir().absolutePath
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.fileList.adapter = adapter
        binding.fileList.setOnItemClickListener { _, _, position, _ ->
            val file = files.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, TxtViewActivity::class.java).putExtra(TxtViewActivity.EXTRA_PATH, file.absolutePath),
            )
        }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnGrant.setOnClickListener { StorageAccess.ensureAccess(this) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!StorageAccess.hasAccess(this)) {
            binding.emptyHint.visibility = View.VISIBLE
            binding.emptyHint.text = getString(R.string.need_storage)
            adapter.clear()
            files.clear()
            return
        }
        val dir = SharedAlogDirs.filesDir()
        dir.mkdirs()
        val listed = dir.listFiles { f -> f.isFile && f.name.endsWith(".alog", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.clear()
        files.addAll(listed)
        adapter.clear()
        adapter.addAll(listed.map { formatRow(it) })
        adapter.notifyDataSetChanged()
        binding.emptyHint.visibility = if (listed.isEmpty()) View.VISIBLE else View.GONE
        if (listed.isEmpty()) {
            binding.emptyHint.text = getString(R.string.empty_list)
        }
    }

    private fun formatRow(file: File): String {
        val sizeKb = file.length() / 1024.0
        val mtime = timeFmt.format(Date(file.lastModified()))
        return "%s\n%.1f KB · %s".format(Locale.US, file.name, sizeKb, mtime)
    }
}
