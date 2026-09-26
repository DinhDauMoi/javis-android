package com.dinh.javis.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.dinh.javis.utils.PermissionHelper
import com.openwakeword.OpenWakeWord

/**
 * Trình quản lý từ khóa đánh thức "javis" bằng openWakeWord (on-device, không cần API key, không cần tài khoản)
 * kết hợp SpeechRecognizer nghe 1 lệnh có timeout 6 giây.
 *
 * Chu trình hoạt động theo Prompt v4:
 * 1. Chạy nền liên tục (HotwordService foreground) chờ từ "javis" (hoặc built-in "Hey Jarvis").
 * 2. Khi bắt được wake word:
 *    - Tạm dừng openWakeWord (trả mic).
 *    - Phát âm thanh thức dậy (wake beep / "dạ").
 *    - Bật SpeechRecognizer nghe đúng 1 lệnh (timeout 6 giây, locale vi-VN).
 *    - Xử lý lệnh (thực thi cử chỉ lướt TikTok / chỉnh âm lượng / mở app) + phát tiếng beep 100ms xác nhận.
 *    - Quay lại chờ wake word tiếp theo.
 * 3. Tắt công tắc "Chờ gọi 'javis'" trên UI -> dừng hẳn detector, micro nhả hoàn toàn (mất icon mic).
 */
class HotwordManager(
    private val context: Context,
    private val speaker: Speaker,
    private val onCommandRecognized: (String) -> Unit,
    private val onStatusChange: (statusText: String, isListeningCommand: Boolean, isWaitingHotword: Boolean) -> Unit,
    private val onLogMessage: (String, Boolean, String?) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var openWakeWord: OpenWakeWord? = null
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile private var isRunning = false                // Người dùng bật hay tắt
    @Volatile private var isListeningCommand = false       // Đang chạy SpeechRecognizer nghe 1 lệnh
    @Volatile private var isPausedForTts = false          // Đang tạm dừng cho TTS nói

    // Debounce chống lặp lệnh trong vòng 1 giây (1000ms)
    private var lastCommandText = ""
    private var lastCommandTime = 0L
    private val DEBOUNCE_MS = 1000L

    // Timeout 6 giây cho 1 lệnh nói
    private val COMMAND_TIMEOUT_MS = 6000L
    private val commandTimeoutRunnable = Runnable {
        Log.w(TAG, "Hết thời gian chờ lệnh (6 giây) — quay lại chờ wake word")
        cancelCommandListening()
        resumeHotwordDetector()
    }

    init {
        // Lắng nghe trạng thái TTS để micro và loa không đè nhau
        speaker.onSpeechStarted = {
            mainHandler.post {
                isPausedForTts = true
                pauseHotwordDetector()
                cancelCommandListening()
            }
        }
        speaker.onSpeechFinished = {
            mainHandler.post {
                isPausedForTts = false
                if (isRunning && !isListeningCommand) {
                    resumeHotwordDetector(delayMs = 300)
                }
            }
        }
    }

    /**
     * Bật chức năng chờ gọi "javis"
     */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val errMsg = "Chưa cấp quyền Micro cho JAVIS"
            onLogMessage(errMsg, false, "LỖI")
            speaker.speak(errMsg)
            return
        }

        isRunning = true
        HotwordService.start(context, "Đang chờ gọi 'javis'...")
        initAndStartHotword()
    }

    /**
     * Dừng hẳn chức năng chờ gọi "javis" — giải phóng micro hoàn toàn
     */
    fun stop() {
        isRunning = false
        mainHandler.removeCallbacks(commandTimeoutRunnable)

        // Dừng và hủy OpenWakeWord
        try {
            openWakeWord?.stop()
            openWakeWord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng openWakeWord", e)
        }
        openWakeWord = null

        // Dừng và hủy SpeechRecognizer
        cancelCommandListening()

        // Dừng foreground service
        HotwordService.stop(context)

        onStatusChange("Đã tắt chờ gọi 'javis'", false, false)
        Log.i(TAG, "HotwordManager đã dừng hẳn, mic đã được giải phóng")
    }

    /**
     * Kích hoạt nghe 1 lệnh thủ công (khi bấm nút Mic lớn hoặc từ Quick Settings Tile)
     */
    fun triggerOneShotCommand() {
        if (!isRunning) {
            start()
        }
        onWakeWordTriggered()
    }

    // =========================================================================
    // KHỞI TẠO & CHẠY OPENWAKEWORD
    // =========================================================================

    private fun initAndStartHotword() {
        if (!isRunning || isListeningCommand || isPausedForTts) return

        try {
            if (openWakeWord == null) {
                val builder = OpenWakeWord.Builder(context)

                // Kiểm tra model: Ưu tiên javis.onnx trong assets, fallback dùng built-in HEY_JARVIS
                val hasCustomModel = try {
                    context.assets.list("")?.contains("javis.onnx") == true
                } catch (e: Exception) {
                    false
                }

                if (hasCustomModel) {
                    Log.i(TAG, "Sử dụng model riêng: javis.onnx từ thư mục assets")
                    builder.setModelAsset("javis.onnx")
                } else {
                    Log.i(TAG, "Sử dụng model built-in HEY_JARVIS ('Hey Jarvis' ≈ 'javis')")
                    builder.setModel(OpenWakeWord.BuiltInModel.HEY_JARVIS)
                }

                builder.setThreshold(0.5f)
                builder.setDebounceMs(1500)
                openWakeWord = builder.build()
            }

            openWakeWord?.start { score ->
                Log.d(TAG, "Phát hiện từ khóa 'javis'! Điểm tin cậy: $score")
                mainHandler.post { onWakeWordTriggered() }
            }

            onStatusChange("Đang chờ gọi 'javis'...", false, true)
            Log.i(TAG, "openWakeWord đang lắng nghe từ khóa 'javis'...")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi động openWakeWord", e)
            onLogMessage("Lỗi khởi tạo mô hình nhận diện giọng nói: ${e.message}", false, "LỖI")
            speaker.speak("Không thể tải mô hình nhận diện từ khóa")
        }
    }

    private fun pauseHotwordDetector() {
        try {
            openWakeWord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi pause openWakeWord", e)
        }
    }

    private fun resumeHotwordDetector(delayMs: Long = 200) {
        if (!isRunning || isListeningCommand || isPausedForTts) return

        mainHandler.postDelayed({
            if (isRunning && !isListeningCommand && !isPausedForTts) {
                initAndStartHotword()
            }
        }, delayMs)
    }

    // =========================================================================
    // XỬ LÝ KHI BẮT ĐƯỢC TỪ KHÓA "JAVIS"
    // =========================================================================

    private fun onWakeWordTriggered() {
        if (!isRunning || isListeningCommand) return

        Log.i(TAG, "Bắt được 'javis' -> Phát âm thanh thức dậy và lắng nghe 1 lệnh (6s)")

        // 1. Tạm dừng ngay wake word detector để nhường mic cho SpeechRecognizer
        pauseHotwordDetector()

        // 2. Phát beep ngắn (hoặc tone) thức dậy
        speaker.playWakeBeep()

        // 3. Bật SpeechRecognizer nghe đúng 1 lệnh (timeout 6 giây)
        startCommandListening()
    }

    private fun startCommandListening() {
        isListeningCommand = true
        onStatusChange("Đang nghe lệnh (6s)...", true, false)
        HotwordService.start(context, "Đang nghe lệnh nói...")

        destroySpeechRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
            onLogMessage("Thiết bị chưa cài Google Speech Engine", false, "LỖI")
            speaker.speak("Chưa cài Google Speech trên máy này")
            isListeningCommand = false
            resumeHotwordDetector()
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createCommandRecognitionListener())
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

            // Đặt lịch timeout 6 giây tự động hủy nếu người dùng không nói gì
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            mainHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS)

            Log.d(TAG, "SpeechRecognizer đã bắt đầu lắng nghe lệnh (hẹn giờ 6s)")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động SpeechRecognizer", e)
            isListeningCommand = false
            resumeHotwordDetector()
        }
    }

    private fun cancelCommandListening() {
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        isListeningCommand = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) { /* bỏ qua */ }
        destroySpeechRecognizer()
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* bỏ qua */ }
        speechRecognizer = null
    }

    private fun createCommandRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SpeechRecognizer sẵn sàng nhận giọng nói lệnh")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Phát hiện người dùng bắt đầu nói lệnh")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Người dùng đã dứt câu — đang nhận diện kết quả...")
            mainHandler.removeCallbacks(commandTimeoutRunnable)
        }

        override fun onError(errorCode: Int) {
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            isListeningCommand = false
            destroySpeechRecognizer()

            val errorMsg = describeSpeechError(errorCode)
            Log.w(TAG, "SpeechRecognizer lỗi ($errorCode): $errorMsg")

            // Quay lại chờ wake word
            resumeHotwordDetector(delayMs = 400)
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            isListeningCommand = false
            destroySpeechRecognizer()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            Log.d(TAG, "Nhận diện lệnh thành công: \"$text\"")

            if (text.isNotEmpty()) {
                val now = System.currentTimeMillis()
                // Debounce 1 giây: Bỏ qua nếu lệnh trùng lặp trong vòng 1s
                if (text.equals(lastCommandText, ignoreCase = true) && (now - lastCommandTime < DEBOUNCE_MS)) {
                    Log.d(TAG, "Debounce: bỏ qua kết quả trùng lặp nhanh: \"$text\"")
                } else {
                    lastCommandText = text
                    lastCommandTime = now
                    onCommandRecognized(text)
                }
            }

            // Nếu không có TTS đang nói thì resume wake word ngay sau 400ms
            if (!isPausedForTts) {
                resumeHotwordDetector(delayMs = 400)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotEmpty()) {
                onStatusChange(partial, true, false)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun describeSpeechError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm từ Micro"
        SpeechRecognizer.ERROR_CLIENT -> "Lỗi client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro"
        SpeechRecognizer.ERROR_NETWORK -> "Lỗi kết nối mạng"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hết thời gian chờ mạng"
        SpeechRecognizer.ERROR_NO_MATCH -> "Không nghe rõ câu lệnh"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Dịch vụ nhận diện đang bận"
        SpeechRecognizer.ERROR_SERVER -> "Lỗi máy chủ Google"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không phát hiện tiếng nói"
        else -> "Lỗi không xác định ($code)"
    }

    val isWaiting: Boolean get() = isRunning && !isListeningCommand && !isPausedForTts
    val isListening: Boolean get() = isListeningCommand
    val isDetectorRunning: Boolean get() = isRunning

    companion object {
        private const val TAG = "HotwordManager"
    }
}
