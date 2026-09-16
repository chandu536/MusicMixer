package com.musicmixer.app.ui.editor

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.musicmixer.app.R
import com.musicmixer.app.audio.AudioPlayer
import com.musicmixer.app.databinding.ActivityEditorBinding
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.utils.FileUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK = "extra_track"
        private val TRACK_COLORS = listOf(
            0xFF6C63FF.toInt(), 0xFFFF6B9D.toInt(), 0xFF00D4AA.toInt(),
            0xFFFFB300.toInt(), 0xFFFF4757.toInt(), 0xFF00BFFF.toInt(),
            0xFFA8E063.toInt(), 0xFFFF7043.toInt()
        )
    }

    private lateinit var binding: ActivityEditorBinding
    private lateinit var track: AudioTrack
    private val player = AudioPlayer()
    private var previewJob: Job? = null

    // Working copies (ratios 0..1)
    private var startRatio = 0f
    private var endRatio = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TRACK, AudioTrack::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TRACK)!!
        }

        startRatio = if (track.durationMs > 0) track.trimStartMs.toFloat() / track.durationMs else 0f
        endRatio = if (track.durationMs > 0) track.trimEndMs.toFloat() / track.durationMs else 1f

        setupUI()
        setupSeekBars()
        setupWaveform()
        setupButtons()
    }

    private fun setupUI() {
        binding.tvTrackName.text = track.displayName
        binding.tvOriginalDuration.text = "Original: ${FileUtils.formatDuration(track.durationMs)}"
        updateDurationLabel()
    }

    private fun updateDurationLabel() {
        val startMs = (startRatio * track.durationMs).toLong()
        val endMs = (endRatio * track.durationMs).toLong()
        binding.tvSelectedDuration.text = "Selected: ${FileUtils.formatDuration(endMs - startMs)}"
        binding.tvTrimStartVal.text = FileUtils.formatDuration(startMs)
        binding.tvTrimEndVal.text = FileUtils.formatDuration(endMs)
        binding.tvTimeStart.text = FileUtils.formatDuration(startMs)
        binding.tvTimeEnd.text = FileUtils.formatDuration(endMs)
    }

    private fun setupSeekBars() {
        binding.sbTrimStart.progress = (startRatio * 1000).toInt()
        binding.sbTrimEnd.progress = (endRatio * 1000).toInt()

        binding.sbTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newRatio = progress / 1000f
                if (newRatio >= endRatio - 0.01f) {
                    sb.progress = ((endRatio - 0.01f) * 1000).toInt()
                    return
                }
                startRatio = newRatio
                binding.trimWaveform.trimStartRatio.let {
                    binding.trimWaveform.bind(
                        track.waveformPeaks,
                        TRACK_COLORS[track.colorIndex % TRACK_COLORS.size],
                        startRatio, endRatio
                    )
                }
                updateDurationLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.sbTrimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newRatio = progress / 1000f
                if (newRatio <= startRatio + 0.01f) {
                    sb.progress = ((startRatio + 0.01f) * 1000).toInt()
                    return
                }
                endRatio = newRatio
                binding.trimWaveform.bind(
                    track.waveformPeaks,
                    TRACK_COLORS[track.colorIndex % TRACK_COLORS.size],
                    startRatio, endRatio
                )
                updateDurationLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupWaveform() {
        val color = TRACK_COLORS[track.colorIndex % TRACK_COLORS.size]
        binding.trimWaveform.bind(track.waveformPeaks, color, startRatio, endRatio)
        binding.trimWaveform.onTrimChanged = { start, end ->
            startRatio = start
            endRatio = end
            // Sync seekbars
            binding.sbTrimStart.progress = (start * 1000).toInt()
            binding.sbTrimEnd.progress = (end * 1000).toInt()
            updateDurationLabel()
        }
    }

    private fun setupButtons() {
        binding.btnPreview.setOnClickListener {
            if (player.isPlaying) {
                player.stop()
                binding.btnPreview.text = "▶  Preview"
            } else {
                val startMs = (startRatio * track.durationMs).toLong()
                val endMs = (endRatio * track.durationMs).toLong()
                binding.btnPreview.text = "⏹  Stop"
                previewJob = lifecycleScope.launch {
                    player.play(applicationContext, track.uri, startMs, endMs)
                    binding.btnPreview.text = "▶  Preview"
                }
            }
        }

        binding.btnResetTrim.setOnClickListener {
            startRatio = 0f
            endRatio = 1f
            binding.sbTrimStart.progress = 0
            binding.sbTrimEnd.progress = 1000
            val color = TRACK_COLORS[track.colorIndex % TRACK_COLORS.size]
            binding.trimWaveform.bind(track.waveformPeaks, color, 0f, 1f)
            updateDurationLabel()
        }

        binding.btnApply.setOnClickListener {
            player.stop()
            val startMs = (startRatio * track.durationMs).toLong()
            val endMs = (endRatio * track.durationMs).toLong()
            val result = track.copy(trimStartMs = startMs, trimEndMs = endMs)
            val data = Intent().putExtra(EXTRA_TRACK, result)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
        previewJob?.cancel()
    }
}
