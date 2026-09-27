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

    /**
     * Send prompt to AI and receive concise spoken response in Vietnamese
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
                content = "Bạn là JAVIS, trợ lý AI cá nhân tiếng Việt của anh Dinh trên điện thoại OPPO Find X8 Ultra. Hãy trả lời ngắn gọn, thông minh và súc tích trong 1 đến 2 câu tự nhiên để đọc bằng giọng nói. Không dùng ký hiệu định dạng markdown như **, #, *."
            )
            val userMessage = ChatMessage(role = "user", content = prompt)

            val response = client.chat(
                messages = listOf(systemMessage, userMessage),
                options = ModelOptions(temperature = 0.7, maxTokens = 150)
            )

            if (response.isNotBlank()) {
                return@withContext response
            }
            return@withContext "Tôi đã nghe bạn nói nhưng AI không đưa ra phản hồi phù hợp."
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

    companion object {
        private const val TAG = "OpenAiClient"
    }
}
