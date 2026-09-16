package com.musicmixer.app.model

/**
 * Named project — each project holds its own track list, master volume, fade toggle.
 * Tracks are NOT embedded here; they are stored separately in ProjectStore keyed by projectId.
 */
data class Project(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    var lastModifiedAt: Long = System.currentTimeMillis(),
    var trackCount: Int = 0,
    var masterVol: Float = 0.8f,
    var fade: Boolean = false
) {
    companion object {
        fun untitled(index: Int) = Project(name = "Mix $index")
    }
}
