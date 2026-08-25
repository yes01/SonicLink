package org.cloud.sonic.android.ui.images

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.cloud.sonic.android.databinding.ItemMediaGridBinding
import org.cloud.sonic.android.model.MediaItem

class ImageAdapter(
    private val onItemClick: (MediaItem) -> Unit,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : ListAdapter<MediaItem, ImageAdapter.ViewHolder>(MediaDiffCallback()) {

    var isSelectionMode: Boolean = false
        private set

    fun getSelectedItems(): List<MediaItem> = currentList.filter { it.isSelected }

    fun clearSelection() {
        isSelectionMode = false
        currentList.forEach { it.isSelected = false }
        notifyItemRangeChanged(0, currentList.size)
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemMediaGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            Glide.with(binding.ivThumbnail)
                .load(item.uri)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivThumbnail)

            if (item.isScreenshot) {
                binding.tvTag.visibility = View.VISIBLE
                binding.tvTag.text = "截屏"
            } else {
                binding.tvTag.visibility = View.GONE
            }

            binding.tvDuration.visibility = View.GONE

            if (isSelectionMode) {
                binding.ivSelect.visibility = View.VISIBLE
                binding.ivSelect.alpha = if (item.isSelected) 1.0f else 0.4f
            } else {
                binding.ivSelect.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    item.isSelected = !item.isSelected
                    notifyItemChanged(bindingAdapterPosition)
                    val count = getSelectedItems().size
                    if (count == 0) isSelectionMode = false
                    onSelectionChanged(count)
                } else {
                    onItemClick(item)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    item.isSelected = true
                    notifyItemRangeChanged(0, currentList.size)
                    onSelectionChanged(getSelectedItems().size)
                }
                true
            }
        }
    }

    private class MediaDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem.isSelected == newItem.isSelected && oldItem.dateModified == newItem.dateModified && oldItem.size == newItem.size
        }
    }
}