package com.dinh.javis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * ContinuousVoiceListener — luôn lắng nghe, tự restart sau mỗi kết quả hoặc lỗi.
 * - Dừng mic khi TTS đang nói (tránh tự nghe giọng mình), resume ngay sau khi TTS xong.
 * - Debounce 1 giây chống lặp lệnh.
 * - Không bao giờ tự dừng trừ khi gọi stop().
 *
 * Cách dùng:
 *   val cvl = ContinuousVoiceListener(context) { text -> handleCommand(text) }
 *   cvl.start()
 *   // Trước khi TTS nói:  cvl.pauseForTts()
 *   // Sau khi TTS xong:   cvl.resumeAfterTts()
 *   // Khi dừng hẳn:       cvl.stop()
 */
class ContinuousVoiceListener(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatusChange: ((isListening: Boolean) -> Unit)? = null,
    private val onPartialResult: ((String) -> Unit)? = null
) {

    // -------------------------------------------------------------------------
    // Trạng thái nội bộ
    // -------------------------------------------------------------------------
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning = false          // người dùng muốn chạy hay không
    @Volatile private var isPausedForTts = false     // đang tạm dừng cho TTS nói
    @Volatile private var isRecognizing = false      // SpeechRecognizer đang chạy

    // Debounce: bỏ qua lệnh trùng trong vòng DEBOUNCE_MS
    private var lastCommandTime = 0L
    private var lastCommandText = ""

    private val RESTART_DELAY_MS = 400L
    private val DEBOUNCE_MS = 1000L

    private val restartRunnable = Runnable { startRecognizer() }

    // -------------------------------------------------------------------------
    // API công khai
    // -------------------------------------------------------------------------

    /** Bắt đầu luồng nghe liên tục */
    fun start() {
        if (isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Thiết bị không hỗ trợ SpeechRecognizer")
            return
        }
        isRunning = true
        isPausedForTts = false
        scheduleStart()
    }

    /**
     * Tạm dừng micro để TTS nói — gọi ngay TRƯỚC khi speaker.speak(...)
     * Micro chỉ resume khi gọi resumeAfterTts()
     */
    fun pauseForTts() {
        if (!isRunning) return
        isPausedForTts = true
        stopRecognizer()
        onStatusChange?.invoke(false)
        Log.d(TAG, "Mic tạm dừng cho TTS")
    }

    /**
     * Resume micro ngay sau khi TTS đọc xong — gọi trong onDone callback của Speaker
     */
    fun resumeAfterTts() {
        if (!isRunning) return
        isPausedForTts = false
        Log.d(TAG, "TTS xong — resume micro")
        scheduleStart(delayMs = 300)
    }

    /** Dừng hẳn, hủy tất cả tài nguyên */
    fun stop() {
        isRunning = false
        isPausedForTts = false
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        onStatusChange?.invoke(false)
        Log.i(TAG, "ContinuousVoiceListener đã dừng hẳn")
    }

    val isListening: Boolean get() = isRecognizing && !isPausedForTts

    // -------------------------------------------------------------------------
    // Nội bộ
    // -------------------------------------------------------------------------

    private fun scheduleStart(delayMs: Long = 0) {
        handler.removeCallbacks(restartRunnable)
        if (delayMs == 0L) {
            handler.post(restartRunnable)
        } else {
            handler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun startRecognizer() {
        if (!isRunning || isPausedForTts || isRecognizing) return

        // Tạo mới SpeechRecognizer mỗi lần để tránh lỗi trạng thái tích lũy
        destroyRecognizer()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(buildListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            isRecognizing = true
            onStatusChange?.invoke(true)
            Log.d(TAG, "SpeechRecognizer đã bắt đầu")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động SpeechRecognizer", e)
            isRecognizing = false
            scheduleRestart()
        }
    }

    private fun stopRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) { /* bỏ qua */ }
        destroyRecognizer()
        isRecognizing = false
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* bỏ qua */ }
        speechRecognizer = null
    }

    private fun scheduleRestart() {
        if (!isRunning || isPausedForTts) return
        isRecognizing = false
        onStatusChange?.invoke(false)
        scheduleStart(RESTART_DELAY_MS)
    }

    private fun buildListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Sẵn sàng nghe")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Bắt đầu phát hiện tiếng nói")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Hết tiếng nói — đang xử lý...")
        }

        override fun onError(error: Int) {
            isRecognizing = false
            val msg = describeError(error)
            Log.w(TAG, "Lỗi SpeechRecognizer ($error): $msg")

            // Bỏ qua lỗi CLIENT khi bị hủy chủ động do TTS pause
            if (error == SpeechRecognizer.ERROR_CLIENT && isPausedForTts) return

            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            isRecognizing = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            Log.d(TAG, "Kết quả nhận diện: \"$text\"")

            if (text.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (text == lastCommandText && now - lastCommandTime < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounce: bỏ qua lệnh trùng \"$text\"")
                } else {
                    lastCommandText = text
                    lastCommandTime = now
                    onResult(text)
                }
            }

            // Restart ngay sau kết quả nếu không đang bị TTS dừng
            if (!isPausedForTts) {
                scheduleStart(RESTART_DELAY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotEmpty()) {
                onPartialResult?.invoke(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun describeError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm"
        SpeechRecognizer.ERROR_CLIENT -> "Lỗi client (hủy chủ động)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro"
        SpeechRecognizer.ERROR_NETWORK -> "Lỗi mạng"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hết thời gian chờ mạng"
        SpeechRecognizer.ERROR_NO_MATCH -> "Không nghe rõ"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Engine đang bận"
        SpeechRecognizer.ERROR_SERVER -> "Lỗi server Google"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không phát hiện tiếng nói"
        else -> "Không xác định ($code)"
    }

    companion object {
        private const val TAG = "ContinuousVoiceListener"
    }
}
