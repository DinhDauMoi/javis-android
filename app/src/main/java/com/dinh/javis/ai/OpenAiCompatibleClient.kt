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
 * Client for communicating with any AI Gateway supporting the OpenAI API protocol
 * (OpenAI GPT-4o, Groq Vision, OpenRouter, Mistral, Ollama, local vLLM).
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
            // GenroStore gateway active catalog supports pixtral-12b-2409 for Mistral Vision.
            // Map pixtral-large models to supported mistral/pixtral-12b-2409 to prevent 401/400 gateway errors.
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

    override suspend fun chat(messages: List<ChatMessage>, options: ModelOptions): String = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(baseUrl)
        val isMistral = isMistralOrPixtral(defaultChatModel, baseUrl)

        val jsonPayload = JSONObject().apply {
            put("model", resolveModelName(defaultChatModel, baseUrl))
            put("temperature", options.temperature)
            put("max_tokens", options.maxTokens)

            val messagesArray = JSONArray()
            if (isMistral) {
                // Pixtral/Mistral models do not support role: "system"; prepend system instructions into the first user message
                val systemContents = messages.filter { it.role.equals("system", ignoreCase = true) }.map { it.content }
                val nonSystem = messages.filterNot { it.role.equals("system", ignoreCase = true) }
                val systemPrefix = if (systemContents.isNotEmpty()) systemContents.joinToString("\n\n") + "\n\n" else ""

                var firstUserHandled = false
                for (msg in nonSystem) {
                    val content = if (!firstUserHandled && msg.role.equals("user", ignoreCase = true)) {
                        firstUserHandled = true
                        systemPrefix + msg.content
                    } else {
                        msg.content
                    }
                    messagesArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", content)
                    })
                }
                if (!firstUserHandled && systemPrefix.isNotBlank()) {
                    messagesArray.put(JSONObject().apply {
                        put("role", "user")
                        put("content", systemPrefix.trim())
                    })
                }
            } else {
                for (msg in messages) {
                    messagesArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
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
        val isMistral = isMistralOrPixtral(defaultVisionModel, baseUrl)
        val systemPrompt = "Bạn là JAVIS Vision Assistant. Bạn nhận hình ảnh màn hình điện thoại và bối cảnh cây giao diện. Hãy phân tích các phần tử hiển thị trên màn hình và trả lời bằng tiếng Việt ngắn gọn, chính xác."

        val jsonPayload = JSONObject().apply {
            put("model", resolveModelName(defaultVisionModel, baseUrl))
            put("temperature", options.temperature)
            put("max_tokens", max(options.maxTokens, 400))

            val messagesArray = JSONArray().apply {
                if (!isMistral) {
                    // System message for models supporting standard OpenAI schema
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }

                // User message with text prompt and image_url payload
                val userContentArray = JSONArray().apply {
                    val fullTextPrompt = buildString {
                        if (isMistral) {
                            append("[$systemPrompt]\n\n")
                        }
                        append(prompt)
                        if (!nodeContext.isNullOrBlank()) {
                            append("\n\n[Accessibility UI Hierarchy]:\n")
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
            Log.e(TAG, "Error analyzing screen vision", e)
            VisionAnalysisResult(description = "Unable to analyze screen: ${e.message}", rawResponse = "")
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
            You are JAVIS Behavior Agent controlling an Android smartphone.
            Your task is to plan the next action to achieve the user's goal.
            You MUST return raw JSON only (no markdown, no ```json wrapper):
            {
              "thought": "Brief reasoning for choosing this action",
              "action": "CLICK" | "SCROLL" | "TYPE" | "WAIT" | "TERMINATE",
              "params": {
                "x": 540,
                "y": 1200,
                "text": "text content if TYPE",
                "direction": "UP" | "DOWN" | "LEFT" | "RIGHT"
              },
              "isGoalComplete": false
            }
            Notes:
            - CLICK: Tap at physical coordinates (x, y).
            - SCROLL: Scroll in direction (UP/DOWN).
            - TYPE: Input text into the focused field.
            - WAIT: Wait for UI to load.
            - TERMINATE: Completed or cannot proceed; set isGoalComplete = true.
        """.trimIndent()

        val userPromptBuilder = StringBuilder().apply {
            append("Task goal: ").append(taskGoal).append("\n")
            append("Current package: ").append(screenSummary.currentPackage ?: "Unknown").append("\n")
            append("Screen resolution: ${screenSummary.screenshotWidth}x${screenSummary.screenshotHeight}\n\n")

            if (!screenSummary.nodeHierarchyText.isNullOrBlank()) {
                append("[UI Elements Detected]:\n")
                append(screenSummary.nodeHierarchyText).append("\n\n")
            }

            if (!screenSummary.ocrText.isNullOrBlank()) {
                append("[On-Screen OCR Text]:\n")
                append(screenSummary.ocrText).append("\n\n")
            }

            if (history.isNotEmpty()) {
                append("[Execution History]:\n")
                for (step in history) {
                    append("- Step ${step.stepIndex}: ${step.action} (${step.details}) -> Success: ${step.success}\n")
                }
                append("\n")
            }

            append("Decide the next action as JSON:")
        }

        val isMistral = isMistralOrPixtral(defaultPlanningModel, baseUrl)
        val jsonPayload = JSONObject().apply {
            put("model", resolveModelName(defaultPlanningModel, baseUrl))
            put("temperature", 0.2) // Low temperature for deterministic JSON output
            put("max_tokens", 350)

            val messagesArray = JSONArray().apply {
                if (!isMistral) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPromptBuilder.toString())
                    })
                } else {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "$systemPrompt\n\n$userPromptBuilder")
                    })
                }
            }
            put("messages", messagesArray)
        }

        try {
            val responseText = executeRequest(endpoint, jsonPayload)
            parseActionPlanJson(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Error planning ReAct behavior", e)
            ActionPlanResult(
                thought = "Error calling planning model: ${e.message}",
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
            val thought = obj.optString("thought", "No explanation provided")
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
            Log.w(TAG, "Failed to parse JSON returned from Planner: $rawResponse")
            ActionPlanResult(
                thought = "Model returned invalid format: $rawResponse",
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
            val trimmedKey = apiKey.trim()
            requestBuilder.addHeader("Authorization", "Bearer $trimmedKey")
            requestBuilder.addHeader("x-api-key", trimmedKey)
            requestBuilder.addHeader("api-key", trimmedKey)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    400 -> "HTTP 400 Bad Request: $bodyString"
                    401 -> "HTTP 401: Invalid or unauthorized API Key."
                    429 -> "HTTP 429: API rate limit exceeded."
                    500, 502, 503 -> "HTTP ${response.code}: AI server maintenance or overload."
                    else -> "AI connection error (${response.code}): $bodyString"
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
            throw RuntimeException("API returned no valid completion choice")
        }
    }

    /**
     * Compress Bitmap directly in RAM (JPEG 75%, max 1080p),
     * never writing to disk to guarantee user privacy.
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

    private fun extractErrorMessage(jsonBody: String): String {
        return try {
            val obj = JSONObject(jsonBody)
            val errObj = obj.optJSONObject("error")
            errObj?.optString("message")?.ifBlank { null }
                ?: obj.optString("message").ifBlank { null }
                ?: obj.optString("detail").ifBlank { null }
                ?: jsonBody.take(200)
        } catch (_: Exception) {
            jsonBody.take(200)
        }
    }

    /**
     * Test connection to the AI endpoint
     */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val endpoint = normalizeEndpoint(baseUrl)
        val finalModel = resolveModelName(model.ifBlank { "pixtral-12b-2409" }, baseUrl)

        val jsonPayload = JSONObject().apply {
            put("model", finalModel)
            put("temperature", 0.2)
            put("max_tokens", 30)

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
                val trimmedKey = apiKey.trim()
                requestBuilder.addHeader("Authorization", "Bearer $trimmedKey")
                requestBuilder.addHeader("x-api-key", trimmedKey)
                requestBuilder.addHeader("api-key", trimmedKey)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success(Pair(latency, "Kết nối thành công! Độ trễ: ${latency}ms (Mô hình: $finalModel)"))
                } else {
                    val code = response.code
                    val detail = extractErrorMessage(bodyString)
                    val desc = when (code) {
                        400 -> "Lỗi 400 (Yêu cầu không hợp lệ): $detail"
                        401 -> "Lỗi 401 (Sai hoặc thiếu API Key): $detail"
                        404 -> "Lỗi 404 (Không tìm thấy endpoint): $detail"
                        429 -> "Lỗi 429 (Hết hạn ngạch hoặc Rate Limit): $detail"
                        else -> "Máy chủ trả về mã lỗi $code: $detail"
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
