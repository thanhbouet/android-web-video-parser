package com.example.videodownloader

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : ListAdapter<String, LogAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.tvLog)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        holder.text.text = msg
        holder.text.setTextColor(when {
            "✓" in msg || "done" in msg.lowercase() -> Color.parseColor("#22c55e")
            "error" in msg.lowercase() || "✗" in msg -> Color.parseColor("#ef4444")
            "skip" in msg.lowercase() || "warn" in msg.lowercase() -> Color.parseColor("#f59e0b")
            msg.startsWith("[intercept]") -> Color.parseColor("#a78bfa")
            else -> Color.parseColor("#94a3b8")
        })
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
