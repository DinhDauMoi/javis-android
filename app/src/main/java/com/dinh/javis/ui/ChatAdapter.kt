package com.dinh.javis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dinh.javis.data.Message
import com.dinh.javis.databinding.ItemChatMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter hiển thị lịch sử hội thoại giữa Người dùng và Trợ lý JAVIS
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<Message>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class ChatViewHolder(private val binding: ItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val timeString = timeFormat.format(Date(message.timestamp))

            if (message.isUser) {
                // Tin nhắn của Người dùng
                binding.layoutUserMsg.visibility = View.VISIBLE
                binding.layoutBotMsg.visibility = View.GONE
                binding.tvUserText.text = message.text
                binding.tvUserTime.text = timeString
            } else {
                // Tin nhắn phản hồi của JAVIS
                binding.layoutUserMsg.visibility = View.GONE
                binding.layoutBotMsg.visibility = View.VISIBLE
                binding.tvBotText.text = message.text
                binding.tvBotTime.text = timeString

                if (!message.actionTag.isNullOrBlank()) {
                    binding.tvBotBadge.visibility = View.VISIBLE
                    binding.tvBotBadge.text = message.actionTag
                } else {
                    binding.tvBotBadge.visibility = View.GONE
                }
            }
        }
    }
}
