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
import com.dinh.javis.commands.Command
import com.dinh.javis.commands.CommandParser
import com.dinh.javis.data.PreferenceManager
import com.dinh.javis.ui.GlowOverlayManager
import com.dinh.javis.utils.PermissionHelper
import com.openwakeword.OpenWakeWord
import java.util.Locale

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
    private val preferenceManager: PreferenceManager,
    private val commandParser: CommandParser,
    private val onCommandRecognized: (String) -> Unit,
    private val onStatusChange: (statusText: String, isListeningCommand: Boolean, isWaitingHotword: Boolean) -> Unit,
    private val onLogMessage: (String, Boolean, String?) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glowOverlayManager = GlowOverlayManager(context, preferenceManager)

    private var openWakeWord: OpenWakeWord? = null
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile private var isRunning = false                // Người dùng bật hay tắt
    @Volatile private var isListeningCommand = false       // Đang chạy SpeechRecognizer nghe 1 lệnh
    @Volatile private var isPausedForTts = false          // Đang tạm dừng cho TTS nói

    // Đếm số lần thử nghe lại khi không phát hiện tiếng nói (tối đa 1 lần thử lại)
    private var commandRetryCount = 0
    private val MAX_COMMAND_RETRIES = 1

    // Đo độ trễ từng bước (timestamp profiling)
    private var tWakeDetected = 0L
    private var tBeepStarted = 0L
    private var tSttStart = 0L
    private var tSttReady = 0L

    // Debounce chống lặp lệnh trong vòng 1 giây (1000ms)
    private var lastCommandText = ""
    private var lastCommandTime = 0L
    private val DEBOUNCE_MS = 1000L

    // Timeout 6 giây cho 1 lệnh nói
    private val COMMAND_TIMEOUT_MS = 6000L
    private val commandTimeoutRunnable = Runnable {
        handleCommandTimeoutOrNoSpeech("Hết thời gian chờ lệnh (6 giây)")
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
        glowOverlayManager.hide()

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
        destroySpeechRecognizer()

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
        tWakeDetected = System.currentTimeMillis()
        tBeepStarted = tWakeDetected
        pauseHotwordDetector()
        speaker.playWakeBeep()
        onStatusChange("Đang kích hoạt...", true, false)
        glowOverlayManager.show()
        mainHandler.postDelayed({
            if (isRunning && !isListeningCommand && !isPausedForTts) {
                commandRetryCount = 0
                startCommandListening()
            }
        }, 420L)
    }

    // =========================================================================
    // KHỞI TẠO & CHẠY OPENWAKEWORD
    // =========================================================================

    private fun initAndStartHotword() {
        if (!isRunning || isListeningCommand || isPausedForTts) return

        try {
            val currentThreshold = preferenceManager.wakeWordThreshold

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

                builder.setThreshold(currentThreshold)
                builder.setDebounceMs(1500)
                openWakeWord = builder.build()
            } else {
                openWakeWord?.updateThreshold(currentThreshold)
            }

            openWakeWord?.setStatusListener { step, isSuccess, detail ->
                Log.i(TAG, "[$step] Success=$isSuccess: $detail")
                val tag = if (isSuccess) "HOTWORD" else "LỖI"
                onLogMessage("[$step] $detail", false, tag)
            }

            var lastLoggedScoreTime = 0L
            openWakeWord?.setScoreListener { score, rms ->
                if (isRunning && !isListeningCommand && !isPausedForTts) {
                    val threshold = preferenceManager.wakeWordThreshold
                    val statusText = if (rms > 80f || score > 0.05f) {
                        String.format(
                            Locale.US,
                            "Đang nghe... Score: %.2f / Ngưỡng: %.2f (RMS: %d)",
                            score,
                            threshold,
                            rms.toInt()
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "Đang chờ 'javis'... (Ngưỡng: %.2f)",
                            threshold
                        )
                    }
                    onStatusChange(statusText, false, true)

                    val now = System.currentTimeMillis()
                    // Log ra chat khi có tiếng nói rõ rệt (rms > 300 hoặc score >= 0.10)
                    if ((score >= 0.12f || (rms > 350f && score >= 0.05f)) && (now - lastLoggedScoreTime > 1200L)) {
                        lastLoggedScoreTime = now
                        val logText = String.format(
                            Locale.US,
                            "🎙️ Mic nhận tiếng: Score=%.3f (Ngưỡng kích hoạt: %.2f | RMS: %d)",
                            score,
                            threshold,
                            rms.toInt()
                        )
                        Log.i(TAG, logText)
                        onLogMessage(logText, false, "SCORE")
                    }
                }
            }

            openWakeWord?.start { score ->
                val threshold = preferenceManager.wakeWordThreshold
                val triggerMsg = String.format(
                    Locale.US,
                    "🔥 ĐÃ BẮT ĐƯỢC TỪ KHÓA! Score: %.3f >= Ngưỡng: %.2f",
                    score,
                    threshold
                )
                Log.i(TAG, triggerMsg)
                onLogMessage(triggerMsg, false, "WAKE")
                mainHandler.post { onWakeWordTriggered() }
            }

            val initStatus = String.format(
                Locale.US,
                "Đang chờ gọi 'javis'... (Ngưỡng: %.2f)",
                currentThreshold
            )
            onStatusChange(initStatus, false, true)
            Log.i(TAG, "openWakeWord đang lắng nghe từ khóa 'javis' với ngưỡng $currentThreshold...")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi động openWakeWord", e)
            onLogMessage("Lỗi khởi tạo mô hình nhận diện giọng nói: ${e.message}", false, "LỖI")
            speaker.speak("Không thể tải mô hình nhận diện từ khóa")
        }
    }

    fun updateThreshold() {
        val newThreshold = preferenceManager.wakeWordThreshold
        openWakeWord?.updateThreshold(newThreshold)
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

        tWakeDetected = System.currentTimeMillis()
        Log.i(TAG, "⏱️ [T0 - Wake] Bắt được 'javis' lúc $tWakeDetected ms -> Bật viền sáng và phát beep")

        // 1. Tạm dừng ngay wake word detector để nhường mic cho SpeechRecognizer
        pauseHotwordDetector()

        // 2. Bật ngay hiệu ứng viền màn hình phát sáng đa sắc
        glowOverlayManager.show()

        // 3. Phát beep ngắn (120ms)
        tBeepStarted = System.currentTimeMillis()
        speaker.playWakeBeep()
        onStatusChange("Đang kích hoạt...", true, false)
        Log.i(TAG, "⏱️ [T1 - Beep] Phát beep lúc $tBeepStarted ms (+${tBeepStarted - tWakeDetected}ms từ T0)")

        // 4. Chờ beep phát xong (120ms) + 300ms = 420ms rồi mới bật SpeechRecognizer (tránh beep lấn mất chữ đầu)
        mainHandler.postDelayed({
            if (isRunning && !isListeningCommand && !isPausedForTts) {
                commandRetryCount = 0
                startCommandListening()
            }
        }, 420L)
    }

    private fun getOrCreateSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
                return null
            }
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createCommandRecognitionListener())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi tạo SpeechRecognizer", e)
                return null
            }
        }
        return speechRecognizer
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* bỏ qua */ }
        speechRecognizer = null
    }

    @Volatile private var isOfflineRetryAttempt = false

    private fun startCommandListening(isRetryOnline: Boolean = false) {
        isListeningCommand = true
        if (!isRetryOnline) {
            isOfflineRetryAttempt = false
        }
        tSttStart = System.currentTimeMillis()
        Log.i(TAG, "⏱️ [T2 - STT Start] Bật SpeechRecognizer (retryOnline=$isRetryOnline) lúc $tSttStart ms")

        onStatusChange("🎙️ ĐANG NGHE... BẠN NÓI ĐI!", true, false)
        HotwordService.start(context, "🎙️ Đang nghe lệnh...")
        glowOverlayManager.show()

        val recognizer = getOrCreateSpeechRecognizer()
        if (recognizer == null) {
            Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
            onLogMessage("Thiết bị chưa cài Google Speech Engine", false, "LỖI")
            speaker.speak("Chưa cài Google Speech trên máy này")
            isListeningCommand = false
            resumeHotwordDetector()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

            // Rút ngắn thời gian chờ kết thúc câu (500ms thay vì 1500ms mặc định)
            putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 600L)
            putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1000L)
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1000L)

            // Thử ưu tiên nhận diện offline ở lần thử đầu; nếu máy không có gói offline thì fallback online
            if (!isRetryOnline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        try {
            recognizer.startListening(intent)

            // Đặt lịch timeout 6 giây tự động xử lý nếu người dùng không nói gì
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            mainHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS)

            Log.d(TAG, "SpeechRecognizer đã bắt đầu lắng nghe (retryOnline=$isRetryOnline)")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startListening, thử tạo lại recognizer", e)
            destroySpeechRecognizer()
            if (!isRetryOnline) {
                mainHandler.postDelayed({
                    if (isRunning && !isPausedForTts) {
                        startCommandListening(isRetryOnline = true)
                    }
                }, 350L)
            } else {
                isListeningCommand = false
                resumeHotwordDetector()
            }
        }
    }

    private fun cancelCommandListening() {
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        isListeningCommand = false
        glowOverlayManager.hide()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) { /* bỏ qua */ }
    }

    private fun handleCommandTimeoutOrNoSpeech(reason: String) {
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        if (commandRetryCount < MAX_COMMAND_RETRIES && isRunning) {
            commandRetryCount++
            Log.w(TAG, "$reason -> Beep báo và nghe lại lần thứ $commandRetryCount")
            onStatusChange("Chưa nghe rõ, đang nghe lại...", true, false)
            onLogMessage("⚠️ Chưa nghe rõ câu lệnh, đang thử nghe lại...", false, "THỬ LẠI")

            // Beep báo và nghe lại 1 lần nữa thay vì ngủ luôn
            speaker.playWakeBeep()
            try { speechRecognizer?.cancel() } catch (_: Exception) {}

            mainHandler.postDelayed({
                if (isRunning && !isPausedForTts) {
                    startCommandListening()
                }
            }, 450L)
        } else {
            Log.w(TAG, "$reason -> Đã hết lượt nghe lại, quay lại chờ wake word")
            commandRetryCount = 0
            cancelCommandListening()
            glowOverlayManager.hide()
            resumeHotwordDetector(delayMs = 300)
        }
    }

    private fun createCommandRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            tSttReady = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T3 - STT Ready] Mic sẵn sàng nghe lệnh lúc $tSttReady ms (+${tSttReady - tSttStart}ms từ STT start, tổng: +${tSttReady - tWakeDetected}ms)")
            onStatusChange("🎙️ SẴN SÀNG! MỜI BẠN NÓI...", true, false)
        }

        override fun onBeginningOfSpeech() {
            val tSpeech = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T4 - Speaking] Người dùng bắt đầu nói lúc $tSpeech ms (+${tSpeech - tSttReady}ms từ khi mic sẵn sàng)")
            onStatusChange("🎙️ Đang thu âm câu lệnh...", true, false)
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Người dùng đã dứt câu — đang nhận diện kết quả...")
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            onStatusChange("Đang nhận diện...", true, false)
        }

        override fun onError(errorCode: Int) {
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            val errorMsg = describeSpeechError(errorCode)
            Log.w(TAG, "SpeechRecognizer báo lỗi ($errorCode): $errorMsg")

            // Nếu timeout hoặc không nghe rõ, beep và thử lại 1 lần nữa
            if (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH) {
                handleCommandTimeoutOrNoSpeech("Không nhận diện được giọng nói ($errorMsg)")
            } else if ((errorCode == SpeechRecognizer.ERROR_CLIENT || errorCode == SpeechRecognizer.ERROR_SERVER || errorCode == SpeechRecognizer.ERROR_AUDIO || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && !isOfflineRetryAttempt) {
                // Tự động thử lại với chế độ Online khi gói offline hoặc mic timing gặp lỗi trên các dòng máy khác nhau
                isOfflineRetryAttempt = true
                Log.w(TAG, "SpeechRecognizer gặp lỗi $errorCode, đang thử lại với chế độ Online...")
                destroySpeechRecognizer()
                mainHandler.postDelayed({
                    if (isRunning && !isPausedForTts) {
                        startCommandListening(isRetryOnline = true)
                    }
                }, 350L)
            } else {
                isOfflineRetryAttempt = false
                commandRetryCount = 0
                isListeningCommand = false
                glowOverlayManager.hide()
                if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_CLIENT) {
                    destroySpeechRecognizer()
                }
                resumeHotwordDetector(delayMs = 400)
            }
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            isListeningCommand = false

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            val tDone = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T5 - Final Result] Nhận diện lệnh thành công: \"$text\" lúc $tDone ms (Tổng chu trình: ${tDone - tWakeDetected}ms)")

            glowOverlayManager.hide()

            if (text.isNotEmpty()) {
                commandRetryCount = 0
                val now = System.currentTimeMillis()
                // Debounce 1 giây: Bỏ qua nếu lệnh trùng lặp trong vòng 1s
                if (text.equals(lastCommandText, ignoreCase = true) && (now - lastCommandTime < DEBOUNCE_MS)) {
                    Log.d(TAG, "Debounce: bỏ qua kết quả trùng lặp nhanh: \"$text\"")
                } else {
                    lastCommandText = text
                    lastCommandTime = now
                    onLogMessage("⏱️ Chu trình: Wake->Beep(+${tBeepStarted - tWakeDetected}ms) -> STT(+${tSttStart - tBeepStarted}ms) -> Sẵn sàng(+${tSttReady - tSttStart}ms) -> Xong(+${tDone - tSttReady}ms) | Tổng: ${tDone - tWakeDetected}ms", false, "TIMING")
                    onCommandRecognized(text)
                }
            } else {
                handleCommandTimeoutOrNoSpeech("Không có từ nào trong kết quả nhận diện")
                return
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
                onStatusChange("🎙️ $partial", true, false)

                // TĂNG TỐC ĐỘ NHẬN LỆNH: Ngay khi partial result chứa từ khóa lệnh đã khớp thì thực hiện luôn!
                val command = commandParser.parse(partial)
                if (command !is Command.Unknown && command !is Command.AskAi) {
                    val tDone = System.currentTimeMillis()
                    Log.i(TAG, "⚡ [T5 - Partial Instant] Khớp lệnh siêu tốc: \"$partial\" lúc $tDone ms (Tổng chu trình: ${tDone - tWakeDetected}ms)")
                    mainHandler.removeCallbacks(commandTimeoutRunnable)
                    isListeningCommand = false
                    glowOverlayManager.hide()
                    try { speechRecognizer?.stopListening() } catch (_: Exception) {}

                    val now = System.currentTimeMillis()
                    if (!partial.equals(lastCommandText, ignoreCase = true) || (now - lastCommandTime >= DEBOUNCE_MS)) {
                        lastCommandText = partial
                        lastCommandTime = now
                        commandRetryCount = 0
                        onLogMessage("⚡ Khớp lệnh tức thì: Wake->Beep(+${tBeepStarted - tWakeDetected}ms) -> STT(+${tSttStart - tBeepStarted}ms) -> Khớp(+${tDone - tSttStart}ms) | Tổng: ${tDone - tWakeDetected}ms", false, "TIMING")
                        onCommandRecognized(partial)
                    }

                    if (!isPausedForTts) {
                        resumeHotwordDetector(delayMs = 400)
                    }
                }
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
