package com.musicmixer.app.ui.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.musicmixer.app.R
import com.musicmixer.app.audio.AudioMixer
import com.musicmixer.app.audio.AudioPlayer
import com.musicmixer.app.databinding.ActivityMainBinding
import com.musicmixer.app.databinding.BottomSheetExportBinding
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.ui.download.DownloadActivity
import com.musicmixer.app.ui.editor.EditorActivity
import com.musicmixer.app.utils.FileUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID   = "project_id"
        const val EXTRA_PROJECT_NAME = "project_name"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: TrackAdapter

    // ─── Launchers ────────────────────────────────────────────────────────
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            viewModel.addTracks(uris)
        }
    }

    private val editTrackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val updatedTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(EditorActivity.EXTRA_TRACK, AudioTrack::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(EditorActivity.EXTRA_TRACK)
            }
            updatedTrack?.let { viewModel.updateTrack(it) }
        }
    }

    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uriStr = result.data?.getStringExtra(DownloadActivity.RESULT_ADD_URI)
            if (!uriStr.isNullOrBlank()) {
                viewModel.addTracks(listOf(Uri.parse(uriStr)))
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openFilePicker()
        else Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
    }

    // ─── onCreate ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Receive project context from ProjectsActivity
        val projectId   = intent.getStringExtra(EXTRA_PROJECT_ID) ?: ""
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Mix"

        // Show project name in toolbar
        binding.tvProjectName.text = projectName

        // Back arrow → go back to projects list
        binding.btnBack.setOnClickListener {
            finish()
        }

        viewModel.initProject(projectId, projectName)

        setupAdapter()
        observeViewModel()
        setupTopBar()
    }

    private fun setupAdapter() {
        adapter = TrackAdapter(
            onRemove        = { track -> viewModel.removeTrack(track) },
            onVolumeChanged = { track, pct -> viewModel.setTrackVolume(track, pct) },
            onTrimChanged   = { track, s, e -> viewModel.updateTrim(track, s, e) },
            onOrderChanged  = { from, to -> viewModel.reorderTrack(from, to) },
            onEditTrack     = { track ->
                adapter.stopClipPlayback()
                val intent = Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_TRACK, track)
                editTrackLauncher.launch(intent)
            }
        )
        binding.rvTracks.layoutManager = LinearLayoutManager(this)
        binding.rvTracks.adapter = adapter
        adapter.attachToRecyclerView(binding.rvTracks)
    }

    private fun observeViewModel() {
        viewModel.tracks.observe(this) { tracks ->
            val has = tracks.isNotEmpty()
            binding.emptyState.visibility = if (has) View.GONE  else View.VISIBLE
            binding.rvTracks.visibility   = if (has) View.VISIBLE else View.GONE
            binding.tvTrackCount.text     = "${tracks.size} track${if (tracks.size != 1) "s" else ""}"
            adapter.submitList(tracks.toList())
        }

        viewModel.loading.observe(this) { loading ->
            binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.loadingMsg.observe(this) { msg ->
            if (msg != null) binding.tvLoadingMsg.text = msg
        }

        viewModel.error.observe(this) { msg ->
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); viewModel.clearError() }
        }
    }

    private fun setupTopBar() {
        binding.btnPlayAll.setOnClickListener { playAllSequential() }
        binding.btnShareMix.setOnClickListener { startMixAndShare() }
        binding.btnAddTracks.setOnClickListener { requestAndPickFiles() }
        binding.btnDownloadUrl.setOnClickListener {
            downloadLauncher.launch(Intent(this, DownloadActivity::class.java))
        }

        binding.sbMasterVolume.progress = (viewModel.masterVolume * 100).toInt()
        binding.switchFade.isChecked = viewModel.applyFade

        binding.sbMasterVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) viewModel.saveMasterVol(p / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.switchFade.setOnCheckedChangeListener { _, checked ->
            viewModel.saveFade(checked)
        }
    }

    // ─── Play all clips sequentially ──────────────────────────────────────
    private var globalPlayer = AudioPlayer()
    private var globalPlaying = false
    private var globalJob: kotlinx.coroutines.Job? = null

    // Export sheet mix player — kept here so onPause can stop it
    private var sheetMixPlayer: AudioPlayer? = null
    private var sheetMixJob: kotlinx.coroutines.Job? = null

    private var globalTotalMs = 0L
    private var globalTrackOffsetMs = 0L
    private var globalUserScrubbing = false
    // Seek position requested by user during scrub — applied when touch is released
    private var globalScrubTargetMs = 0L
    // Named Runnable for pending seek-bar position updates — cancelled on scrub start/release
    // so queued posts never overwrite the user's drag position after they lift their finger.
    private var seekBarUpdateRunnable: Runnable? = null

    private fun showPlayAllBar(totalMs: Long) {
        globalTotalMs = totalMs
        binding.tvPlayallDuration.text = FileUtils.formatDuration(totalMs)
        binding.tvPlayallPosition.text = FileUtils.formatDuration(0L)
        binding.seekbarPlayall.progress = 0
        binding.layoutPlayallBar.visibility = View.VISIBLE
        binding.dividerPlayall.visibility   = View.VISIBLE
    }

    private fun hidePlayAllBar() {
        binding.layoutPlayallBar.visibility = View.GONE
        binding.dividerPlayall.visibility   = View.GONE
        binding.seekbarPlayall.progress     = 0
        binding.tvPlayallPosition.text      = FileUtils.formatDuration(0L)
    }

    private fun stopPlayAll() {
        // Null out callback FIRST so no new posts are queued
        globalPlayer.onPositionMs = null
        globalPlayer.stop()
        globalJob?.cancel()
        globalJob = null
        globalPlaying = false
        globalTrackOffsetMs = 0L
        globalScrubTargetMs = 0L
        // Flush any pending position update that was already queued before callback was nulled
        seekBarUpdateRunnable?.let { binding.seekbarPlayall.removeCallbacks(it) }
        seekBarUpdateRunnable = null
        binding.btnPlayAll.setImageResource(R.drawable.ic_play)
        binding.btnPlayAll.setBackgroundResource(R.drawable.bg_play_btn)
        adapter.stopClipPlayback()
        hidePlayAllBar()
    }

    /**
     * Jump playback to [seekMs] within the global timeline.
     * Cancels current job, stops audio immediately (flush+stop), then restarts from seekMs.
     */
    private fun seekPlayAllTo(tracks: List<com.musicmixer.app.model.AudioTrack>, seekMs: Long) {
        // Fully stop current playback — flush prevents buffered audio playing on
        globalPlayer.onPositionMs = null
        globalPlayer.stop()
        globalJob?.cancel()

        // Find target track and offset within it
        var remaining = seekMs.coerceIn(0L, globalTotalMs)
        var targetIdx = 0
        var offsetInTrack = 0L
        for ((i, t) in tracks.withIndex()) {
            if (remaining <= t.trimmedDurationMs) {
                targetIdx = i; offsetInTrack = remaining; break
            }
            remaining -= t.trimmedDurationMs
            if (i == tracks.lastIndex) { targetIdx = i; offsetInTrack = t.trimmedDurationMs }
        }

        val cumulOffset = tracks.take(targetIdx).sumOf { it.trimmedDurationMs }
        globalTrackOffsetMs = cumulOffset

        // Update seek bar + label immediately on main thread
        if (globalTotalMs > 0) {
            val prog = ((seekMs * 1000L) / globalTotalMs).toInt().coerceIn(0, 1000)
            binding.seekbarPlayall.progress = prog
            binding.tvPlayallPosition.text  = FileUtils.formatDuration(seekMs)
        }

        globalJob = CoroutineScope(Dispatchers.Main).launch {
            val target      = tracks[targetIdx]
            val startInFile = target.trimStartMs + offsetInTrack
            if (globalPlaying) {
                // Apply per-track volume
                globalPlayer.volume = target.volumePercent / 100f
                attachGlobalPositionCallback(cumulOffset)
                globalPlayer.play(applicationContext, target.uri, startInFile, target.trimEndMs)
                globalTrackOffsetMs += target.trimmedDurationMs - offsetInTrack
            }
            for (i in (targetIdx + 1)..tracks.lastIndex) {
                if (!globalPlaying) break
                val t = tracks[i]
                globalPlayer.volume = t.volumePercent / 100f
                attachGlobalPositionCallback(globalTrackOffsetMs)
                globalPlayer.play(applicationContext, t.uri, t.trimStartMs, t.trimEndMs)
                globalTrackOffsetMs += t.trimmedDurationMs
            }
            withContext(Dispatchers.Main) { if (globalPlaying) stopPlayAll() }
        }
    }

    private fun playAllSequential() {
        if (globalPlaying) { stopPlayAll(); return }

        val tracks = viewModel.tracks.value?.toList() ?: return
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No tracks to play", Toast.LENGTH_SHORT).show(); return
        }

        val totalMs = tracks.sumOf { it.trimmedDurationMs }
        adapter.stopClipPlayback()
        globalPlaying = true
        globalTrackOffsetMs = 0L
        globalScrubTargetMs = 0L
        binding.btnPlayAll.setImageResource(R.drawable.ic_stop)
        binding.btnPlayAll.setBackgroundResource(R.drawable.bg_stop_btn)
        showPlayAllBar(totalMs)

        // Seek bar — update label while dragging, seek on release.
        // Pending playback-position posts are flushed on both touch-start and touch-release
        // so they can never land after the user's finger position.
        binding.seekbarPlayall.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                globalUserScrubbing = true
                // Flush any pending position update already queued on the UI thread
                seekBarUpdateRunnable?.let { sb.removeCallbacks(it) }
                seekBarUpdateRunnable = null
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && globalTotalMs > 0) {
                    globalScrubTargetMs = (progress.toLong() * globalTotalMs) / 1000L
                    binding.tvPlayallPosition.text = FileUtils.formatDuration(globalScrubTargetMs)
                }
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                // Kill the position callback FIRST — stops the IO thread from queuing
                // any new post() between now and seekPlayAllTo() nulling it again.
                globalPlayer.onPositionMs = null
                // Flush any post already in the queue before the callback was nulled
                seekBarUpdateRunnable?.let { sb.removeCallbacks(it) }
                seekBarUpdateRunnable = null
                globalUserScrubbing = false
                if (globalTotalMs == 0L) return
                // Pin bar + label to exact finger-release position
                val finalMs = (sb.progress.toLong() * globalTotalMs) / 1000L
                sb.progress = ((finalMs * 1000L) / globalTotalMs).toInt().coerceIn(0, 1000)
                binding.tvPlayallPosition.text = FileUtils.formatDuration(finalMs)
                seekPlayAllTo(tracks, finalMs)
            }
        })

        // Start from beginning
        globalJob = CoroutineScope(Dispatchers.Main).launch {
            for (track in tracks) {
                if (!globalPlaying) break
                globalPlayer.volume = track.volumePercent / 100f
                attachGlobalPositionCallback(globalTrackOffsetMs)
                globalPlayer.play(applicationContext, track.uri, track.trimStartMs, track.trimEndMs)
                globalTrackOffsetMs += track.trimmedDurationMs
            }
            withContext(Dispatchers.Main) { if (globalPlaying) stopPlayAll() }
        }
    }

    private fun attachGlobalPositionCallback(offsetMs: Long) {
        globalPlayer.onPositionMs = { posMs ->
            if (!globalUserScrubbing && globalTotalMs > 0) {
                val absMs    = offsetMs + posMs
                val progress = ((absMs * 1000L) / globalTotalMs).toInt().coerceIn(0, 1000)
                // Cancel any previously queued update before posting a new one.
                // This prevents stale posts from firing after the user lifts their finger.
                val bar = binding.seekbarPlayall
                seekBarUpdateRunnable?.let { bar.removeCallbacks(it) }
                val r = Runnable {
                    bar.progress = progress
                    binding.tvPlayallPosition.text = FileUtils.formatDuration(absMs)
                }
                seekBarUpdateRunnable = r
                bar.post(r)
            }
        }
    }

    // ─── Render mix → share sheet ─────────────────────────────────────────
    private fun startMixAndShare() {
        val tracks = viewModel.tracks.value
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(this, "Add tracks first", Toast.LENGTH_SHORT).show()
            return
        }
        globalPlayer.stop(); globalJob?.cancel(); globalPlaying = false
        adapter.stopClipPlayback()

        val sheet = BottomSheetDialog(this, R.style.ThemeOverlay_App_BottomSheetDialog)
        val sheetBinding = BottomSheetExportBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)
        sheet.show()

        val masterVol = viewModel.masterVolume
        val fade = viewModel.applyFade

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val exportedFile = AudioMixer.mix(
                    context = applicationContext,
                    tracks  = tracks,
                    masterVolume = masterVol,
                    applyFade = fade
                ) { _, label ->
                    withContext(Dispatchers.Main) { sheetBinding.tvExportStatus.text = label }
                }

                withContext(Dispatchers.Main) {
                    sheetBinding.progressExport.isIndeterminate = false
                    sheetBinding.progressExport.progress = 100
                    sheetBinding.tvExportStatus.text = "✓ Mix ready!"
                    sheetBinding.tvExportDetail.text =
                        "${"%.1f".format(exportedFile.length() / 1_048_576f)} MB · ${tracks.size} tracks"
                    sheetBinding.layoutDone.visibility = View.VISIBLE

                    // ── Get total mix duration for seek bar ───────────────────
                    val mixDurationMs: Long = run {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(exportedFile.absolutePath)
                            mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                        } catch (_: Exception) { 0L }
                        finally { mmr.release() }
                    }

                    sheetBinding.tvMixDuration.text = FileUtils.formatDuration(mixDurationMs)
                    sheetBinding.tvMixPosition.text = FileUtils.formatDuration(0L)
                    sheetBinding.seekbarMix.progress = 0

                    val mixPlayer = AudioPlayer().also { sheetMixPlayer = it }
                    var mixPlaying = false
                    var userScrubbing = false
                    var playFromMs = 0L

                    fun stopMixPlayer() {
                        mixPlayer.onPositionMs = null
                        mixPlayer.stop()
                        sheetMixJob?.cancel()
                        sheetMixJob = null
                        mixPlaying = false
                        sheetBinding.btnPlayMix.text = "▶  Play Mix"
                    }

                    fun attachMixPositionCallback(fromMs: Long) {
                        mixPlayer.onPositionMs = { posMs ->
                            if (!userScrubbing) {
                                val absMs = fromMs + posMs
                                val progress = if (mixDurationMs > 0)
                                    ((absMs * 1000L) / mixDurationMs).toInt().coerceIn(0, 1000)
                                else 0
                                sheetBinding.seekbarMix.post {
                                    sheetBinding.seekbarMix.progress = progress
                                    sheetBinding.tvMixPosition.text = FileUtils.formatDuration(absMs)
                                }
                            }
                        }
                    }

                    attachMixPositionCallback(0L)

                    sheetBinding.seekbarMix.setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(sb: SeekBar) { userScrubbing = true }
                            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                                if (fromUser && mixDurationMs > 0) {
                                    val posMs = (progress * mixDurationMs) / 1000L
                                    sheetBinding.tvMixPosition.text = FileUtils.formatDuration(posMs)
                                }
                            }
                            override fun onStopTrackingTouch(sb: SeekBar) {
                                userScrubbing = false
                                if (mixDurationMs > 0) {
                                    val seekMs = (sb.progress * mixDurationMs) / 1000L
                                    playFromMs = seekMs
                                    if (mixPlaying) {
                                        mixPlayer.onPositionMs = null
                                        mixPlayer.stop()
                                        sheetMixJob?.cancel()
                                        attachMixPositionCallback(seekMs)
                                        sheetMixJob = this@MainActivity.lifecycleScope.launch {
                                            mixPlayer.play(applicationContext,
                                                Uri.fromFile(exportedFile), seekMs, Long.MAX_VALUE)
                                            withContext(Dispatchers.Main) { stopMixPlayer() }
                                        }
                                    }
                                }
                            }
                        }
                    )

                    sheetBinding.btnPlayMix.setOnClickListener {
                        if (mixPlaying) {
                            stopMixPlayer()
                        } else {
                            mixPlaying = true
                            sheetBinding.btnPlayMix.text = "⏹  Stop"
                            attachMixPositionCallback(playFromMs)
                            sheetMixJob = this@MainActivity.lifecycleScope.launch {
                                mixPlayer.play(applicationContext,
                                    Uri.fromFile(exportedFile), playFromMs, Long.MAX_VALUE)
                                withContext(Dispatchers.Main) { stopMixPlayer() }
                            }
                        }
                    }
                    sheet.setOnDismissListener {
                        stopMixPlayer()
                        sheetMixPlayer = null
                    }

                    // WhatsApp: always compress (downsample to mono 22050 Hz) for smaller size.
                    // Uses its own CoroutineScope — the outer launch() is already finished by
                    // the time the user taps this button, so we cannot call launch() on it.
                    sheetBinding.btnShareWhatsapp.setOnClickListener {
                        sheetBinding.btnShareWhatsapp.isEnabled = false
                        sheetBinding.tvExportStatus.text = "Compressing for WhatsApp… 0%"
                        sheetBinding.progressExport.isIndeterminate = false
                        sheetBinding.progressExport.setProgressCompat(0, false)
                        sheetBinding.layoutDone.visibility = View.GONE
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val compressed = AudioMixer.compressForWhatsApp(
                                    context  = applicationContext,
                                    wavFile  = exportedFile
                                ) { pct -> withContext(Dispatchers.Main) {
                                    sheetBinding.progressExport.setProgressCompat(pct, true)
                                    sheetBinding.tvExportStatus.text = "Compressing for WhatsApp… $pct%"
                                }}
                                withContext(Dispatchers.Main) {
                                    sheetBinding.progressExport.setProgressCompat(100, true)
                                    sheetBinding.tvExportStatus.text =
                                        "✓ Compressed! ${"%.1f".format(compressed.length() / 1_048_576f)} MB"
                                    sheetBinding.tvExportDetail.text =
                                        "${"%.1f".format(compressed.length() / 1_048_576f)} MB · WhatsApp ready"
                                    sheetBinding.layoutDone.visibility = View.VISIBLE
                                    sheetBinding.btnShareWhatsapp.isEnabled = true
                                    shareFile(compressed, "com.whatsapp")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    sheetBinding.progressExport.setProgressCompat(0, false)
                                    sheetBinding.tvExportStatus.text = "Compression failed: ${e.message}"
                                    sheetBinding.layoutDone.visibility = View.VISIBLE
                                    sheetBinding.btnShareWhatsapp.isEnabled = true
                                }
                            }
                        }
                    }

                    sheetBinding.btnShareOther.setOnClickListener {
                        shareFile(exportedFile, null)
                    }
                    sheetBinding.btnDownload.setOnClickListener {
                        val saved = FileUtils.saveToDownloads(applicationContext, exportedFile)
                        Toast.makeText(this@MainActivity,
                            if (saved != null) "Saved to Downloads!" else "Save failed",
                            Toast.LENGTH_LONG).show()
                    }
                    sheetBinding.btnClose.setOnClickListener { sheet.dismiss() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sheetBinding.tvExportStatus.text = "Export failed: ${e.message}"
                }
            }
        }
    }

    private fun shareFile(file: java.io.File, pkg: String?) {
        val intent = FileUtils.buildShareIntent(this, file, pkg)
        try { startActivity(intent) }
        catch (e: Exception) {
            if (pkg == "com.whatsapp") {
                Toast.makeText(this, getString(R.string.whatsapp_not_installed), Toast.LENGTH_LONG).show()
                try { startActivity(FileUtils.buildShareIntent(this, file, null)) } catch (_: Exception) {}
            }
        }
    }

    private fun requestAndPickFiles() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            openFilePicker()
        else permissionLauncher.launch(perm)
    }

    private fun openFilePicker() = pickFilesLauncher.launch(arrayOf("audio/*"))

    override fun onPause() {
        super.onPause()
        // Stop ALL audio the moment the activity is no longer in the foreground.
        // This covers: back button, home button, app switch, opening EditorActivity.
        if (globalPlaying) stopPlayAll()
        adapter.stopClipPlayback()
        sheetMixPlayer?.let { it.onPositionMs = null; it.stop() }
        sheetMixJob?.cancel(); sheetMixJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        globalPlayer.onPositionMs = null
        globalPlayer.stop()
        sheetMixPlayer?.let { it.onPositionMs = null; it.stop() }
        sheetMixJob?.cancel()
        adapter.release()
    }
}
