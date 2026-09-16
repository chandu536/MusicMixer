package com.musicmixer.app.ui.download

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicmixer.app.audio.AudioDecoder
import com.musicmixer.app.databinding.ActivityDownloadBinding
import com.musicmixer.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private val viewModel: DownloadViewModel by viewModels()
    private lateinit var historyAdapter: DownloadHistoryAdapter

    // Passed back to MainActivity when user adds a downloaded track to the mixer
    companion object {
        const val RESULT_ADD_URI = "result_add_uri"
        const val RESULT_ADD_TITLE = "result_add_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHistoryList()
        observeViewModel()
        setupInputActions()

        // Auto-detect clipboard URL on open
        checkClipboardForUrl()
    }

    private fun setupHistoryList() {
        historyAdapter = DownloadHistoryAdapter { item ->
            returnTrackToMixer(item.fileUri, item.title)
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state ->
            // Reset all cards
            binding.cardProgress.visibility = View.GONE
            binding.cardSuccess.visibility = View.GONE
            binding.cardError.visibility = View.GONE

            when (state) {
                is DownloadState.Idle -> { /* nothing */ }

                is DownloadState.Extracting -> {
                    binding.cardProgress.visibility = View.VISIBLE
                    binding.progressBar.isIndeterminate = true
                    binding.tvProgressTitle.text = "Extracting from ${state.platform}…"
                    binding.tvProgressDetail.text = "Fetching stream info"
                    binding.tvProgressPct.text = ""
                }

                is DownloadState.Downloading -> {
                    binding.cardProgress.visibility = View.VISIBLE
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.setProgressCompat(state.progressPct, true)
                    binding.tvProgressTitle.text = state.title
                    binding.tvProgressDetail.text = "Downloading audio…"
                    val totalStr = if (state.totalMb > 0f) " / ${"%.1f".format(state.totalMb)} MB" else ""
                    binding.tvProgressPct.text = "${"%.1f".format(state.downloadedMb)} MB$totalStr  ·  ${state.progressPct}%"
                }

                is DownloadState.Success -> {
                    binding.cardSuccess.visibility = View.VISIBLE
                    binding.tvSuccessTitle.text = state.result.title
                    val dur = if (state.result.durationMs > 0)
                        "  ·  ${state.result.durationMs / 60000}m ${(state.result.durationMs % 60000) / 1000}s" else ""
                    val sizeMb = "%.1f MB".format(state.result.file.length() / 1_048_576f)
                    binding.tvSuccessMeta.text = "Downloaded · $sizeMb$dur"

                    binding.btnAddToMixer.setOnClickListener {
                        returnTrackToMixer(Uri.fromFile(state.result.file), state.result.title)
                    }
                    binding.btnDownloadAnother.setOnClickListener {
                        viewModel.resetState()
                        binding.etUrl.text?.clear()
                    }
                }

                is DownloadState.Error -> {
                    binding.cardError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = state.message
                }
            }
        }

        viewModel.history.observe(this) { list ->
            val hasHistory = list.isNotEmpty()
            binding.tvHistoryLabel.visibility = if (hasHistory) View.VISIBLE else View.GONE
            binding.rvHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
            historyAdapter.submitList(list)
        }
    }

    private fun setupInputActions() {
        // Paste button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                binding.etUrl.setText(text)
                binding.etUrl.setSelection(text.length)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Download button
        binding.btnDownload.setOnClickListener { startDownload() }

        // IME "Go" action
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDownload()
                true
            } else false
        }

        // Cancel button
        binding.btnCancel.setOnClickListener {
            // ViewModel coroutine is tied to its scope; canceling via resetState
            viewModel.resetState()
            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
        }

        // Chip hints — tap to fill example URL
        binding.chipYoutube.setOnClickListener {
            binding.etUrl.setText("https://www.youtube.com/watch?v=")
            binding.etUrl.requestFocus()
            binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        }
        binding.chipSoundcloud.setOnClickListener {
            binding.etUrl.setText("https://soundcloud.com/")
            binding.etUrl.requestFocus()
            binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        }
    }

    private fun startDownload() {
        hideKeyboard()
        val url = binding.etUrl.text?.toString()?.trim() ?: ""
        viewModel.downloadWithTitleCapture(url)
    }

    private fun checkClipboardForUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text.startsWith("http") && binding.etUrl.text.isNullOrBlank()) {
            binding.etUrl.setText(text)
            binding.etUrl.setSelection(text.length)
        }
    }

    private fun returnTrackToMixer(uri: Uri, title: String) {
        val data = Intent().apply {
            putExtra(RESULT_ADD_URI, uri.toString())
            putExtra(RESULT_ADD_TITLE, title)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
