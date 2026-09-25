package com.dinh.javis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Wrapper quản lý chuyển văn bản thành giọng nói (TextToSpeech) tiếng Việt
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var pendingSpeech: String? = null
    private var onDoneCallback: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val vietnameseLocale = Locale("vi", "VN")
            val langResult = tts?.setLanguage(vietnameseLocale)

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Gói giọng nói tiếng Việt chưa được cài đặt đầy đủ. Đang dùng locale mặc định.")
                tts?.language = Locale.getDefault()
            }

            // Tinh chỉnh tốc độ đọc tự nhiên và cao độ
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Lỗi phát âm Utterance ID: $utteranceId")
                    onDoneCallback?.invoke()
                    onDoneCallback = null
                }
            })

            isInitialized = true

            // Đọc văn bản đang chờ nếu có lệnh trước khi TTS khởi tạo xong
            pendingSpeech?.let {
                speak(it)
                pendingSpeech = null
            }
        } else {
            Log.e(TAG, "Khởi tạo TextToSpeech thất bại với mã trạng thái: $status")
        }
    }

    /**
     * Phát âm thanh đọc phản hồi bằng tiếng Việt
     * @param text Văn bản cần đọc
     * @param onDone Callback khi đọc xong
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) return

        this.onDoneCallback = onDone

        if (!isInitialized) {
            pendingSpeech = text
            return
        }

        try {
            val utteranceId = "JAVIS_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình speak()", e)
        }
    }

    /**
     * Dừng ngay lập tức nếu đang phát âm thanh
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng TTS", e)
        }
    }

    /**
     * Giải phóng TextToSpeech khi Activity/Service onDestroy
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng TextToSpeech", e)
        }
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "Speaker"
    }
}
