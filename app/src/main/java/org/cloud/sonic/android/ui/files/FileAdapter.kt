package org.cloud.sonic.android.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ItemFileRowBinding
import org.cloud.sonic.android.model.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onUploadClick: (FileItem) -> Unit
) : ListAdapter<FileItem, FileAdapter.ViewHolder>(FileDiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFileRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FileItem) {
            binding.tvFileName.text = item.name

            val dateStr = dateFormat.format(Date(item.lastModified))
            if (item.isDirectory) {
                binding.ivIcon.setImageResource(R.drawable.ic_folder)
                binding.tvFileMeta.text = "${item.childCount} 项 · $dateStr"
                binding.btnUpload.visibility = View.GONE
            } else {
                binding.ivIcon.setImageResource(R.drawable.ic_file)
                binding.tvFileMeta.text = "${formatSize(item.size)} · $dateStr"
                binding.btnUpload.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnUpload.setOnClickListener { onUploadClick(item) }
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    private class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.lastModified == newItem.lastModified && oldItem.size == newItem.size && oldItem.name == newItem.name
        }
    }
}