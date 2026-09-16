package com.musicmixer.app.utils

import android.content.Context
import android.net.Uri
import com.musicmixer.app.model.AudioTrack
import com.musicmixer.app.model.Project
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages all projects + their track lists in SharedPreferences.
 *
 * Layout in SharedPreferences (file "musicmixer_projects"):
 *   "project_list"   → JSONArray of project metadata objects
 *   "tracks_<id>"    → JSONArray of track objects for that project
 *   "vol_<id>"       → Float master volume for that project
 *   "fade_<id>"      → Boolean fade for that project
 */
object ProjectStore {

    private const val PREFS_NAME = "musicmixer_projects"
    private const val KEY_PROJECT_LIST = "project_list"

    // ── Project list ─────────────────────────────────────────────────────────

    fun saveProjects(context: Context, projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id",          p.id)
                put("name",        p.name)
                put("createdAt",   p.createdAt)
                put("modifiedAt",  p.lastModifiedAt)
                put("trackCount",  p.trackCount)
                put("masterVol",   p.masterVol.toDouble())
                put("fade",        p.fade)
            })
        }
        prefs(context).edit().putString(KEY_PROJECT_LIST, arr.toString()).apply()
    }

    fun loadProjects(context: Context): MutableList<Project> {
        val json = prefs(context).getString(KEY_PROJECT_LIST, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapTo(mutableListOf()) { i ->
                val o = arr.getJSONObject(i)
                Project(
                    id             = o.getString("id"),
                    name           = o.getString("name"),
                    createdAt      = o.getLong("createdAt"),
                    lastModifiedAt = o.getLong("modifiedAt"),
                    trackCount     = o.getInt("trackCount"),
                    masterVol      = o.getDouble("masterVol").toFloat(),
                    fade           = o.getBoolean("fade")
                )
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun upsertProject(context: Context, project: Project) {
        val list = loadProjects(context)
        val idx = list.indexOfFirst { it.id == project.id }
        if (idx >= 0) list[idx] = project else list.add(0, project)
        saveProjects(context, list)
    }

    fun deleteProject(context: Context, projectId: String) {
        val list = loadProjects(context).filter { it.id != projectId }
        saveProjects(context, list)
        // Also wipe track data for this project
        prefs(context).edit()
            .remove("tracks_$projectId")
            .remove("vol_$projectId")
            .remove("fade_$projectId")
            .apply()
    }

    // ── Tracks for a specific project ────────────────────────────────────────

    fun saveTracks(context: Context, projectId: String,
                   tracks: List<AudioTrack>, masterVol: Float, fade: Boolean) {
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
        prefs(context).edit()
            .putString("tracks_$projectId", arr.toString())
            .putFloat("vol_$projectId", masterVol)
            .putBoolean("fade_$projectId", fade)
            .apply()
    }

    fun loadTrackStubs(context: Context, projectId: String): List<AudioTrack> {
        val json = prefs(context).getString("tracks_$projectId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AudioTrack(
                    id            = o.getString("id"),
                    uri           = Uri.parse(o.getString("uri")),
                    displayName   = o.getString("name"),
                    durationMs    = o.getLong("durationMs"),
                    trimStartMs   = o.getLong("trimStart"),
                    trimEndMs     = o.getLong("trimEnd"),
                    volumePercent = o.getInt("volume"),
                    waveformPeaks = FloatArray(0),
                    colorIndex    = o.getInt("colorIndex")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun loadMasterVol(context: Context, projectId: String) =
        prefs(context).getFloat("vol_$projectId", 0.8f)

    fun loadFade(context: Context, projectId: String) =
        prefs(context).getBoolean("fade_$projectId", false)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
