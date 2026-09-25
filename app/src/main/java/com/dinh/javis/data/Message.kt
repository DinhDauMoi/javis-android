package com.dinh.javis.data

/**
 * Model tin nhắn / log hội thoại trong giao diện JAVIS
 */
data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val actionTag: String? = null // Ví dụ: "MỞ APP", "LƯỚT LÊN", "AI CHAT", "BÁO THỨC"
)
