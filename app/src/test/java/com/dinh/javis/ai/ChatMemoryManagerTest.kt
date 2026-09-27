package com.dinh.javis.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryManagerTest {

    @Test
    fun testAddMessageAndMemoryLimit() {
        val memory = ChatMemoryManager(maxMessages = 4)
        memory.addMessage("user", "Q1")
        memory.addMessage("assistant", "A1")
        memory.addMessage("user", "Q2")
        memory.addMessage("assistant", "A2")
        memory.addMessage("user", "Q3")
        memory.addMessage("assistant", "A3")

        val history = memory.getCompactHistory()
        assertEquals(4, history.size)
        assertEquals("Q2", history[0].content)
        assertEquals("A2", history[1].content)
        assertEquals("Q3", history[2].content)
        assertEquals("A3", history[3].content)
    }

    @Test
    fun testCompactContentTruncation() {
        val memory = ChatMemoryManager(maxMessages = 2)
        val longText = "A".repeat(400)
        memory.addMessage("user", longText)

        val history = memory.getCompactHistory()
        assertEquals(1, history.size)
        assertTrue(history[0].content.endsWith("..."))
        assertEquals(303, history[0].content.length) // 300 chars + "..."
    }

    @Test
    fun testClearMemory() {
        val memory = ChatMemoryManager()
        memory.addMessage("user", "Hello")
        memory.clearMemory()
        assertTrue(memory.getCompactHistory().isEmpty())
    }
}
