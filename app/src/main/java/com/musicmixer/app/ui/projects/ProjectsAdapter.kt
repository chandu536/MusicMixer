package com.musicmixer.app.ui.projects

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicmixer.app.databinding.ItemProjectBinding
import com.musicmixer.app.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectsAdapter(
    private val onClick: (Project) -> Unit,
    private val onRename: (Project) -> Unit,
    private val onDelete: (Project) -> Unit
) : ListAdapter<Project, ProjectsAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProjectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val project = getItem(position)
        val b = holder.binding

        b.tvProjectName.text = project.name
        b.tvTrackCount.text = "${project.trackCount} track${if (project.trackCount != 1) "s" else ""}"

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        b.tvLastModified.text = fmt.format(Date(project.lastModifiedAt))

        b.root.setOnClickListener { onClick(project) }
        b.btnRename.setOnClickListener { onRename(project) }
        b.btnDelete.setOnClickListener { onDelete(project) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Project>() {
            override fun areItemsTheSame(a: Project, b: Project) = a.id == b.id
            override fun areContentsTheSame(a: Project, b: Project) =
                a.name == b.name && a.trackCount == b.trackCount && a.lastModifiedAt == b.lastModifiedAt
        }
    }
}
