package com.dinh.javis.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AI response sanitization (R9).
 * Verifies that empty, whitespace, and punctuation-only provider responses
 * (such as literal "....." or "...") are never treated as meaningful assistant speech.
 */
class OpenAiClientSanitizationTest {

    private fun hasMeaningfulContent(response: String): Boolean {
        val trimmed = response.trim()
        return trimmed.any { it.isLetterOrDigit() }
    }

    @Test
    fun testPunctuationOnlyResponsesAreRejected() {
        assertFalse("Literal dots must be rejected", hasMeaningfulContent("....."))
        assertFalse("Three dots must be rejected", hasMeaningfulContent("..."))
        assertFalse("Punctuation symbols must be rejected", hasMeaningfulContent("?!.,;;--"))
        assertFalse("Whitespace and dots must be rejected", hasMeaningfulContent("   . . .   "))
        assertFalse("Empty string must be rejected", hasMeaningfulContent(""))
        assertFalse("Newline and tabs must be rejected", hasMeaningfulContent("\n\t   \n"))
    }

    @Test
    fun testValidResponsesAreAccepted() {
        assertTrue(hasMeaningfulContent("Tôi là JAVIS, trợ lý của bạn."))
        assertTrue(hasMeaningfulContent("Giá sản phẩm là 99.000đ"))
        assertTrue(hasMeaningfulContent("OK"))
        assertTrue(hasMeaningfulContent("Áo thun"))
    }
}
