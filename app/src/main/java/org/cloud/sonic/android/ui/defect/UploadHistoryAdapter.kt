package org.cloud.sonic.android.ui.defect

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.cloud.sonic.android.databinding.ItemUploadHistoryBinding
import org.cloud.sonic.android.repository.UploadedAttachment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UploadHistoryAdapter(
    private val onCopyMarkdown: (String) -> Unit
) : RecyclerView.Adapter<UploadHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<UploadedAttachment>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<UploadedAttachment>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUploadHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemUploadHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UploadedAttachment) {
            binding.tvFileName.text = item.fileName
            binding.tvTime.text = dateFormat.format(Date(item.timestamp))
            binding.tvMarkdown.text = item.markdown

            binding.btnCopyMarkdown.setOnClickListener {
                onCopyMarkdown(item.markdown)
            }
        }
    }
}