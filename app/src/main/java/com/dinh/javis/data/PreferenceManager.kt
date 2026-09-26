package com.dinh.javis.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Trình quản lý cấu hình và thiết lập ứng dụng JAVIS (SharedPreferences)
 */
class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var openAiBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var openAiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var openAiModel: String
        get() = prefs.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKEWORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKEWORD_ENABLED, value).apply()

    var picovoiceKey: String
        get() = prefs.getString(KEY_PICOVOICE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PICOVOICE_KEY, value.trim()).apply()

    var isFloatingMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_MIC, value).apply()

    companion object {
        private const val PREF_NAME = "javis_preferences"
        private const val KEY_BASE_URL = "openai_base_url"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_MODEL = "openai_model"
        private const val KEY_WAKEWORD_ENABLED = "wakeword_enabled"
        private const val KEY_PICOVOICE_KEY = "picovoice_key"
        private const val KEY_FLOATING_MIC = "floating_mic_enabled"
    }
}
