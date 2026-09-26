package com.dinh.javis.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import com.dinh.javis.ai.capabilities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Client giao tiếp với bất kỳ AI Gateway nào hỗ trợ giao thức OpenAI API
 * (OpenAI GPT-4o, Groq Vision, OpenRouter, DeepSeek, Ollama, vLLM nội bộ).
 */
class OpenAiCompatibleClient(
    private var baseUrl: String,
    private var apiKey: String,
    private var defaultChatModel: String = "gpt-4o-mini",
    private var defaultVisionModel: String = "gpt-4o-mini",
    private var defaultPlanningModel: String = "gpt-4o-mini"
) : ChatCapability, VisionCapability, PlanningCapability {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    fun updateConfig(
        baseUrl: String,
        apiKey: String,
        chatModel: String,
        visionModel: String,
        planningModel: String
    ) {
        this.baseUrl = baseUrl.trim()
        this.apiKey = apiKey.trim()
        this.defaultChatModel = chatModel.trim()
        this.defaultVisionModel = visionModel.trim()
        this.defaultPlanningModel = planningModel.trim()
    }

    private fun normalizeEndpoint(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else if (trimmed.endsWith("/")) {
            "${trimmed}chat/completions"
        } else {
            "${trimmed}/chat/completions"
        }
    }

    override suspend fun chat(messages: List<ChatMessage>, options: ModelOptions): String = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(baseUrl)
        val jsonPayload = JSONObject().apply {
            put("model", defaultChatModel)
            put("temperature", options.temperature)
            put("max_tokens", options.maxTokens)

            val messagesArray = JSONArray()
            for (msg in messages) {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            put("messages", messagesArray)
        }

        executeRequest(endpoint, jsonPayload)
    }

    override suspend fun analyzeScreen(
        prompt: String,
        bitmap: Bitmap,
        nodeContext: String?,
        options: ModelOptions
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(baseUrl)
        val base64Image = encodeBitmapToBase64Jpeg(bitmap)

        val jsonPayload = JSONObject().apply {
            put("model", defaultVisionModel)
            put("temperature", options.temperature)
            put("max_tokens", max(options.maxTokens, 400))

            val messagesArray = JSONArray().apply {
                // System message
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Bạn là JAVIS Vision Assistant. Bạn nhận hình ảnh màn hình điện thoại và bối cảnh cây giao diện. Hãy phân tích các phần tử hiển thị trên màn hình và trả lời bằng tiếng Việt ngắn gọn, chính xác.")
                })

                // User message with text and image_url
                val userContentArray = JSONArray().apply {
                    val fullTextPrompt = buildString {
                        append(prompt)
                        if (!nodeContext.isNullOrBlank()) {
                            append("\n\n[Bối cảnh giao diện Accessibility]:\n")
                            append(nodeContext)
                        }
                    }
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", fullTextPrompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                }

                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContentArray)
                })
            }
            put("messages", messagesArray)
        }

        try {
            val responseText = executeRequest(endpoint, jsonPayload)
            VisionAnalysisResult(description = responseText, rawResponse = responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích thị giác màn hình", e)
            VisionAnalysisResult(description = "Không thể phân tích màn hình: ${e.message}", rawResponse = "")
        }
    }

    override suspend fun planNextAction(
        taskGoal: String,
        screenSummary: ScreenObservation,
        history: List<ActionSummary>,
        options: ModelOptions
    ): ActionPlanResult = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(baseUrl)

        val systemPrompt = """
            Bạn là Behavior Agent điều khiển điện thoại thông minh JAVIS.
            Nhiệm vụ của bạn là đưa ra hành động tiếp theo để đạt mục tiêu của người dùng.
            BẮT BUỘC chỉ trả về định dạng JSON thuần túy (không thêm lời dẫn, không bọc markdown ```json):
            {
              "thought": "Giải thích ngắn gọn lý do chọn hành động (tiếng Việt)",
              "action": "CLICK" | "SCROLL" | "TYPE" | "WAIT" | "TERMINATE",
              "params": {
                "x": 540,
                "y": 1200,
                "text": "nội dung nếu TYPE",
                "direction": "UP" | "DOWN" | "LEFT" | "RIGHT"
              },
              "isGoalComplete": false
            }
            Ghi chú:
            - CLICK: Bấm vào tọa độ (x, y) trên màn hình.
            - SCROLL: Cuộn trang theo hướng direction (UP/DOWN).
            - TYPE: Nhập text vào ô nhập liệu đang focus hoặc vừa click.
            - WAIT: Chờ màn hình tải dữ liệu.
            - TERMINATE: Đã hoàn thành hoặc không thể tiếp tục, đặt isGoalComplete = true.
        """.trimIndent()

        val userPromptBuilder = StringBuilder().apply {
            append("Mục tiêu tác vụ: ").append(taskGoal).append("\n")
            append("Ứng dụng hiện tại: ").append(screenSummary.currentPackage ?: "Chưa rõ").append("\n")
            append("Kích thước màn hình: ${screenSummary.screenshotWidth}x${screenSummary.screenshotHeight}\n\n")

            if (!screenSummary.nodeHierarchyText.isNullOrBlank()) {
                append("[Các phần tử giao diện phát hiện được]:\n")
                append(screenSummary.nodeHierarchyText).append("\n\n")
            }

            if (!screenSummary.ocrText.isNullOrBlank()) {
                append("[Văn bản OCR đọc được trên màn hình]:\n")
                append(screenSummary.ocrText).append("\n\n")
            }

            if (history.isNotEmpty()) {
                append("[Lịch sử các bước đã thực hiện]:\n")
                for (step in history) {
                    append("- Bước ${step.stepIndex}: ${step.action} (${step.details}) -> Thành công: ${step.success}\n")
                }
                append("\n")
            }

            append("Hãy quyết định bước tiếp theo dưới dạng JSON:")
        }

        val jsonPayload = JSONObject().apply {
            put("model", defaultPlanningModel)
            put("temperature", 0.2) // Nhiệt độ thấp cho JSON deterministic
            put("max_tokens", 350)

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPromptBuilder.toString())
                })
            }
            put("messages", messagesArray)
        }

        try {
            val responseText = executeRequest(endpoint, jsonPayload)
            parseActionPlanJson(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lập kế hoạch hành vi ReAct", e)
            ActionPlanResult(
                thought = "Lỗi khi gọi mô hình lập kế hoạch: ${e.message}",
                action = "TERMINATE",
                isGoalComplete = true,
                rawJson = ""
            )
        }
    }

    private fun parseActionPlanJson(rawResponse: String): ActionPlanResult {
        var cleanJson = rawResponse.trim()
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.removePrefix("```json")
        }
        if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.removePrefix("```")
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.removeSuffix("```")
        }
        cleanJson = cleanJson.trim()

        return try {
            val obj = JSONObject(cleanJson)
            val thought = obj.optString("thought", "Không có giải thích")
            val action = obj.optString("action", "TERMINATE").uppercase()
            val isGoalComplete = obj.optBoolean("isGoalComplete", action == "TERMINATE")

            val paramsObj = obj.optJSONObject("params")
            val params = if (paramsObj != null) {
                ActionParams(
                    x = paramsObj.optDouble("x", 0.0).toFloat(),
                    y = paramsObj.optDouble("y", 0.0).toFloat(),
                    text = paramsObj.optString("text", ""),
                    direction = paramsObj.optString("direction", "DOWN")
                )
            } else {
                ActionParams()
            }

            ActionPlanResult(
                thought = thought,
                action = action,
                params = params,
                isGoalComplete = isGoalComplete,
                rawJson = cleanJson
            )
        } catch (e: Exception) {
            Log.w(TAG, "Không thể phân tích JSON trả về từ Planner: $rawResponse")
            ActionPlanResult(
                thought = "Mô hình trả về định dạng không khớp: $rawResponse",
                action = "TERMINATE",
                isGoalComplete = true,
                rawJson = rawResponse
            )
        }
    }

    private fun executeRequest(endpoint: String, payload: JSONObject): String {
        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    401 -> "Lỗi 401: API Key không hợp lệ hoặc chưa được ủy quyền."
                    429 -> "Lỗi 429: Vượt quá giới hạn gọi API (Rate limit)."
                    500, 502, 503 -> "Lỗi ${response.code}: Máy chủ AI đang bảo trì hoặc quá tải."
                    else -> "Lỗi kết nối AI (${response.code}): $bodyString"
                }
                throw RuntimeException(errorMsg)
            }

            val jsonResponse = JSONObject(bodyString)
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                return message?.optString("content")?.trim() ?: ""
            }
            throw RuntimeException("API không trả lời nội dung phù hợp")
        }
    }

    /**
     * Nén ảnh Bitmap trực tiếp trong bộ nhớ RAM (JPEG 75%, tối đa 1080p),
     * không bao giờ lưu vào bộ nhớ flash để bảo đảm quyền riêng tư tuyệt đối.
     */
    private fun encodeBitmapToBase64Jpeg(bitmap: Bitmap): String {
        val maxDimension = 1080
        val width = bitmap.width
        val height = bitmap.height

        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension.toFloat() / max(width, height).toFloat()
            val matrix = Matrix().apply { postScale(scale, scale) }
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Kiểm tra kết nối thử nghiệm đến endpoint AI
     */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val endpoint = normalizeEndpoint(baseUrl)

        val jsonPayload = JSONObject().apply {
            put("model", model.ifBlank { "gpt-4o-mini" })
            put("temperature", 0.1)
            put("max_tokens", 10)

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            }
            put("messages", messagesArray)
        }

        try {
            val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    Result.success(Pair(latency, "Kết nối thành công! Độ trễ: ${latency}ms"))
                } else {
                    val code = response.code
                    val desc = when (code) {
                        401 -> "Sai API Key (401 Unauthorized)"
                        404 -> "Sai URL endpoint (404 Not Found)"
                        429 -> "Hết hạn ngạch hoặc bị giới hạn tốc độ (429 Rate Limit)"
                        else -> "Máy chủ trả về mã lỗi: $code"
                    }
                    Result.failure(RuntimeException(desc))
                }
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Lỗi kết nối: ${e.localizedMessage}"))
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatibleClient"
    }
}
