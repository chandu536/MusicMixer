package com.musicmixer.app.ui.projects

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicmixer.app.R
import com.musicmixer.app.databinding.ActivityProjectsBinding
import com.musicmixer.app.model.Project
import com.musicmixer.app.ui.main.MainActivity

class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding
    private val viewModel: ProjectsViewModel by viewModels()
    private lateinit var adapter: ProjectsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProjectsAdapter(
            onClick  = { project -> openProject(project) },
            onRename = { project -> showRenameDialog(project) },
            onDelete = { project -> showDeleteConfirm(project) }
        )
        binding.rvProjects.layoutManager = LinearLayoutManager(this)
        binding.rvProjects.adapter = adapter

        viewModel.projects.observe(this) { projects ->
            adapter.submitList(projects.toList())
            binding.emptyState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
            binding.rvProjects.visibility  = if (projects.isEmpty()) View.GONE  else View.VISIBLE
        }

        binding.fabNewProject.setOnClickListener { showCreateDialog() }
    }

    override fun onResume() {
        super.onResume()
        // Refresh track counts whenever we return from MainActivity
        viewModel.reload()
    }

    private fun openProject(project: Project) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PROJECT_ID,   project.id)
            putExtra(MainActivity.EXTRA_PROJECT_NAME, project.name)
        }
        startActivity(intent)
    }

    private fun showCreateDialog() {
        val et = EditText(this).apply {
            hint = "Project name"
            setSingleLine()
        }
        AlertDialog.Builder(this, R.style.ThemeOverlay_App_Dialog)
            .setTitle("New Project")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val project = viewModel.createProject(et.text.toString())
                openProject(project)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(project: Project) {
        val et = EditText(this).apply {
            setText(project.name)
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this, R.style.ThemeOverlay_App_Dialog)
            .setTitle("Rename Project")
            .setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotBlank()) viewModel.renameProject(project.id, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirm(project: Project) {
        AlertDialog.Builder(this, R.style.ThemeOverlay_App_Dialog)
            .setTitle("Delete \"${project.name}\"?")
            .setMessage("All tracks in this project will be removed. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteProject(project.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
