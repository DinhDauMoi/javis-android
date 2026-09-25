package com.dinh.javis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Wrapper quản lý nhận diện giọng nói SpeechRecognizer
 * Cấu hình mặc định: Tiếng Việt (vi-VN), sử dụng Google Speech Engine
 *
 * GHI CHÚ PHƯƠNG ÁN DỰ PHÒNG OFFLINE (VOSK):
 * Trong trường hợp máy không có Google Play Services hoặc không có mạng Internet,
 * có thể tích hợp thư viện Vosk Android (alphacep/vosk-android) với model "vosk-model-small-vn-0.4".
 * Vosk chạy hoàn toàn offline trên thiết bị mà không phụ thuộc Google.
 */
class VoiceInput(
    private val context: Context,
    private val listener: VoiceInputListener
) {

    interface VoiceInputListener {
        fun onReady()
        fun onBeginningOfSpeech()
        fun onRmsChanged(rmsdB: Float)
        fun onPartialResult(partialText: String)
        fun onFinalResult(text: String)
        fun onError(errorCode: Int, errorMessage: String)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Thiết bị không hỗ trợ SpeechRecognizer (chưa cài app Google).")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        listener.onReady()
                    }

                    override fun onBeginningOfSpeech() {
                        listener.onBeginningOfSpeech()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        listener.onRmsChanged(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val message = getErrorDescription(error)
                        Log.w(TAG, "SpeechRecognizer Error ($error): $message")
                        listener.onError(error, message)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim() ?: ""
                        if (spokenText.isNotEmpty()) {
                            listener.onFinalResult(spokenText)
                        } else {
                            listener.onError(-1, "Không nhận diện được nội dung nói")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotEmpty()) {
                            listener.onPartialResult(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo SpeechRecognizer", e)
        }
    }

    /**
     * Bắt đầu lắng nghe giọng nói với ngôn ngữ tiếng Việt (vi-VN)
     */
    fun startListening() {
        if (isListening) return

        if (speechRecognizer == null) {
            initRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể bắt đầu ghi âm", e)
            listener.onError(-1, "Không thể kích hoạt micro: ${e.localizedMessage}")
        }
    }

    /**
     * Dừng lắng nghe và bắt đầu phân tích kết quả
     */
    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi dừng SpeechRecognizer", e)
            }
            isListening = false
        }
    }

    /**
     * Hủy bỏ phiên nghe hiện tại
     */
    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi hủy SpeechRecognizer", e)
        }
        isListening = false
    }

    /**
     * Giải phóng tài nguyên SpeechRecognizer khi Activity/Service onDestroy
     */
    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng SpeechRecognizer", e)
        }
        speechRecognizer = null
        isListening = false
    }

    private fun getErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm từ Micro"
            SpeechRecognizer.ERROR_CLIENT -> "Lỗi ứng dụng client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro"
            SpeechRecognizer.ERROR_NETWORK -> "Lỗi kết nối mạng"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hết thời gian chờ mạng"
            SpeechRecognizer.ERROR_NO_MATCH -> "Không nghe rõ câu lệnh"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Dịch vụ đang bận, vui lòng thử lại"
            SpeechRecognizer.ERROR_SERVER -> "Lỗi máy chủ Google"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không phát hiện tiếng nói"
            else -> "Lỗi không xác định ($errorCode)"
        }
    }

    companion object {
        private const val TAG = "VoiceInput"
    }
}
