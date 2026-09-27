package com.dinh.javis.ai

import com.dinh.javis.ai.capabilities.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Quản lý ngữ cảnh hội thoại thu gọn (Compact Chat Context Memory) cho người dùng JAVIS.
 * Lưu trữ tối đa N lượt trò chuyện gần nhất (mặc định 6 tin nhắn) để AI nhớ bối cảnh hỏi đáp liền mạch,
 * đồng thời giới hạn độ dài mỗi tin nhắn để giữ token thu gọn, siêu nhanh và tiết kiệm.
 */
class ChatMemoryManager(private val maxMessages: Int = 6) {

    private val history = CopyOnWriteArrayList<ChatMessage>()

    /**
     * Thêm tin nhắn của người dùng hoặc AI vào bộ nhớ ngữ cảnh thu gọn
     */
    fun addMessage(role: String, content: String) {
        if (content.isBlank()) return
        val compactContent = if (content.length > 300) content.take(300) + "..." else content
        history.add(ChatMessage(role = role, content = compactContent))
        while (history.size > maxMessages) {
            history.removeAt(0)
        }
    }

    /**
     * Lấy danh sách lịch sử hội thoại thu gọn hiện tại
     */
    fun getCompactHistory(): List<ChatMessage> {
        return history.toList()
    }

    /**
     * Xóa sạch lịch sử ngữ cảnh khi người dùng muốn bắt đầu chủ đề mới
     */
    fun clearMemory() {
        history.clear()
    }
}
