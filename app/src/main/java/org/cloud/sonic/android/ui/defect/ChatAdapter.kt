package org.cloud.sonic.android.ui.defect

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ItemChatMessageBinding
import org.cloud.sonic.android.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val items = mutableListOf<ChatMessage>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<ChatMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            val context = binding.root.context
            val timeStr = dateFormat.format(Date(item.timestamp))

            if (item.isMine) {
                binding.layoutContainer.gravity = Gravity.END
                binding.tvSender.text = "我 $timeStr"
                binding.cardBubble.setCardBackgroundColor(ContextCompat.getColor(context, R.color.brand_primary))
                binding.tvMessage.setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                binding.layoutContainer.gravity = Gravity.START
                binding.tvSender.text = "${item.senderName} $timeStr"
                binding.cardBubble.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_card_secondary))
                binding.tvMessage.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }

            binding.tvMessage.text = item.text
        }
    }
}