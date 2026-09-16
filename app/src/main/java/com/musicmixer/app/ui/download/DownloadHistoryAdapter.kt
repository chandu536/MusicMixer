package com.musicmixer.app.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicmixer.app.databinding.ItemDownloadHistoryBinding
import com.musicmixer.app.utils.FileUtils

class DownloadHistoryAdapter(
    private val onAddToMixer: (DownloadHistoryItem) -> Unit
) : ListAdapter<DownloadHistoryItem, DownloadHistoryAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemDownloadHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadHistoryItem) {
            binding.tvHistoryTitle.text = item.title
            binding.tvHistoryPlatform.text = item.platform
            binding.tvHistoryDuration.text = if (item.durationMs > 0)
                FileUtils.formatDurationShort(item.durationMs) else ""
            binding.tvPlatformIcon.text = when (item.platform) {
                "YouTube" -> "▶"
                "SoundCloud" -> "☁"
                "Bandcamp" -> "🎵"
                "Mixcloud" -> "🎛"
                else -> "🌐"
            }
            binding.btnAddHistory.setOnClickListener { onAddToMixer(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadHistoryItem>() {
            override fun areItemsTheSame(a: DownloadHistoryItem, b: DownloadHistoryItem) =
                a.fileUri == b.fileUri
            override fun areContentsTheSame(a: DownloadHistoryItem, b: DownloadHistoryItem) =
                a == b
        }
    }
}
