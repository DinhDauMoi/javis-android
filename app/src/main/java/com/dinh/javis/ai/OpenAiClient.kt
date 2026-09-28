package com.dinh.javis.ai

import android.util.Log
import com.dinh.javis.ai.capabilities.*
import com.dinh.javis.data.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy wrapper client connecting to OpenAI-compatible API endpoints.
 * Delegates to OpenAiCompatibleClient for unified model resolution, mistral role fixes, and multi-header auth.
 */
class OpenAiClient(private val preferenceManager: PreferenceManager) {

    private val chatMemory = ChatMemoryManager()

    /**
     * Clear active compact chat memory history
     */
    fun clearChatMemory() {
        chatMemory.clearMemory()
    }

    /**
     * Send prompt to AI and receive concise spoken response in Vietnamese with compact conversation context
     */
    suspend fun askAi(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = preferenceManager.openAiApiKey.trim()
        val baseUrl = preferenceManager.openAiBaseUrl.trim()
        val model = preferenceManager.openAiModel.ifBlank { "gpt-4o-mini" }

        if (apiKey.isBlank()) {
            return@withContext "Tôi chưa hiểu câu lệnh này. Bạn vui lòng vào Cài đặt để thêm lệnh tùy chỉnh hoặc nhập API Key cho AI nhé!"
        }

        try {
            val client = OpenAiCompatibleClient(
                baseUrl = baseUrl,
                apiKey = apiKey,
                defaultChatModel = model,
                defaultVisionModel = model,
                defaultPlanningModel = model
            )

            val systemMessage = ChatMessage(
                role = "system",
                content = "Bạn là JAVIS, trợ lý AI cá nhân tiếng Việt trên Android. JAVIS có các dịch vụ như Trợ năng, Quan sát màn hình (ScreenCaptureService), tìm sản phẩm Shopee, điều khiển TikTok/YouTube, âm lượng. Tuyệt đối KHÔNG gợi ý phần mềm bên thứ 3 như TeamViewer, AnyDesk hay AirDroid khi người dùng hỏi về màn hình hoặc tính năng của JAVIS. Trả lời ngắn gọn 1-2 câu, không dùng markdown."
            )

            val compactHistory = chatMemory.getCompactHistory()
            val fullMessages = mutableListOf<ChatMessage>()
            fullMessages.add(systemMessage)
            fullMessages.addAll(compactHistory)
            fullMessages.add(ChatMessage(role = "user", content = prompt))

            val response = client.chat(
                messages = fullMessages,
                options = ModelOptions(temperature = 0.7, maxTokens = 150)
            )

            val trimmed = response.trim()
            val hasMeaningfulContent = trimmed.any { it.isLetterOrDigit() }
            if (hasMeaningfulContent) {
                chatMemory.addMessage("user", prompt)
                chatMemory.addMessage("assistant", trimmed)
                return@withContext trimmed
            }
            return@withContext "Tôi đã nghe bạn nói nhưng máy chủ AI phản hồi không hợp lệ."
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi ngoại lệ khi gọi AI qua OpenAiCompatibleClient: ${e.message}", e)
            val msg = e.message ?: ""
            return@withContext when {
                msg.contains("401", ignoreCase = true) -> "Lỗi xác thực: API Key của bạn không hợp lệ hoặc đã hết hạn."
                msg.contains("429", ignoreCase = true) -> "Hạn ngạch API đã vượt giới hạn, vui lòng thử lại sau."
                msg.contains("SocketTimeout", ignoreCase = true) -> "Yêu cầu đến máy chủ AI bị quá thời gian chờ, bạn thử lại sau nhé."
                msg.contains("UnknownHost", ignoreCase = true) -> "Điện thoại dường như không có kết nối internet để hỏi AI."
                else -> "Đã xảy ra lỗi khi trao đổi với AI: ${e.localizedMessage}"
            }
        }
    }

    /**
     * Stateless intent classification for voice-command recovery.
     *
     * Sends ONLY the [systemPrompt] + the single [transcript] message — no chat
     * history is appended, so unrelated conversation content is never sent to
     * the provider for this call (privacy/correctness boundary per plan review 4.4).
     *
     * Returns the raw model text (expected JSON). Propagates exceptions to the
     * caller for explicit retry/fallback handling.
     */
    suspend fun classifyIntent(systemPrompt: String, transcript: String): String = withContext(Dispatchers.IO) {
        val apiKey = preferenceManager.openAiApiKey.trim()
        val baseUrl = preferenceManager.openAiBaseUrl.trim()
        val model = preferenceManager.openAiModel.ifBlank { "gpt-4o-mini" }

        if (apiKey.isBlank()) {
            throw java.lang.IllegalStateException("API key not configured")
        }

        val client = OpenAiCompatibleClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultChatModel = model,
            defaultVisionModel = model,
            defaultPlanningModel = model
        )
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = transcript)
        )
        return@withContext client.chat(
            messages = messages,
            options = ModelOptions(temperature = 0.3, maxTokens = 256)
        )
    }

    companion object {
        private const val TAG = "OpenAiClient"
    }
}
