package com.dinh.javis.ai

import android.content.Context
import android.graphics.Bitmap
import com.dinh.javis.ai.capabilities.*
import com.dinh.javis.data.AiModelProfile
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trình định tuyến mô hình AI đa nền tảng (BYOK Router).
 * Tự động chọn cấu hình AI hoạt động từ Room DB hoặc các thông số trong PreferenceManager.
 */
class ModelRouter(private val context: Context) {

    private val preferenceManager = PreferenceManager(context)
    private val keystoreManager = KeystoreManager(context)
    private val database = AppDatabase.getDatabase(context)

    private val openAiClient = OpenAiCompatibleClient(
        baseUrl = preferenceManager.openAiBaseUrl,
        apiKey = preferenceManager.openAiApiKey,
        defaultChatModel = preferenceManager.openAiModel,
        defaultVisionModel = preferenceManager.openAiModel,
        defaultPlanningModel = preferenceManager.openAiModel
    )

    private suspend fun syncClientWithActiveProfile() = withContext(Dispatchers.IO) {
        val activeProfile = database.aiModelProfileDao().getActiveProfile()
        if (activeProfile != null) {
            val key = keystoreManager.decrypt(activeProfile.secretKeyAlias)
            openAiClient.updateConfig(
                baseUrl = activeProfile.baseUrl,
                apiKey = key,
                chatModel = activeProfile.chatModelId.ifBlank { "gpt-4o-mini" },
                visionModel = activeProfile.visionModelId.ifBlank { "gpt-4o-mini" },
                planningModel = activeProfile.planningModelId.ifBlank { "gpt-4o-mini" }
            )
        } else {
            // Sử dụng cấu hình mặc định trong PreferenceManager
            openAiClient.updateConfig(
                baseUrl = preferenceManager.openAiBaseUrl,
                apiKey = preferenceManager.openAiApiKey,
                chatModel = preferenceManager.openAiModel,
                visionModel = preferenceManager.openAiModel,
                planningModel = preferenceManager.openAiModel
            )
        }
    }

    /**
     * Chat bằng tin nhắn dạng chuỗi văn bản (Tương thích ngược với OpenAiClient cũ)
     */
    suspend fun askAi(prompt: String): String {
        syncClientWithActiveProfile()
        val messages = listOf(
            ChatMessage(
                role = "system",
                content = "Bạn là JAVIS, trợ lý AI cá nhân tiếng Việt của anh Dinh trên điện thoại OPPO Find X8 Ultra. Hãy trả lời ngắn gọn, thông minh và súc tích trong 1 đến 2 câu tự nhiên để đọc bằng giọng nói. Tránh dùng ký hiệu định dạng markdown như **, #, *."
            ),
            ChatMessage(role = "user", content = prompt)
        )
        return try {
            openAiClient.chat(messages, ModelOptions(temperature = 0.7, maxTokens = 150))
        } catch (e: Exception) {
            "Lỗi kết nối AI: ${e.localizedMessage ?: "Không xác định"}"
        }
    }

    /**
     * Phân tích thị giác màn hình từ ảnh chụp Bitmap và bối cảnh Accessibility
     */
    suspend fun analyzeScreen(
        prompt: String,
        bitmap: Bitmap,
        nodeContext: String? = null
    ): VisionAnalysisResult {
        syncClientWithActiveProfile()
        return openAiClient.analyzeScreen(prompt, bitmap, nodeContext)
    }

    /**
     * Lập kế hoạch bước hành động tiếp theo cho Behavior Agent
     */
    suspend fun planNextAction(
        taskGoal: String,
        screenSummary: ScreenObservation,
        history: List<ActionSummary> = emptyList()
    ): ActionPlanResult {
        syncClientWithActiveProfile()
        return openAiClient.planNextAction(taskGoal, screenSummary, history)
    }

    /**
     * Kiểm tra kết nối cấu hình mô hình
     */
    suspend fun testProfileConnection(
        profile: AiModelProfile,
        overrideKey: String? = null
    ): Result<Pair<Long, String>> {
        val key = overrideKey ?: keystoreManager.decrypt(profile.secretKeyAlias)
        return openAiClient.testConnection(
            baseUrl = profile.baseUrl,
            apiKey = key,
            model = profile.chatModelId.ifBlank { "gpt-4o-mini" }
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: ModelRouter? = null

        fun getInstance(context: Context): ModelRouter {
            return INSTANCE ?: synchronized(this) {
                val instance = ModelRouter(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
