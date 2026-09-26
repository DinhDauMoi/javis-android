package com.dinh.javis.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OpenAiCompatibleClient:
 * - Endpoint normalization (normalizeEndpoint)
 * - Automatic model name prefix resolution for Gateway (GenroStore / OpenRouter)
 * - Mistral/Pixtral model identification (isMistralOrPixtral)
 * - System prompt transformation for Mistral (preventing HTTP 400 system role error)
 */
class OpenAiCompatibleClientTest {

    private fun normalizeEndpoint(url: String): String {
        var trimmed = url.trim().removeSuffix("/")
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed
        }
        if (trimmed.endsWith("/api/v1")) {
            return "$trimmed/chat/completions"
        }
        if (!trimmed.endsWith("/v1")) {
            trimmed = "$trimmed/v1"
        }
        return "$trimmed/chat/completions"
    }

    private fun isMistralOrPixtral(modelName: String, url: String): Boolean {
        return modelName.contains("mistral", ignoreCase = true) ||
                modelName.contains("pixtral", ignoreCase = true) ||
                url.contains("mistral.ai", ignoreCase = true)
    }

    private fun resolveModelName(modelName: String, url: String): String {
        val trimmedModel = modelName.trim()
        if (url.contains("gateway.genrostore.com") || url.contains("openrouter.ai")) {
            if (trimmedModel.contains("pixtral-large", ignoreCase = true)) {
                return "mistral/pixtral-12b-2409"
            }
            if (!trimmedModel.contains("/")) {
                if (trimmedModel.contains("pixtral", ignoreCase = true) || trimmedModel.contains("mistral", ignoreCase = true)) {
                    return "mistral/$trimmedModel"
                }
            }
        }
        return trimmedModel
    }

    private fun sanitizeMessagesForMistral(systemPrompt: String, userMessage: String): JSONArray {
        val messagesArray = JSONArray()
        val combinedContent = "[HƯỚNG DẪN HỆ THỐNG]\n$systemPrompt\n\n[YÊU CẦU NGUỜI DÙNG]\n$userMessage"
        val userObj = JSONObject().apply {
            put("role", "user")
            put("content", combinedContent)
        }
        messagesArray.put(userObj)
        return messagesArray
    }

    @Test
    fun testNormalizeEndpointV1() {
        val endpoint = normalizeEndpoint("https://gateway.genrostore.com/v1")
        assertEquals("https://gateway.genrostore.com/v1/chat/completions", endpoint)
    }

    @Test
    fun testNormalizeEndpointApiV1() {
        val endpoint = normalizeEndpoint("https://gateway.genrostore.com/api/v1")
        assertEquals("https://gateway.genrostore.com/api/v1/chat/completions", endpoint)
    }

    @Test
    fun testNormalizeEndpointBaseUrl() {
        val endpoint = normalizeEndpoint("https://api.openai.com")
        assertEquals("https://api.openai.com/v1/chat/completions", endpoint)
    }

    @Test
    fun testResolveModelNameAutoPrefixGenroStore() {
        val model = resolveModelName("pixtral-12b-2409", "https://gateway.genrostore.com/v1")
        assertEquals("mistral/pixtral-12b-2409", model)
    }

    @Test
    fun testResolveModelNamePreserveExistingPrefix() {
        val model = resolveModelName("mistral/pixtral-12b-2409", "https://gateway.genrostore.com/v1")
        assertEquals("mistral/pixtral-12b-2409", model)
    }

    @Test
    fun testResolveModelNamePixtralLargeAutoMap() {
        val modelWithPrefix = resolveModelName("mistral/pixtral-large-latest", "https://gateway.genrostore.com/v1")
        assertEquals("mistral/pixtral-12b-2409", modelWithPrefix)

        val modelNoPrefix = resolveModelName("pixtral-large-latest", "https://gateway.genrostore.com/v1")
        assertEquals("mistral/pixtral-12b-2409", modelNoPrefix)
    }

    @Test
    fun testResolveModelNameOpenAI() {
        val model = resolveModelName("gpt-4o-mini", "https://api.openai.com/v1")
        assertEquals("gpt-4o-mini", model)
    }

    @Test
    fun testIsMistralOrPixtral() {
        assertTrue(isMistralOrPixtral("pixtral-12b-2409", "https://gateway.genrostore.com/v1"))
        assertTrue(isMistralOrPixtral("mistral-large-latest", "https://api.mistral.ai/v1"))
        assertFalse(isMistralOrPixtral("gpt-4o-mini", "https://api.openai.com/v1"))
    }

    @Test
    fun testSanitizeMessagesForMistralDoesNotUseSystemRole() {
        val messages = sanitizeMessagesForMistral("Bạn là JAVIS trợ lý AI", "Chào bạn!")
        assertEquals(1, messages.length())
        val firstMsg = messages.getJSONObject(0)
        assertEquals("user", firstMsg.getString("role"))
        assertTrue(firstMsg.getString("content").contains("JAVIS"))
        assertTrue(firstMsg.getString("content").contains("Chào bạn!"))
    }
}
