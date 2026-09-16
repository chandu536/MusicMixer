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
import com.musicmixer.app.utils.FileUtils
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
            onOrderChanged  = { from, to -> viewModel.reorderTrack(from, to) }
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

    private fun playAllSequential() {
        if (globalPlaying) {
            globalPlayer.stop()
            globalJob?.cancel()
            globalPlaying = false
            binding.btnPlayAll.setImageResource(R.drawable.ic_play)
            binding.btnPlayAll.setBackgroundResource(R.drawable.bg_play_btn)
            adapter.stopClipPlayback()
            return
        }

        val tracks = viewModel.tracks.value?.toList() ?: return
        if (tracks.isEmpty()) { Toast.makeText(this, "No tracks to play", Toast.LENGTH_SHORT).show(); return }

        adapter.stopClipPlayback()
        globalPlaying = true
        binding.btnPlayAll.setImageResource(R.drawable.ic_stop)
        binding.btnPlayAll.setBackgroundResource(R.drawable.bg_stop_btn)

        globalJob = CoroutineScope(Dispatchers.Main).launch {
            for (track in tracks) {
                if (!globalPlaying) break
                globalPlayer.play(applicationContext, track.uri, track.trimStartMs, track.trimEndMs)
            }
            globalPlaying = false
            binding.btnPlayAll.setImageResource(R.drawable.ic_play)
            binding.btnPlayAll.setBackgroundResource(R.drawable.bg_play_btn)
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

                    val mixPlayer = AudioPlayer()
                    var mixPlaying = false
                    sheetBinding.btnPlayMix.setOnClickListener {
                        if (mixPlaying) {
                            mixPlayer.stop()
                            mixPlaying = false
                            sheetBinding.btnPlayMix.text = "▶  Play Mix"
                        } else {
                            mixPlaying = true
                            sheetBinding.btnPlayMix.text = "⏹  Stop"
                            launch {
                                mixPlayer.play(applicationContext,
                                    Uri.fromFile(exportedFile), 0L, Long.MAX_VALUE)
                                withContext(Dispatchers.Main) {
                                    mixPlaying = false
                                    sheetBinding.btnPlayMix.text = "▶  Play Mix"
                                }
                            }
                        }
                    }
                    sheet.setOnDismissListener { mixPlayer.stop() }

                    sheetBinding.btnShareWhatsapp.setOnClickListener {
                        shareFile(exportedFile, "com.whatsapp")
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

    override fun onDestroy() {
        super.onDestroy()
        globalPlayer.stop()
        adapter.release()
    }
}
