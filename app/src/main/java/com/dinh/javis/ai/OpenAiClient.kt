package com.dinh.javis.ai

import android.util.Log
import com.dinh.javis.data.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client kết nối API LLM tương thích OpenAI (OpenAI, DeepSeek, Gemini OpenAI-compatible, v.v.)
 */
class OpenAiClient(private val preferenceManager: PreferenceManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Gửi câu hỏi đến AI và nhận câu trả lời ngắn gọn
     */
    suspend fun askAi(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = preferenceManager.openAiApiKey.trim()
        var baseUrl = preferenceManager.openAiBaseUrl.trim()
        val model = preferenceManager.openAiModel.ifBlank { "gpt-4o-mini" }

        if (apiKey.isBlank()) {
            return@withContext "Tôi chưa hiểu câu lệnh này. Bạn vui lòng vào Cài đặt để thêm lệnh tùy chỉnh hoặc nhập API Key cho AI nhé!"
        }

        // Đảm bảo URL kết thúc đúng endpoint
        if (!baseUrl.endsWith("/chat/completions")) {
            baseUrl = if (baseUrl.endsWith("/")) {
                "${baseUrl}chat/completions"
            } else {
                "${baseUrl}/chat/completions"
            }
        }

        try {
            val jsonPayload = JSONObject().apply {
                put("model", model)
                put("temperature", 0.7)
                put("max_tokens", 150)

                val messagesArray = JSONArray().apply {
                    // System prompt định hình trợ lý JAVIS
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Bạn là JAVIS, trợ lý AI cá nhân tiếng Việt của anh Dinh trên điện thoại OPPO Find X8 Ultra. Hãy trả lời ngắn gọn, thông minh và súc tích trong 1 đến 2 câu tự nhiên để đọc bằng giọng nói.")
                    })
                    // User prompt
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                put("messages", messagesArray)
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "Lỗi phản hồi API (${response.code}): $bodyString")
                    return@withContext when (response.code) {
                        401 -> "Lỗi xác thực: API Key của bạn không hợp lệ hoặc đã hết hạn."
                        429 -> "Hạn ngạch API đã vượt giới hạn, vui lòng thử lại sau."
                        500, 502, 503 -> "Máy chủ AI đang bảo trì hoặc gặp sự cố tạm thời."
                        else -> "Không thể kết nối đến AI (Mã lỗi ${response.code})."
                    }
                }

                val jsonResponse = JSONObject(bodyString)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")
                    val answer = message?.optString("content")?.trim() ?: ""
                    if (answer.isNotEmpty()) {
                        return@withContext answer
                    }
                }

                return@withContext "Tôi đã nghe bạn nói nhưng AI không đưa ra phản hồi phù hợp."
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout khi gọi API AI", e)
            return@withContext "Yêu cầu đến máy chủ AI bị quá thời gian chờ, bạn thử lại sau nhé."
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Lỗi mất mạng khi gọi AI", e)
            return@withContext "Điện thoại dường như không có kết nối internet để hỏi AI."
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi ngoại lệ khi gọi AI", e)
            return@withContext "Đã xảy ra lỗi khi trao đổi với AI: ${e.localizedMessage}"
        }
    }

    companion object {
        private const val TAG = "OpenAiClient"
    }
}
