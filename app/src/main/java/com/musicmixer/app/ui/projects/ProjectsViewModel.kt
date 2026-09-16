package com.musicmixer.app.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.musicmixer.app.model.Project
import com.musicmixer.app.utils.ProjectStore

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {

    private val _projects = MutableLiveData<MutableList<Project>>(mutableListOf())
    val projects: LiveData<MutableList<Project>> get() = _projects

    init { reload() }

    fun reload() {
        _projects.value = ProjectStore.loadProjects(getApplication())
    }

    fun createProject(name: String): Project {
        val existing = _projects.value ?: mutableListOf()
        val index = existing.size + 1
        val p = Project(name = name.ifBlank { "Mix $index" })
        ProjectStore.upsertProject(getApplication(), p)
        reload()
        return p
    }

    fun renameProject(projectId: String, newName: String) {
        val list = _projects.value ?: return
        val idx = list.indexOfFirst { it.id == projectId }
        if (idx < 0) return
        val updated = list[idx].copy(name = newName, lastModifiedAt = System.currentTimeMillis())
        ProjectStore.upsertProject(getApplication(), updated)
        reload()
    }

    fun deleteProject(projectId: String) {
        ProjectStore.deleteProject(getApplication(), projectId)
        reload()
    }
}
