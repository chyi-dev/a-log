package com.chyi.alog.sample.viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogLineAdapter : RecyclerView.Adapter<LogLineAdapter.LineVH>() {
    private var lines: List<String> = emptyList()

    fun submitList(newLines: List<String>) {
        lines = newLines
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_line, parent, false) as TextView
        return LineVH(view)
    }

    override fun onBindViewHolder(holder: LineVH, position: Int) {
        holder.textView.text = lines[position]
    }

    override fun getItemCount(): Int = lines.size

    class LineVH(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
