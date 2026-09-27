package com.dinh.javis.ai

import com.dinh.javis.ai.capabilities.ChatMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages compact conversation context memory for JAVIS app users.
 * Retains up to N recent conversation turns (default 6 messages max) for seamless multi-turn AI context,
 * while truncating message lengths to keep token footprint small, fast, and cost-effective.
 */
class ChatMemoryManager(private val maxMessages: Int = 6) {

    private val history = CopyOnWriteArrayList<ChatMessage>()

    /**
     * Add a user or assistant message to the compact context history
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
     * Retrieve the active list of compact conversation history messages
     */
    fun getCompactHistory(): List<ChatMessage> {
        return history.toList()
    }

    /**
     * Clear all conversation context memory history
     */
    fun clearMemory() {
        history.clear()
    }
}
