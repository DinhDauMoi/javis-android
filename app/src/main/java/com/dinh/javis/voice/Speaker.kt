package com.dinh.javis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Wrapper quản lý chuyển văn bản thành giọng nói (TextToSpeech) tiếng Việt.
 *
 * Tích hợp với ContinuousVoiceListener để tránh TTS đè micro:
 *   - Trước khi đọc: gọi continuousListener?.pauseForTts()
 *   - Sau khi đọc xong: gọi continuousListener?.resumeAfterTts()
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var pendingSpeech: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    /**
     * Gán ContinuousVoiceListener để Speaker tự động pause/resume micro
     * khi đang nói. Set trước khi gọi speak() lần đầu.
     */
    var continuousListener: ContinuousVoiceListener? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val vietnameseLocale = Locale("vi", "VN")
            val langResult = tts?.setLanguage(vietnameseLocale)

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Gói giọng nói tiếng Việt chưa được cài đầy đủ. Dùng locale mặc định.")
                tts?.language = Locale.getDefault()
            }

            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Mic đã được pause trước khi gọi speak(), không cần làm gì thêm
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS xong — resume micro")
                    continuousListener?.resumeAfterTts()
                    pendingOnDone?.invoke()
                    pendingOnDone = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Lỗi phát âm Utterance: $utteranceId")
                    continuousListener?.resumeAfterTts()
                    pendingOnDone?.invoke()
                    pendingOnDone = null
                }
            })

            isInitialized = true

            // Đọc văn bản đang chờ nếu có lệnh trước khi TTS khởi tạo xong
            val pending = pendingSpeech
            val pendingDone = pendingOnDone
            if (pending != null) {
                pendingSpeech = null
                pendingOnDone = null
                speakInternal(pending, pendingDone)
            }
        } else {
            Log.e(TAG, "Khởi tạo TextToSpeech thất bại với mã: $status")
        }
    }

    /**
     * Phát âm thanh đọc phản hồi bằng tiếng Việt.
     * Tự động pause micro (ContinuousVoiceListener) trước khi nói,
     * và resume sau khi nói xong.
     *
     * @param text  Văn bản cần đọc
     * @param onDone Callback khi đọc xong (tuỳ chọn)
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        // Pause micro TRƯỚC khi TTS nói để tránh feedback loop
        continuousListener?.pauseForTts()

        if (!isInitialized) {
            pendingSpeech = text
            pendingOnDone = onDone
            return
        }

        speakInternal(text, onDone)
    }

    private fun speakInternal(text: String, onDone: (() -> Unit)?) {
        this.pendingOnDone = onDone
        try {
            val utteranceId = "JAVIS_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình speak()", e)
            continuousListener?.resumeAfterTts()
            onDone?.invoke()
            pendingOnDone = null
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
