package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cấu hình mô hình AI BYOK (Bring Your Own Key) & các endpoint cục bộ (Ollama, vLLM)
 */
@Entity(tableName = "ai_profiles")
data class AiModelProfile(
    @PrimaryKey
    val id: String,
    val name: String,
    val providerType: String, // OPENAI, GROQ, OPENROUTER, OLLAMA, VLLM, CUSTOM
    val baseUrl: String,
    val secretKeyAlias: String, // Định danh khóa bí mật trong Android Keystore
    val chatModelId: String,
    val visionModelId: String,
    val planningModelId: String,
    val isActive: Boolean = false
) {
    companion object {
        const val PROVIDER_OPENAI = "OPENAI"
        const val PROVIDER_MISTRAL = "MISTRAL"
        const val PROVIDER_GROQ = "GROQ"
        const val PROVIDER_OPENROUTER = "OPENROUTER"
        const val PROVIDER_OLLAMA = "OLLAMA"
        const val PROVIDER_VLLM = "VLLM"
        const val PROVIDER_CUSTOM = "CUSTOM"
    }
}
