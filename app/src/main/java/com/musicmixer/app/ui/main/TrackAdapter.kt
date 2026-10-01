package com.musicmixer.app.ui.main

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicmixer.app.R
import com.musicmixer.app.audio.AudioPlayer
import com.musicmixer.app.databinding.ItemTrackBinding
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackAdapter(
    private val onRemove: (AudioTrack) -> Unit,
    private val onVolumeChanged: (AudioTrack, Int) -> Unit,
    private val onTrimChanged: (AudioTrack, Long, Long) -> Unit,
    private val onOrderChanged: (Int, Int) -> Unit,
    private val onEditTrack: (AudioTrack) -> Unit = {}
) : ListAdapter<AudioTrack, TrackAdapter.TrackViewHolder>(DIFF_CALLBACK) {

    private val trackColors = listOf(
        0xFF6C63FF.toInt(), 0xFFFF6B9D.toInt(), 0xFF00D4AA.toInt(),
        0xFFFFB300.toInt(), 0xFFFF4757.toInt(), 0xFF00BFFF.toInt(),
        0xFFA8E063.toInt(), 0xFFFF7043.toInt()
    )

    private val player = AudioPlayer()
    private var playingId: String? = null
    private var playJob: Job? = null
    private var activeHolder: TrackViewHolder? = null
    private var touchHelper: ItemTouchHelper? = null

    fun attachToRecyclerView(rv: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView,
                                vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                onOrderChanged(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = false
        }
        touchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rv) }
    }

    fun getColor(colorIndex: Int): Int = trackColors[colorIndex % trackColors.size]

    // ─────────────────────────────────────────────────────────────────────────
    inner class TrackViewHolder(val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Update the live timer during playback. */
        fun onPosition(currentAbsMs: Long, clipEndMs: Long) {
            binding.tvPlaybackTimer.post {
                binding.tvPlaybackTimer.text =
                    "${FileUtils.formatDuration(currentAbsMs)} / ${FileUtils.formatDuration(clipEndMs)}"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val b = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(b)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) =
        holder.bind(getItem(position))

    private fun TrackViewHolder.bind(track: AudioTrack) {
        val color = getColor(track.colorIndex)

        binding.viewColorBar.setBackgroundColor(color)
        binding.tvTrackName.text = track.displayName
        binding.tvDuration.text  = FileUtils.formatDurationShort(track.durationMs)

        // Trim badge
        if (track.trimStartMs > 0 || track.trimEndMs < track.durationMs) {
            binding.tvTrimInfo.visibility = View.VISIBLE
            binding.tvTrimInfo.text = "✂ ${FileUtils.formatDurationShort(track.trimmedDurationMs)}"
        } else {
            binding.tvTrimInfo.visibility = View.GONE
        }

        // Static trim-ratio bar
        val startRatio = if (track.durationMs > 0) track.trimStartMs.toFloat() / track.durationMs else 0f
        val endRatio   = if (track.durationMs > 0) track.trimEndMs.toFloat()   / track.durationMs else 1f
        binding.clipBar.bind(color, startRatio, endRatio)

        // Trim timestamps
        binding.tvTrimStart.text = FileUtils.formatDuration(track.trimStartMs)
        binding.tvTrimEnd.text   = FileUtils.formatDuration(track.trimEndMs)

        // Playback state
        val isThisPlaying = playingId == track.id
        binding.btnPlayTrack.setImageResource(if (isThisPlaying) R.drawable.ic_stop else R.drawable.ic_play)
        binding.btnPlayTrack.setBackgroundResource(if (isThisPlaying) R.drawable.bg_stop_btn else R.drawable.bg_play_btn)
        binding.tvPlaybackTimer.visibility = if (isThisPlaying) View.VISIBLE else View.GONE

        // Play / Stop
        binding.btnPlayTrack.setOnClickListener {
            if (playingId == track.id) stopClipPlayback()
            else startClipPlayback(this, track)
        }

        // Edit clip → open EditorActivity
        binding.btnEdit.setOnClickListener {
            onEditTrack(track)
        }

        // Delete confirmation
        binding.btnRemove.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(
                binding.root.context, R.style.ThemeOverlay_App_Dialog
            )
                .setTitle("Remove track?")
                .setMessage("\"${track.displayName}\" will be removed from this project.")
                .setPositiveButton("Remove") { _, _ -> onRemove(track) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Drag handle
        binding.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(this)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun startClipPlayback(holder: TrackViewHolder, track: AudioTrack) {
        stopClipPlayback()
        playingId    = track.id
        activeHolder = holder
        notifyDataSetChanged()
        launchPlay(holder, track, track.trimStartMs, track.trimEndMs)
    }

    private fun launchPlay(holder: TrackViewHolder, track: AudioTrack, start: Long, end: Long) {
        player.onPositionMs = { posMs ->
            activeHolder?.onPosition(start + posMs, end)
        }
        playJob = CoroutineScope(Dispatchers.Main).launch {
            player.play(holder.binding.root.context.applicationContext, track.uri, start, end)
            if (playingId == track.id) withContext(Dispatchers.Main) { stopClipPlayback() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    fun stopClipPlayback() {
        player.onPositionMs = null
        player.stop()
        playJob?.cancel()
        playJob = null
        activeHolder = null
        val prev = playingId
        playingId = null
        if (prev != null) notifyDataSetChanged()
    }

    fun release() = stopClipPlayback()

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AudioTrack>() {
            override fun areItemsTheSame(a: AudioTrack, b: AudioTrack) = a.id == b.id
            override fun areContentsTheSame(a: AudioTrack, b: AudioTrack) = a == b
        }
    }
}
