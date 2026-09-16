package com.musicmixer.app.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.musicmixer.app.audio.AudioDecoder
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.model.Project
import com.musicmixer.app.utils.ProjectStore
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _tracks = MutableLiveData<MutableList<AudioTrack>>(mutableListOf())
    val tracks: LiveData<MutableList<AudioTrack>> get() = _tracks

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> get() = _loading

    private val _loadingMsg = MutableLiveData<String?>()
    val loadingMsg: LiveData<String?> get() = _loadingMsg

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var colorCounter = 0

    // Set by MainActivity after receiving intent extras
    var projectId: String = ""
        private set
    var projectName: String = ""
        private set

    var masterVolume: Float = 0.8f
    var applyFade: Boolean = false

    fun initProject(id: String, name: String) {
        if (projectId == id) return   // already initialised (e.g. config change)
        projectId   = id
        projectName = name
        masterVolume = ProjectStore.loadMasterVol(getApplication(), id)
        applyFade    = ProjectStore.loadFade(getApplication(), id)
        restoreFromPrefs()
    }

    /** Reload saved stubs → re-decode waveform peaks → emit. */
    private fun restoreFromPrefs() {
        val stubs = ProjectStore.loadTrackStubs(getApplication(), projectId)
        if (stubs.isEmpty()) return

        colorCounter = (stubs.maxOfOrNull { it.colorIndex } ?: -1) + 1

        _loading.postValue(true)
        _loadingMsg.postValue("Restoring ${stubs.size} track${if (stubs.size != 1) "s" else ""}…")
        viewModelScope.launch {
            val restored = mutableListOf<AudioTrack>()
            stubs.forEachIndexed { idx, stub ->
                _loadingMsg.postValue("Restoring ${idx + 1}/${stubs.size}: ${stub.displayName}")

                // Re-grant URI permission (needed after process restart)
                try {
                    getApplication<Application>().contentResolver
                        .takePersistableUriPermission(stub.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { /* might already be held or not grantable */ }

                // Re-decode peaks; keep existing trim/volume from persisted stub
                val decoded = try {
                    AudioDecoder.loadTrackMeta(getApplication(), stub.uri, stub.colorIndex)
                } catch (_: Exception) { null }

                if (decoded != null) {
                    restored.add(decoded.copy(
                        id            = stub.id,
                        trimStartMs   = stub.trimStartMs,
                        trimEndMs     = stub.trimEndMs,
                        volumePercent = stub.volumePercent
                    ))
                }
                // If URI is no longer accessible, silently skip
            }
            _tracks.postValue(restored)
            _loading.postValue(false)
            _loadingMsg.postValue(null)
        }
    }

    fun addTracks(uris: List<Uri>) {
        _loading.value = true
        _loadingMsg.value = "Preparing ${uris.size} track${if (uris.size != 1) "s" else ""}…"
        viewModelScope.launch {
            val newTracks = mutableListOf<AudioTrack>()
            uris.forEachIndexed { idx, uri ->
                _loadingMsg.postValue("Analysing track ${idx + 1} of ${uris.size}…")
                val track = AudioDecoder.loadTrackMeta(getApplication(), uri, colorCounter++)
                if (track != null) newTracks.add(track)
            }
            val current = _tracks.value ?: mutableListOf()
            current.addAll(newTracks)
            _tracks.postValue(current)
            _loading.postValue(false)
            _loadingMsg.postValue(null)
            persistNow()
        }
    }

    fun removeTrack(track: AudioTrack) {
        val list = _tracks.value ?: return
        list.remove(track)
        _tracks.value = list
        persistNow()
    }

    fun updateTrack(updated: AudioTrack) {
        val list = _tracks.value ?: return
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
            _tracks.value = list
            persistNow()
        }
    }

    fun reorderTrack(from: Int, to: Int) {
        val list = _tracks.value ?: return
        if (from < 0 || to < 0 || from >= list.size || to >= list.size) return
        val item = list.removeAt(from)
        list.add(to, item)
        _tracks.value = list
        persistNow()
    }

    fun updateTrim(track: AudioTrack, startMs: Long, endMs: Long) {
        val list = _tracks.value ?: return
        val idx = list.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(trimStartMs = startMs, trimEndMs = endMs)
            _tracks.postValue(list)
            persistNow()
        }
    }

    fun setTrackVolume(track: AudioTrack, percent: Int) {
        val list = _tracks.value ?: return
        val idx = list.indexOfFirst { it.id == track.id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(volumePercent = percent)
            persistNow()
        }
    }

    fun clearError() { _error.value = null }

    /** Save current state to ProjectStore and update the project metadata. */
    fun persistNow() {
        if (projectId.isBlank()) return
        val tracks = _tracks.value ?: return
        ProjectStore.saveTracks(getApplication(), projectId, tracks, masterVolume, applyFade)

        // Update project metadata (track count, lastModified)
        val projects = ProjectStore.loadProjects(getApplication())
        val idx = projects.indexOfFirst { it.id == projectId }
        if (idx >= 0) {
            val updated = projects[idx].copy(
                trackCount     = tracks.size,
                lastModifiedAt = System.currentTimeMillis(),
                masterVol      = masterVolume,
                fade           = applyFade
            )
            ProjectStore.upsertProject(getApplication(), updated)
        }
    }

    fun saveMasterVol(vol: Float) {
        masterVolume = vol
        persistNow()
    }

    fun saveFade(enabled: Boolean) {
        applyFade = enabled
        persistNow()
    }
}
