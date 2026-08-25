package org.cloud.sonic.android.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cloud.sonic.android.model.ChatMessage

class ChatRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("sonic_link_chat", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    companion object {
        private const val KEY_MESSAGES = "chat_messages_json"
    }

    init {
        loadMessages()
    }

    private fun loadMessages() {
        val json = prefs.getString(KEY_MESSAGES, null)
        val list: List<ChatMessage> = if (json != null) {
            runCatching {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                gson.fromJson<List<ChatMessage>>(json, type)
            }.getOrDefault(emptyList())
        } else {
            listOf(
                ChatMessage(
                    senderName = "SonicLink 助手",
                    text = "这里用于保存本机协同备注。手机附件上传请使用上方扫码绑定与媒体页面。",
                    isMine = false
                )
            )
        }
        _messagesFlow.value = list
    }

    fun addMessage(message: ChatMessage) {
        val current = _messagesFlow.value.toMutableList()
        current.add(message)
        _messagesFlow.value = current
        saveMessages(current)
    }

    fun clearMessages() {
        _messagesFlow.value = emptyList()
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    private fun saveMessages(list: List<ChatMessage>) {
        val trimmed = list.takeLast(100)
        prefs.edit().putString(KEY_MESSAGES, gson.toJson(trimmed)).apply()
    }
}
