package com.musicmixer.app.ui.download

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.musicmixer.app.download.DownloadResult
import com.musicmixer.app.download.StreamDownloader
import kotlinx.coroutines.launch

sealed class DownloadState {
    object Idle : DownloadState()
    data class Extracting(val platform: String) : DownloadState()
    data class Downloading(
        val title: String,
        val progressPct: Int,
        val downloadedMb: Float,
        val totalMb: Float
    ) : DownloadState()
    data class Success(val result: DownloadResult) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableLiveData<DownloadState>(DownloadState.Idle)
    val state: LiveData<DownloadState> get() = _state

    private val _history = MutableLiveData<List<DownloadHistoryItem>>(emptyList())
    val history: LiveData<List<DownloadHistoryItem>> get() = _history

    fun downloadWithTitleCapture(url: String) {
        if (url.isBlank()) {
            _state.value = DownloadState.Error("Please enter a URL")
            return
        }
        val trimmed = url.trim()
        if (!StreamDownloader.isSupportedUrl(trimmed)) {
            _state.value = DownloadState.Error(
                "Unsupported URL.\nTry a YouTube, SoundCloud, Bandcamp or direct audio link."
            )
            return
        }

        val platform = StreamDownloader.platformLabel(trimmed)
        _state.value = DownloadState.Extracting(platform)

        // Track title discovered mid-download (set once extractor finishes)
        var discoveredTitle = platform

        viewModelScope.launch {
            try {
                val result = StreamDownloader.download(
                    context = getApplication(),
                    url = trimmed
                ) { downloaded, total ->
                    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    _state.postValue(
                        DownloadState.Downloading(
                            title = discoveredTitle,
                            progressPct = pct,
                            downloadedMb = downloaded / 1_048_576f,
                            totalMb = total / 1_048_576f
                        )
                    )
                }
                // Once download() returns the result, we have the real title
                discoveredTitle = result.title

                // Add to history list
                val item = DownloadHistoryItem(
                    title = result.title,
                    platform = platform,
                    fileUri = Uri.fromFile(result.file),
                    durationMs = result.durationMs,
                    thumbnailUrl = result.thumbnailUrl
                )
                val current = _history.value?.toMutableList() ?: mutableListOf()
                current.add(0, item)
                _history.postValue(current)

                _state.postValue(DownloadState.Success(result))

            } catch (e: Exception) {
                _state.postValue(
                    DownloadState.Error(
                        buildString {
                            append(e.message ?: "Download failed")
                            if (e.message?.contains("reCaptcha", ignoreCase = true) == true) {
                                append("\n\nYouTube requires a reCaptcha bypass. Try a SoundCloud or direct URL instead.")
                            }
                        }
                    )
                )
            }
        }
    }

    fun resetState() {
        _state.value = DownloadState.Idle
    }
}

data class DownloadHistoryItem(
    val title: String,
    val platform: String,
    val fileUri: Uri,
    val durationMs: Long,
    val thumbnailUrl: String?
)
