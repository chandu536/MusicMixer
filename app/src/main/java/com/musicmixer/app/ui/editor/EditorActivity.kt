package com.musicmixer.app.ui.editor

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.musicmixer.app.audio.AudioPlayer
import com.musicmixer.app.databinding.ActivityEditorBinding
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Working values in ms
    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var volumePct: Int = 100
    private var fadeIn: Boolean = false
    private var fadeOut: Boolean = false
    /** Silence padding in milliseconds */
    private var delayBeforeMs: Long = 0L
    private var delayAfterMs: Long = 0L

    /** Guard to prevent text-watcher ↔ setter infinite loops */
    private var suppressTextWatcher = false

    /** Current preview playback speed — applied to player on every preview start */
    private var playbackSpeed: Float = 1f

    // ─── Lifecycle ────────────────────────────────────────────────────────────

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

        startMs       = track.trimStartMs
        endMs         = track.trimEndMs
        volumePct     = track.volumePercent
        fadeIn        = false
        fadeOut       = false
        delayBeforeMs = track.delayBeforeMs
        delayAfterMs  = track.delayAfterMs

        setupHeader()
        setupWaveform()
        setupTimeFields()
        setupStepButtons()
        setupVolume()
        setupFadeChips()
        setupDelayFields()
        setupSpeedChips()
        setupPreviewPlayer()
        setupResetApply()
    }

    override fun onPause() {
        super.onPause()
        // Stop preview whenever the editor loses focus — user pressed back, home, or switched app
        stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
    }

    // ─── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.tvTrackName.setText(track.displayName)
        binding.tvTrackName.setOnEditorActionListener { _, _, _ ->
            binding.tvTrackName.clearFocus()
            false
        }
        binding.tvOriginalDuration.text = "Original: ${FileUtils.formatDuration(track.durationMs)}"
        refreshSelectedDuration()
    }

    private fun refreshSelectedDuration() {
        binding.tvSelectedDuration.text = "Selected: ${FileUtils.formatDuration(endMs - startMs)}"
    }

    // ─── Waveform ─────────────────────────────────────────────────────────────

    private fun setupWaveform() {
        val color = TRACK_COLORS[track.colorIndex % TRACK_COLORS.size]
        rebindWaveform(color)

        binding.trimWaveform.onTrimChanged = { sRatio, eRatio ->
            startMs = (sRatio * track.durationMs).toLong()
            endMs   = (eRatio * track.durationMs).toLong()
            updateTimeFields()
            refreshSelectedDuration()
        }

        binding.trimWaveform.onScrubChanged = { ratio ->
            val newPos = (ratio * track.durationMs).toLong().coerceIn(startMs, endMs)
            // Keep the line exactly where the user placed it
            binding.trimWaveform.setPlayPosition(ratio)
            if (player.isPlaying) {
                seekPreviewTo(newPos)
            }
        }
    }

    private fun rebindWaveform(color: Int = TRACK_COLORS[track.colorIndex % TRACK_COLORS.size]) {
        val sRatio = if (track.durationMs > 0) startMs.toFloat() / track.durationMs else 0f
        val eRatio = if (track.durationMs > 0) endMs.toFloat()   / track.durationMs else 1f
        binding.trimWaveform.bind(track.waveformPeaks, color, sRatio, eRatio)
    }

    // ─── Time fields (bidirectional sync) ────────────────────────────────────

    private fun setupTimeFields() {
        // Initial population
        updateTimeFields()

        // Start fields
        binding.etStartMin.addTextChangedListener(timeWatcher { readTimeFields(isStart = true) })
        binding.etStartSec.addTextChangedListener(timeWatcher { readTimeFields(isStart = true) })
        binding.etStartMs.addTextChangedListener(timeWatcher { readTimeFields(isStart = true) })

        // End fields
        binding.etEndMin.addTextChangedListener(timeWatcher { readTimeFields(isStart = false) })
        binding.etEndSec.addTextChangedListener(timeWatcher { readTimeFields(isStart = false) })
        binding.etEndMs.addTextChangedListener(timeWatcher { readTimeFields(isStart = false) })
    }

    /** Populate EditText fields from [startMs] / [endMs] without triggering watchers */
    private fun updateTimeFields() {
        suppressTextWatcher = true
        val s = startMs
        val e = endMs
        binding.etStartMin.setText((s / 60000).toString())
        binding.etStartSec.setText(((s % 60000) / 1000).toString())
        binding.etStartMs.setText((s % 1000).toString())
        binding.etEndMin.setText((e / 60000).toString())
        binding.etEndSec.setText(((e % 60000) / 1000).toString())
        binding.etEndMs.setText((e % 1000).toString())
        suppressTextWatcher = false
    }

    /** Read back the time fields and update startMs/endMs; clamp to valid range */
    private fun readTimeFields(isStart: Boolean) {
        if (suppressTextWatcher) return
        val min = if (isStart) binding.etStartMin.text.toString().toLongOrNull() ?: return
                  else          binding.etEndMin.text.toString().toLongOrNull() ?: return
        val sec = if (isStart) binding.etStartSec.text.toString().toLongOrNull() ?: return
                  else          binding.etEndSec.text.toString().toLongOrNull() ?: return
        val ms  = if (isStart) binding.etStartMs.text.toString().toLongOrNull() ?: return
                  else          binding.etEndMs.text.toString().toLongOrNull() ?: return

        val totalMs = min * 60_000L + sec * 1_000L + ms

        if (isStart) {
            startMs = totalMs.coerceIn(0L, endMs - 100L)
        } else {
            endMs = totalMs.coerceIn(startMs + 100L, track.durationMs)
        }
        rebindWaveform()
        refreshSelectedDuration()
    }

    private fun timeWatcher(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { block() }
    }

    // ─── Step buttons ─────────────────────────────────────────────────────────

    private fun setupStepButtons() {
        // Start ±
        binding.btnStartMinus5.setOnClickListener  { shiftStart(-5_000L) }
        binding.btnStartMinus1.setOnClickListener  { shiftStart(-1_000L) }
        binding.btnStartMinus01.setOnClickListener { shiftStart(-100L)   }
        binding.btnStartPlus01.setOnClickListener  { shiftStart(+100L)   }
        binding.btnStartPlus1.setOnClickListener   { shiftStart(+1_000L) }
        binding.btnStartPlus5.setOnClickListener   { shiftStart(+5_000L) }

        // End ±
        binding.btnEndMinus5.setOnClickListener  { shiftEnd(-5_000L) }
        binding.btnEndMinus1.setOnClickListener  { shiftEnd(-1_000L) }
        binding.btnEndMinus01.setOnClickListener { shiftEnd(-100L)   }
        binding.btnEndPlus01.setOnClickListener  { shiftEnd(+100L)   }
        binding.btnEndPlus1.setOnClickListener   { shiftEnd(+1_000L) }
        binding.btnEndPlus5.setOnClickListener   { shiftEnd(+5_000L) }
    }

    private fun shiftStart(deltaMs: Long) {
        startMs = (startMs + deltaMs).coerceIn(0L, endMs - 100L)
        updateTimeFields()
        rebindWaveform()
        refreshSelectedDuration()
    }

    private fun shiftEnd(deltaMs: Long) {
        endMs = (endMs + deltaMs).coerceIn(startMs + 100L, track.durationMs)
        updateTimeFields()
        rebindWaveform()
        refreshSelectedDuration()
    }

    // ─── Volume ───────────────────────────────────────────────────────────────

    private fun setupVolume() {
        binding.sbVolume.progress = volumePct
        binding.tvVolumeVal.text = "$volumePct%"
        binding.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                volumePct = progress
                binding.tvVolumeVal.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ─── Fade chips ───────────────────────────────────────────────────────────

    private fun setupFadeChips() {
        binding.chipFadeIn.isChecked  = fadeIn
        binding.chipFadeOut.isChecked = fadeOut
        binding.chipFadeIn.setOnCheckedChangeListener  { _, c -> fadeIn  = c }
        binding.chipFadeOut.setOnCheckedChangeListener { _, c -> fadeOut = c }
    }

    // ─── Delay fields ─────────────────────────────────────────────────────────

    private fun setupDelayFields() {
        // Populate from existing track values (empty = 0)
        if (delayBeforeMs > 0) {
            binding.etDelayBefore.setText("%.3f".format(delayBeforeMs / 1000.0))
        }
        if (delayAfterMs > 0) {
            binding.etDelayAfter.setText("%.3f".format(delayAfterMs / 1000.0))
        }
    }

    /** Read delay fields → update working vars. Returns false if input is invalid. */
    private fun readDelayFields() {
        val beforeSec = binding.etDelayBefore.text.toString().toDoubleOrNull() ?: 0.0
        val afterSec  = binding.etDelayAfter.text.toString().toDoubleOrNull() ?: 0.0
        delayBeforeMs = (beforeSec * 1000).toLong().coerceAtLeast(0L)
        delayAfterMs  = (afterSec  * 1000).toLong().coerceAtLeast(0L)
    }

    // ─── Playback speed chips ─────────────────────────────────────────────────

    private fun setupSpeedChips() {
        // Map chip id → speed multiplier
        val speeds = mapOf(
            binding.chipSpeed18  to 0.125f,
            binding.chipSpeed14  to 0.25f,
            binding.chipSpeed12  to 0.5f,
            binding.chipSpeed1x  to 1.0f,
            binding.chipSpeed2x  to 2.0f,
            binding.chipSpeed3x  to 3.0f
        )
        binding.chipGroupSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            val newSpeed = checkedIds.firstOrNull()?.let { id ->
                speeds.entries.firstOrNull { it.key.id == id }?.value
            } ?: 1f
            playbackSpeed = newSpeed
            // Apply live if currently previewing
            player.setSpeed_live(newSpeed)
        }
    }

    // ─── Preview player ───────────────────────────────────────────────────────

    private fun setupPreviewPlayer() {
        binding.btnPreview.setOnClickListener {
            if (player.isPlaying) stopPreview() else launchPreview(startMs, endMs)
        }
    }

    /** Wire the position callback so the timer + playhead update during playback. */
    private fun attachPositionCallback(fromMs: Long) {
        player.onPositionMs = { posMs ->
            val absMs = fromMs + posMs
            binding.tvPreviewTimer.post {
                binding.tvPreviewTimer.text =
                    "${FileUtils.formatDuration(absMs)} / ${FileUtils.formatDuration(endMs)}"
                if (track.durationMs > 0) {
                    binding.trimWaveform.setPlayPosition(absMs.toFloat() / track.durationMs)
                }
            }
        }
    }

    private fun launchPreview(fromMs: Long, toMs: Long) {
        player.onPositionMs = null
        player.stop()
        previewJob?.cancel()

        player.volume = volumePct / 100f
        player.speed  = playbackSpeed
        attachPositionCallback(fromMs)

        binding.btnPreview.text = "⏹  Stop"
        binding.tvPreviewTimer.visibility = android.view.View.VISIBLE

        previewJob = lifecycleScope.launch {
            player.play(applicationContext, track.uri, fromMs, toMs)
            withContext(Dispatchers.Main) { stopPreview() }
        }
    }

    /**
     * Seek to [newFromMs] while already playing: stop+flush old audio then restart immediately.
     */
    private fun seekPreviewTo(newFromMs: Long) {
        player.onPositionMs = null
        player.stop()
        previewJob?.cancel()

        player.volume = volumePct / 100f
        player.speed  = playbackSpeed
        attachPositionCallback(newFromMs)
        previewJob = lifecycleScope.launch {
            player.play(applicationContext, track.uri, newFromMs, endMs)
            withContext(Dispatchers.Main) { stopPreview() }
        }
    }

    private fun stopPreview() {
        player.onPositionMs = null
        player.stop()
        previewJob?.cancel()
        previewJob = null
        binding.btnPreview.text = "▶  Preview"
        binding.tvPreviewTimer.visibility = android.view.View.GONE
        binding.tvPreviewTimer.text = ""
        // Playhead line intentionally NOT reset — stays where playback stopped / user scrubbed
    }

    // ─── Reset / Apply ────────────────────────────────────────────────────────

    private fun setupResetApply() {
        binding.btnResetTrim.setOnClickListener {
            stopPreview()
            startMs       = 0L
            endMs         = track.durationMs
            volumePct     = 100
            fadeIn        = false
            fadeOut       = false
            delayBeforeMs = 0L
            delayAfterMs  = 0L
            binding.sbVolume.progress    = 100
            binding.tvVolumeVal.text     = "100%"
            binding.chipFadeIn.isChecked  = false
            binding.chipFadeOut.isChecked = false
            binding.etDelayBefore.setText("")
            binding.etDelayAfter.setText("")
            updateTimeFields()
            rebindWaveform()
            refreshSelectedDuration()
            Toast.makeText(this, "Reset to original", Toast.LENGTH_SHORT).show()
        }

        binding.btnApply.setOnClickListener {
            stopPreview()
            if (endMs - startMs < 100) {
                Toast.makeText(this, "Selection too short", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            readDelayFields()
            val newName = binding.tvTrackName.text.toString().trim()
                .ifBlank { track.displayName }
            val result = track.copy(
                displayName   = newName,
                trimStartMs   = startMs,
                trimEndMs     = endMs,
                volumePercent = volumePct,
                delayBeforeMs = delayBeforeMs,
                delayAfterMs  = delayAfterMs
            )
            val data = Intent().putExtra(EXTRA_TRACK, result)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }
}
