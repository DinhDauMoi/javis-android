package com.dinh.javis.data

import android.content.Context
import android.content.SharedPreferences
import com.dinh.javis.security.KeystoreManager

/**
 * Trình quản lý cấu hình và thiết lập ứng dụng JAVIS.
 * Sử dụng KeystoreManager để lưu trữ an toàn các thông tin nhạy cảm (API Key).
 */
class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val keystoreManager = KeystoreManager(context)

    init {
        migrateLegacyApiKeyIfNeeded()
    }

    /**
     * Tự động di chuyển API Key từ SharedPreferences dạng văn bản thô
     * sang vùng lưu trữ mã hóa phần cứng Keystore AES-GCM khi khởi động
     */
    private fun migrateLegacyApiKeyIfNeeded() {
        val legacyKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (legacyKey.isNotBlank()) {
            val alias = DEFAULT_KEY_ALIAS
            keystoreManager.encrypt(alias, legacyKey)
            val decrypted = keystoreManager.decrypt(alias)
            if (decrypted == legacyKey) {
                // Xóa hoàn toàn key dạng plaintext khỏi preferences
                prefs.edit().remove(KEY_API_KEY).apply()
            }
        }
    }

    var openAiBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var openAiApiKey: String
        get() {
            // First check hardware Keystore storage
            val encryptedKey = keystoreManager.decrypt(DEFAULT_KEY_ALIAS)
            if (encryptedKey.isNotBlank()) return encryptedKey
            // Secondary fallback to SharedPreferences
            return prefs.getString(KEY_API_KEY, "") ?: ""
        }
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                keystoreManager.deleteKey(DEFAULT_KEY_ALIAS)
                prefs.edit().remove(KEY_API_KEY).apply()
            } else {
                keystoreManager.encrypt(DEFAULT_KEY_ALIAS, trimmed)
                prefs.edit().putString(KEY_API_KEY, trimmed).apply()
            }
        }

    var openAiModel: String
        get() = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var activeAiProfileId: String
        get() = prefs.getString(KEY_ACTIVE_AI_PROFILE_ID, "default_profile") ?: "default_profile"
        set(value) = prefs.edit().putString(KEY_ACTIVE_AI_PROFILE_ID, value.trim()).apply()

    var isVisionEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VISION_ENABLED, value).apply()

    var isBehaviorAgentEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEHAVIOR_AGENT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BEHAVIOR_AGENT_ENABLED, value).apply()

    var agentMaxSteps: Int
        get() = prefs.getInt(KEY_AGENT_MAX_STEPS, 8)
        set(value) = prefs.edit().putInt(KEY_AGENT_MAX_STEPS, value).apply()

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKEWORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKEWORD_ENABLED, value).apply()

    var picovoiceKey: String
        get() = prefs.getString(KEY_PICOVOICE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PICOVOICE_KEY, value.trim()).apply()

    var isFloatingMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_MIC, value).apply()

    var wakeWordThreshold: Float
        get() = prefs.getFloat(KEY_WAKEWORD_THRESHOLD, 0.30f)
        set(value) = prefs.edit().putFloat(KEY_WAKEWORD_THRESHOLD, value).apply()

    var isGlowOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOW_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GLOW_OVERLAY_ENABLED, value).apply()

    /**
     * Explicit opt-in for local behavior analytics (BA-08).
     * Default is OFF — must not be inferred from other permissions.
     * Turning this off must not disable voice commands or agent execution.
     */
    var isBehaviorAnalyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEHAVIOR_ANALYTICS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BEHAVIOR_ANALYTICS_ENABLED, value).apply()

    /**
     * Elapsed-time deadline in seconds for a task run (BA-05).
     * Defaults to 60 seconds; user may configure per task profile.
     */
    var agentDeadlineSeconds: Int
        get() = prefs.getInt(KEY_AGENT_DEADLINE_SECONDS, 60)
        set(value) = prefs.edit().putInt(KEY_AGENT_DEADLINE_SECONDS, value.coerceIn(15, 300)).apply()

    /**
     * Option for Debug Mode to display system logs on the chat screen and enable diagnostic report export.
     */
    var isDebugModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE_ENABLED, value).apply()

    companion object {
        private const val PREF_NAME = "javis_preferences"
        private const val KEY_BASE_URL = "openai_base_url"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_MODEL = "openai_model"
        private const val KEY_ACTIVE_AI_PROFILE_ID = "active_ai_profile_id"
        private const val KEY_VISION_ENABLED = "vision_enabled"
        private const val KEY_BEHAVIOR_AGENT_ENABLED = "behavior_agent_enabled"
        private const val KEY_AGENT_MAX_STEPS = "agent_max_steps"
        private const val KEY_WAKEWORD_ENABLED = "wakeword_enabled"
        private const val KEY_PICOVOICE_KEY = "picovoice_key"
        private const val KEY_FLOATING_MIC = "floating_mic_enabled"
        private const val KEY_WAKEWORD_THRESHOLD = "wakeword_threshold"
        private const val KEY_GLOW_OVERLAY_ENABLED = "glow_overlay_enabled"
        private const val KEY_BEHAVIOR_ANALYTICS_ENABLED = "behavior_analytics_enabled"
        private const val KEY_AGENT_DEADLINE_SECONDS = "agent_deadline_seconds"
        private const val KEY_DEBUG_MODE_ENABLED = "debug_mode_enabled"

        const val DEFAULT_KEY_ALIAS = "default_openai_key"
    }
}
