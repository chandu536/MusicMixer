package com.musicmixer.app.utils

import android.content.Context
import android.net.Uri
import com.musicmixer.app.model.AudioTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the track list (URIs + trim state + volume + order) to SharedPreferences
 * so it survives app restarts.
 *
 * Waveform peaks are NOT persisted (re-decoded on restore) — too large for prefs.
 */
object TrackPersistence {

    private const val PREFS_NAME = "musicmixer_tracks"
    private const val KEY_TRACKS  = "tracks_json"
    private const val KEY_MASTER_VOL = "master_vol"
    private const val KEY_FADE   = "fade_enabled"

    fun save(context: Context, tracks: List<AudioTrack>, masterVol: Float, fade: Boolean) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id",         t.id)
                put("uri",        t.uri.toString())
                put("name",       t.displayName)
                put("durationMs", t.durationMs)
                put("trimStart",  t.trimStartMs)
                put("trimEnd",    t.trimEndMs)
                put("volume",     t.volumePercent)
                put("colorIndex", t.colorIndex)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRACKS, arr.toString())
            .putFloat(KEY_MASTER_VOL, masterVol)
            .putBoolean(KEY_FADE, fade)
            .apply()
    }

    /** Returns list of stub tracks (no waveformPeaks — caller must re-decode). */
    fun loadStubs(context: Context): List<AudioTrack> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRACKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AudioTrack(
                    id          = o.getString("id"),
                    uri         = Uri.parse(o.getString("uri")),
                    displayName = o.getString("name"),
                    durationMs  = o.getLong("durationMs"),
                    trimStartMs = o.getLong("trimStart"),
                    trimEndMs   = o.getLong("trimEnd"),
                    volumePercent = o.getInt("volume"),
                    waveformPeaks = FloatArray(0),   // re-decoded on restore
                    colorIndex  = o.getInt("colorIndex")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun loadMasterVol(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_MASTER_VOL, 0.8f)

    fun loadFade(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FADE, false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
