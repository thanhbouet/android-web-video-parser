package com.example.videodownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.ezt1.webvideo.parser.CoreURLParser
import com.ezt1.webvideo.parser.VideoInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class VideoResultAdapter(
    private val onDownloadClick: (VideoInfo) -> Unit
) : ListAdapter<VideoInfo, VideoResultAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val quality: Chip = view.findViewById(R.id.chipQuality)
        val type: Chip = view.findViewById(R.id.chipType)
        val size: TextView = view.findViewById(R.id.tvSize)
        val btnDl: MaterialButton = view.findViewById(R.id.btnDownload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_result, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val vi = getItem(position)

        holder.title.text = vi.title.ifEmpty { "Untitled" }
        holder.quality.text = vi.label()
        holder.type.text = if (vi.isHls()) "HLS" else "MP4"
        holder.size.text = vi.sizeStr()
        holder.size.visibility = if (vi.sizeStr().isEmpty()) View.GONE else View.VISIBLE

        if (!vi.thumbnail.isNullOrEmpty()) {
            Glide.with(holder.thumb)
                .load(vi.thumbnail)
                .centerCrop()
                .into(holder.thumb)
        } else {
            holder.thumb.setImageResource(R.drawable.ic_video_placeholder)
        }

        holder.btnDl.setOnClickListener { onDownloadClick(vi) }
        holder.btnDl.isEnabled = vi.isDownloadable
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoInfo>() {
            override fun areItemsTheSame(a: VideoInfo, b: VideoInfo) = a.url == b.url
            override fun areContentsTheSame(a: VideoInfo, b: VideoInfo) = a == b
        }
    }

    // Thêm Referer + User-Agent header khi load ảnh
    // Một số CDN (Reddit, v.v.) chặn request không có Referer
    private fun buildGlideUrl(imageUrl: String, pageUrl: String): GlideUrl {
        val referer = when {
            pageUrl.isNotEmpty() -> pageUrl
            imageUrl.contains("redd.it") -> "https://www.reddit.com/"
            imageUrl.contains("dmcdn.net") -> "https://www.dailymotion.com/"
            imageUrl.contains("naver") -> "https://tv.naver.com/"
            else -> imageUrl
        }

        return GlideUrl(
            imageUrl,
            LazyHeaders.Builder()
                .addHeader("Referer", referer)
                .addHeader("User-Agent", CoreURLParser.UA_MOBILE)
                .addHeader("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                .build()
        )
    }
}
