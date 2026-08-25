package org.cloud.sonic.android.model

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderName: String,
    val text: String,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = true,
    val isFile: Boolean = !filePath.isNullOrBlank(),
    val markdownLink: String? = null
)