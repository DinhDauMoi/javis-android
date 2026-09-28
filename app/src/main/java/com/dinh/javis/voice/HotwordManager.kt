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

    @Volatile private var isRunning = false                // User enabled or disabled flag
    @Volatile private var isListeningCommand = false       // Currently running SpeechRecognizer listening for single command
    @Volatile private var isPausedForTts = false          // Temporarily paused while TTS is speaking

    // Session coordinator to manage generational session IDs, debouncing, and cancellation (R13)
    private val sessionCoordinator = VoiceSessionCoordinator(debounceIntervalMs = 350L)
    private var pendingCommandStartRunnable: Runnable? = null

    // Retry counter for speech command recognition when no speech is detected (max 1 retry)
    private var commandRetryCount = 0
    private val MAX_COMMAND_RETRIES = 1

    // Latency profiling timestamps
    private var tWakeDetected = 0L
    private var tBeepStarted = 0L
    private var tSttStart = 0L
    private var tSttReady = 0L

    // Debounce duration to prevent duplicate command execution within 1 second (1000ms)
    private var lastCommandText = ""
    private var lastCommandTime = 0L
    private val DEBOUNCE_MS = 1000L

    // 6-second timeout for spoken command execution
    private val COMMAND_TIMEOUT_MS = 6000L
    private val commandTimeoutRunnable = Runnable {
        handleCommandTimeoutOrNoSpeech("Command listening timeout (6s)")
    }

    init {
        // Listen to TTS speaking state so microphone and speaker do not overlap
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
        sessionCoordinator.invalidateSession()
        pendingCommandStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingCommandStartRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        glowOverlayManager.hide()

        // Stop and release OpenWakeWord engine
        try {
            openWakeWord?.stop()
            openWakeWord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping openWakeWord", e)
        }
        openWakeWord = null

        // Stop and destroy SpeechRecognizer
        cancelCommandListening()
        destroySpeechRecognizer()

        // Stop persistent foreground service
        HotwordService.stop(context)

        onStatusChange("Đã tắt chờ gọi 'javis'", false, false)
        Log.i(TAG, "HotwordManager fully stopped, mic released")
    }

    /**
     * Triggers one-shot voice command listening manually (e.g., via Mic button or Tile).
     * Serialized, debounced, and idempotent: rapid taps do not create concurrent recognizers.
     * Tapping while already listening cancels the active session cleanly (R13).
     */
    fun triggerOneShotCommand() {
        val decision = sessionCoordinator.onManualTrigger(isListeningCommand || pendingCommandStartRunnable != null)
        when (decision) {
            is VoiceSessionCoordinator.TriggerDecision.Debounced -> {
                Log.d(TAG, "Debounce rapid triggerOneShotCommand tap")
                return
            }
            is VoiceSessionCoordinator.TriggerDecision.CancelActive -> {
                Log.i(TAG, "User tapped mic button while listening/starting -> Cancelling voice session")
                pendingCommandStartRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingCommandStartRunnable = null
                cancelCommandListening()
                destroySpeechRecognizer()
                onStatusChange("Đã dừng nghe", false, false)
                if (isRunning && !isPausedForTts) {
                    resumeHotwordDetector(delayMs = 300)
                }
                return
            }
            is VoiceSessionCoordinator.TriggerDecision.StartNew -> {
                val sessionId = decision.newSessionId
                if (!isRunning) {
                    start()
                }
                tWakeDetected = System.currentTimeMillis()
                tBeepStarted = tWakeDetected
                pauseHotwordDetector()
                speaker.playWakeBeep()
                onStatusChange("Đang kích hoạt...", true, false)
                glowOverlayManager.show()

                pendingCommandStartRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    pendingCommandStartRunnable = null
                    if (isRunning && sessionCoordinator.isSessionValid(sessionId) && !isPausedForTts) {
                        commandRetryCount = 0
                        startCommandListening(sessionId = sessionId)
                    }
                }
                pendingCommandStartRunnable = runnable
                mainHandler.postDelayed(runnable, 420L)
            }
        }
    }

    /**
     * Refreshes the current voice status to UI listeners (e.g. on Activity resume or foldable screen transition).
     */
    fun refreshStatus() {
        val statusText = when {
            isListeningCommand -> "🎙️ ĐANG NGHE... BẠN NÓI ĐI!"
            isRunning -> "Đang chờ gọi \"javis\"..."
            else -> "Đã tắt chờ gọi 'javis'"
        }
        onStatusChange(statusText, isListeningCommand, isRunning)
    }

    // =========================================================================
    // INITIALIZE & EXECUTE OPENWAKEWORD ENGINE
    // =========================================================================

    private fun initAndStartHotword() {
        if (!isRunning || isListeningCommand || isPausedForTts) return

        try {
            val currentThreshold = preferenceManager.wakeWordThreshold

            if (openWakeWord == null) {
                val builder = OpenWakeWord.Builder(context)

                // Model resolution: Prefer javis.onnx in assets, fallback to built-in HEY_JARVIS
                val hasCustomModel = try {
                    context.assets.list("")?.contains("javis.onnx") == true
                } catch (e: Exception) {
                    false
                }

                if (hasCustomModel) {
                    Log.i(TAG, "Using custom model: javis.onnx from assets directory")
                    builder.setModelAsset("javis.onnx")
                } else {
                    Log.i(TAG, "Using built-in model HEY_JARVIS ('Hey Jarvis' ≈ 'javis')")
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
    // HANDLE WAKE WORD DETECTION ("JAVIS")
    // =========================================================================

    private fun onWakeWordTriggered() {
        if (!isRunning || isListeningCommand) return

        tWakeDetected = System.currentTimeMillis()
        Log.i(TAG, "⏱️ [T0 - Wake] Detected 'javis' at $tWakeDetected ms -> Enabling glow overlay and playing beep")

        // 1. Immediately pause wake word detector to hand over mic to SpeechRecognizer
        pauseHotwordDetector()

        // 2. Enable multicolord glowing edge effect overlay
        glowOverlayManager.show()

        // 3. Play short 120ms beep sound
        tBeepStarted = System.currentTimeMillis()
        speaker.playWakeBeep()
        onStatusChange("Đang kích hoạt...", true, false)
        Log.i(TAG, "⏱️ [T1 - Beep] Played beep at $tBeepStarted ms (+${tBeepStarted - tWakeDetected}ms from T0)")

        // 4. Wait for beep completion (120ms) + buffer (300ms) = 420ms before starting SpeechRecognizer
        pendingCommandStartRunnable?.let { mainHandler.removeCallbacks(it) }
        val sessionId = sessionCoordinator.nextSession()
        val runnable = Runnable {
            pendingCommandStartRunnable = null
            if (isRunning && sessionCoordinator.isSessionValid(sessionId) && !isListeningCommand && !isPausedForTts) {
                commandRetryCount = 0
                startCommandListening(sessionId = sessionId)
            }
        }
        pendingCommandStartRunnable = runnable
        mainHandler.postDelayed(runnable, 420L)
    }

    private fun getOrCreateSpeechRecognizer(sessionId: Long = sessionCoordinator.currentSessionId): SpeechRecognizer? {
        if (speechRecognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
                return null
            }
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createCommandRecognitionListener(sessionId))
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
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speechRecognizer", e)
        }
        speechRecognizer = null
    }

    @Volatile private var isOfflineRetryAttempt = false

    private fun startCommandListening(isRetryOnline: Boolean = false, sessionId: Long = sessionCoordinator.currentSessionId) {
        if (!sessionCoordinator.isSessionValid(sessionId)) {
            Log.d(TAG, "Dropping stale startCommandListening call ($sessionId vs active ${sessionCoordinator.currentSessionId})")
            return
        }
        isListeningCommand = true
        if (!isRetryOnline) {
            isOfflineRetryAttempt = false
        }
        tSttStart = System.currentTimeMillis()
        Log.i(TAG, "⏱️ [T2 - STT Start] Bật SpeechRecognizer (retryOnline=$isRetryOnline) lúc $tSttStart ms")

        onStatusChange("🎙️ ĐANG NGHE... BẠN NÓI ĐI!", true, false)
        HotwordService.start(context, "🎙️ Đang nghe lệnh...")
        glowOverlayManager.show()

        val recognizer = getOrCreateSpeechRecognizer(sessionId)
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

            // Shorten silence length to complete recognition faster (600ms instead of 1500ms default)
            putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 600L)
            putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1000L)
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1000L)

            // Prefer offline recognition on first attempt; fallback to online if offline package is missing
            if (!isRetryOnline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        try {
            recognizer.startListening(intent)

            // Schedule 6-second timeout automatically handled if user does not speak
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
        sessionCoordinator.invalidateSession()
        pendingCommandStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingCommandStartRunnable = null
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        isListeningCommand = false
        glowOverlayManager.hide()
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun handleCommandTimeoutOrNoSpeech(reason: String) {
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        if (commandRetryCount < MAX_COMMAND_RETRIES && isRunning) {
            commandRetryCount++
            Log.w(TAG, "$reason -> Beep báo và nghe lại lần thứ $commandRetryCount")
            onStatusChange("Chưa nghe rõ, đang nghe lại...", true, false)
            onLogMessage("⚠️ Chưa nghe rõ câu lệnh, đang thử nghe lại...", false, "THỬ LẠI")

            // Beep and retry command listening once instead of immediately going idle
            speaker.playWakeBeep()
            try { speechRecognizer?.cancel() } catch (_: Exception) {}

            val retrySessionId = sessionCoordinator.nextSession()
            mainHandler.postDelayed({
                if (isRunning && sessionCoordinator.isSessionValid(retrySessionId) && !isPausedForTts) {
                    startCommandListening(sessionId = retrySessionId)
                }
            }, 450L)
        } else {
            Log.w(TAG, "$reason -> Max retries reached, resuming wake word detection")
            commandRetryCount = 0
            cancelCommandListening()
            glowOverlayManager.hide()
            resumeHotwordDetector(delayMs = 300)
        }
    }

    private fun createCommandRecognitionListener(sessionId: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            tSttReady = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T3 - STT Ready] Mic sẵn sàng nghe lệnh lúc $tSttReady ms (+${tSttReady - tSttStart}ms từ STT start, tổng: +${tSttReady - tWakeDetected}ms)")
            onStatusChange("🎙️ SẴN SÀNG! MỜI BẠN NÓI...", true, false)
        }

        override fun onBeginningOfSpeech() {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            val tSpeech = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T4 - Speaking] Người dùng bắt đầu nói lúc $tSpeech ms (+${tSpeech - tSttReady}ms từ khi mic sẵn sàng)")
            onStatusChange("🎙️ Đang thu âm câu lệnh...", true, false)
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            Log.d(TAG, "Người dùng đã dứt câu — đang nhận diện kết quả...")
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            onStatusChange("Đang nhận diện...", true, false)
        }

        override fun onError(errorCode: Int) {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            val errorMsg = describeSpeechError(errorCode)
            Log.w(TAG, "SpeechRecognizer báo lỗi ($errorCode): $errorMsg")

            // If timeout or unrecognized speech, beep and retry once instead of sleeping
            if (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH) {
                handleCommandTimeoutOrNoSpeech("Speech recognition unrecognized ($errorMsg)")
            } else if ((errorCode == SpeechRecognizer.ERROR_CLIENT || errorCode == SpeechRecognizer.ERROR_SERVER || errorCode == SpeechRecognizer.ERROR_AUDIO || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && !isOfflineRetryAttempt) {
                // Automatically retry with Online mode when offline package or mic timing fails on specific devices
                isOfflineRetryAttempt = true
                Log.w(TAG, "SpeechRecognizer encountered error $errorCode, retrying with Online mode...")
                destroySpeechRecognizer()
                val retrySessionId = sessionCoordinator.nextSession()
                mainHandler.postDelayed({
                    if (isRunning && sessionCoordinator.isSessionValid(retrySessionId) && !isPausedForTts) {
                        startCommandListening(isRetryOnline = true, sessionId = retrySessionId)
                    }
                }, 350L)
            } else {
                isOfflineRetryAttempt = false
                commandRetryCount = 0
                isListeningCommand = false
                glowOverlayManager.hide()
                if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_CLIENT) {
                    destroySpeechRecognizer()
                    onStatusChange("Dịch vụ giọng nói đang bận, vui lòng thử lại sau giây lát", false, false)
                }
                resumeHotwordDetector(delayMs = 400)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            isListeningCommand = false

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            val tDone = System.currentTimeMillis()
            Log.i(TAG, "⏱️ [T5 - Final Result] Command recognition succeeded: \"$text\" at $tDone ms (Total cycle: ${tDone - tWakeDetected}ms)")

            glowOverlayManager.hide()

            if (text.isNotEmpty()) {
                commandRetryCount = 0
                val now = System.currentTimeMillis()
                // 1-second Debounce: Ignore duplicate commands within 1s window
                if (text.equals(lastCommandText, ignoreCase = true) && (now - lastCommandTime < DEBOUNCE_MS)) {
                    Log.d(TAG, "Debounce: skipping rapid duplicate command: \"$text\"")
                } else {
                    lastCommandText = text
                    lastCommandTime = now
                    onLogMessage("⏱️ Chu trình: Wake->Beep(+${tBeepStarted - tWakeDetected}ms) -> STT(+${tSttStart - tBeepStarted}ms) -> Sẵn sàng(+${tSttReady - tSttStart}ms) -> Xong(+${tDone - tSttReady}ms) | Tổng: ${tDone - tWakeDetected}ms", false, "TIMING")
                    onCommandRecognized(text)
                }
            } else {
                handleCommandTimeoutOrNoSpeech("No text recognized in results")
                return
            }

            // Resume wake word detector after 400ms if TTS is not currently speaking
            if (!isPausedForTts) {
                resumeHotwordDetector(delayMs = 400)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!sessionCoordinator.isSessionValid(sessionId)) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim() ?: ""
            if (partial.isNotEmpty()) {
                onStatusChange("🎙️ $partial", true, false)

                // INSTANT COMMAND MATCHING: Execute immediately when partial result matches a known command keyword
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
