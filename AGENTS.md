# JAVIS Agent Execution Policy, System Rules & Skills Framework

## 1. Always-Proceed Autonomous Execution Mode (Chế độ Tự Động Toàn Diện)
1. **Direct Execution Without Prompting**:
   - Automatically analyze requirements, modify code, generate configuration, create necessary files, execute build/test verification, and report complete results without prompting for confirmation on routine or obvious steps.
   - Do not invoke confirmation/question tools (`ask_question`) for standard development operations unless strictly blocked by missing user credentials.

2. **Compliance & Quality Assurance**:
   - Strict adherence to PROMPT v4 specifications: Wake word detection ("javis") via openWakeWord, audio focus optimization to prevent TikTok/media playback interruption, 100ms beep audio feedback.
   - Preserve legacy features: `JavisAccessibilityService`, `JavisTileService` (Quick Settings Tile), phone call / SMS integration, custom voice commands, and multi-provider AI chat clients.
   - Comprehensive error handling: Applications MUST NOT crash on network loss, null services, missing assets, or invalid inputs. Fallback to Vietnamese spoken feedback and UI logs.

---

## 2. Language & Localization Guidelines
- **Source Code, Identifiers & Comments (`lang: en`)**:
  - All source code, class names, method signatures, variable names, architecture documentation, and inline rule comments must be written in English.
  - Example: `// Handles audio focus acquisition and ducking behavior`
- **User Interface & Spoken Text (`lang: vi`)**:
  - All end-user facing strings, TTS responses, toast notifications, UI labels, dialog messages, and alert prompts must be strictly written in natural Vietnamese.
  - Example: `val toastMsg = "Bạn chưa bật quyền Trợ năng cho JAVIS"`

---

## 3. Architectural Rules & Core Policies

### 3.1 Audio Focus & Wake Word Engine
- **Wake Word Detection**:
  - Engine: `openWakeWord` running locally via ONNX Runtime (`com.github.msnilsen:openwakeword-android`).
  - Model Priority: High-precision custom ONNX model (`assets/javis.onnx`) fallback to built-in `HEY_JARVIS`.
  - Foreground Execution: Must run in a persistent `ForegroundService` with low latency audio streaming (`RECORD_AUDIO`).
- **Media Playback Protection (TikTok / Shorts / Reels)**:
  - DO NOT request global audio focus during wake word detection or speech command listening.
  - Audio Feedback: Play a short 100ms beep sound upon command recognition instead of long TTS responses where possible.
  - TTS Focus Strategy: Request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` only when actively reading out AI responses or system errors. Abandon focus immediately in `UtteranceProgressListener.onDone` / `onError`.
  - Mutual Exclusion: Disable wake word detector and SpeechRecognizer while TTS is speaking to prevent self-triggering feedback loops.

### 3.2 Perception Architecture & Security Guardrails
- **3-Tier Perception Model**:
  - *Tier 1 ($0, 5-15ms)*: Android Accessibility Node Tree structure parsing.
  - *Tier 2 ($0, 40-90ms)*: Local Google ML Kit OCR text recognition.
  - *Tier 3 (Cloud VLM)*: Ephemeral image upload to OpenAI-compatible Vision API endpoints.
- **Keystore & Privacy Security**:
  - **Secret Storage**: API keys MUST NEVER be stored as plaintext in `SharedPreferences`. Use Android Keystore AES-GCM (256-bit) via `KeystoreManager`.
  - **Screen Capture Privacy**: Ephemeral Bitmaps captured via `MediaProjection` must exist in RAM only (JPEG 75% compression, max 1080p resolution) and immediately call `.recycle()`. NEVER write screenshots to flash storage disk.
  - **PolicyGuard App Shielding**: Automatically block screen capture and command execution on banking apps (`com.vietcombank.*`, `com.mbmobile`, etc.), crypto wallets (`com.binance.*`, `io.metamask`), and credential managers (`bitwarden`, `keepass`).

### 3.3 Accessibility & Command Execution
- **Scroll Orientation Conventions**:
  - "Lướt lên" / "Vuốt lên" / "Cuộn lên": View newer content below (performs swipe gesture from bottom to top or `ACTION_SCROLL_FORWARD`).
  - "Lướt xuống" / "Vuốt xuống" / "Cuộn xuống": View previous content above (performs swipe gesture from top to bottom or `ACTION_SCROLL_BACKWARD`).
- **App Launch Package Routing**:
  - YouTube: `com.google.android.youtube`
  - TikTok: `com.zhiliaoapp.musically` (or package query fuzzy lookup)
  - If target package is uninstalled: Speak feedback string `"Chưa cài đặt [Tên ứng dụng] trên thiết bị này"`.

---

## 4. Skills & Capabilities Framework

The agent workspace includes modular skills under `.agents/skills/`. Each skill provides specialized procedures, design patterns, and runnable snippets.

### Skill 1: `project-memory`
- **Path**: `.agents/skills/project-memory/SKILL.md`
- **Purpose**: Tracks architectural decisions, room migration histories, user preferences, and bug fix summaries.
- **Usage**: Reference before introducing structural changes to SQLite schemas or core services.

### Skill 2: `tampermonkey-generator`
- **Path**: `.agents/skills/tampermonkey-generator/SKILL.md`
- **Purpose**: Generates userscripts for browser automation with floating control badges, keyboard shortcuts, and clean console logging.

### Skill 3: `web-api-interceptor`
- **Path**: `.agents/skills/web-api-interceptor/SKILL.md`
- **Purpose**: Provides snippets for intercepting fetch/XHR requests to extract API endpoints and security headers.

### Skill 4: `web-auto-clicker`
- **Path**: `.agents/skills/web-auto-clicker/SKILL.md`
- **Purpose**: Automates DOM clicks, handles React synthetic events, and provides multi-channel script stopping (`Escape` key, UI floating badge, console stop command).

### Skill 5: `javis-voice-command-parser`
- **Purpose**: Normalizes input speech strings, strips Vietnamese diacritics, strips wake word prefixes, and maps command intents.

### Skill 6: `javis-accessibility-gestures`
- **Purpose**: Dispatches touch gestures (swipes, taps, scrolls) via `AccessibilityService.dispatchGesture` when standard node actions fail.

---

## 5. Implementation Code Examples

### 5.1 Hotword & Audio Focus Management (`HotwordManager.kt`)
```kotlin
package com.dinh.javis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/**
 * Manages hotword wake word detection while preserving background media playback (TikTok/Shorts).
 * Enforces rule: English code logic & comments, Vietnamese UI/Speech output.
 */
class HotwordManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ttsEngine: TextToSpeech? = null
    private var isListeningForWakeWord = false

    // Audio focus request configured for transient ducking to minimize TikTok interruption
    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()
        } else {
            null
        }
    }

    /**
     * Initializes TextToSpeech with Vietnamese locale and utterance progress handling.
     */
    fun initTts(onSuccess: () -> Unit) {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = java.util.Locale("vi", "VN")
                ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Pause wake word detection while TTS is speaking to prevent self-triggering
                        pauseWakeWordDetector()
                    }

                    override fun onDone(utteranceId: String?) {
                        abandonAudioFocus()
                        resumeWakeWordDetector()
                    }

                    override fun onError(utteranceId: String?) {
                        abandonAudioFocus()
                        resumeWakeWordDetector()
                    }
                })
                onSuccess()
            } else {
                Log.e("HotwordManager", "Khởi tạo TTS thất bại")
            }
        }
    }

    /**
     * Speaks out user-facing response in Vietnamese.
     */
    fun speakVietnamese(text: String, utteranceId: String = "JAVIS_SPEECH") {
        requestAudioFocus()
        ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Play short 100ms confirmation beep without acquiring heavy audio focus.
     */
    fun playConfirmationBeep() {
        // High-frequency short beep audio playback
        Log.d("HotwordManager", "Phản hồi âm thanh: Đã nhận lệnh")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun pauseWakeWordDetector() {
        isListeningForWakeWord = false
    }

    private fun resumeWakeWordDetector() {
        isListeningForWakeWord = true
    }
}
```

### 5.2 Voice Command Router & Diacritics Parser (`CommandParser.kt`)
```kotlin
package com.dinh.javis.command

import java.text.Normalizer
import java.util.regex.Pattern

sealed class CommandResult {
    data class Scroll(val isForward: Boolean) : CommandResult()
    data class OpenApp(val packageName: String, val appNameVi: String) : CommandResult()
    data class VolumeAdjust(val isIncrease: Boolean) : CommandResult()
    data class Unknown(val rawQuery: String) : CommandResult()
}

/**
 * Parses Vietnamese spoken text, normalizes diacritics, removes wake word prefix, and resolves actions.
 */
object CommandParser {

    private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /**
     * Strip Vietnamese diacritics for flexible fuzzy command matching.
     */
    fun removeDiacritics(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(normalized)
            .replaceAll("")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()
            .trim()
    }

    /**
     * Parse raw voice string into executable command intent.
     */
    fun parseCommand(rawSpeech: String): CommandResult {
        // Strip wake word prefix if present (e.g., "javis lướt lên" -> "lướt lên")
        val cleanedSpeech = rawSpeech.replace("(?i)^(\\bjavis\\b|\\bjarvis\\b|\\be javis\\b)\\s*".toRegex(), "")
        val normalized = removeDiacritics(cleanedSpeech)

        return when {
            // Scroll Up command (View new content below)
            normalized.contains("luot len") || normalized.contains("vuot len") || normalized.contains("cuon len") -> {
                CommandResult.Scroll(isForward = true)
            }
            // Scroll Down command (View previous content above)
            normalized.contains("luot xhuong") || normalized.contains("luot xuong") || 
            normalized.contains("vuot xuong") || normalized.contains("cuon xuong") -> {
                CommandResult.Scroll(isForward = false)
            }
            // Launch TikTok app
            normalized.contains("mo tiktok") || normalized.contains("mo tik tok") -> {
                CommandResult.OpenApp(TIKTOK_PACKAGE, "TikTok")
            }
            // Launch YouTube app
            normalized.contains("mo youtube") || normalized.contains("mo yu tu") -> {
                CommandResult.OpenApp(YOUTUBE_PACKAGE, "YouTube")
            }
            // Increase volume
            normalized.contains("tang am luong") || normalized.contains("to len") || normalized.contains("am luong to len") -> {
                CommandResult.VolumeAdjust(isIncrease = true)
            }
            // Decrease volume
            normalized.contains("giam am luong") || normalized.contains("nho lai") || normalized.contains("nho xuong") -> {
                CommandResult.VolumeAdjust(isIncrease = false)
            }
            else -> CommandResult.Unknown(rawQuery = rawSpeech)
        }
    }
}
```

### 5.3 Accessibility Service Gestures & Fallbacks (`JavisAccessibilityService.kt`)
```kotlin
package com.dinh.javis.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Android Accessibility Service executing UI actions, gestures, and app routing.
 */
class JavisAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing logic
    }

    override fun onInterrupt() {
        // Interruption handling
    }

    /**
     * Executes scroll forward/backward with gesture fallback if node actions are unavailable.
     */
    fun performScroll(isForward: Boolean): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            showToastVietnamese("Không thể đọc màn hình. Hãy đảm bảo ứng dụng đang mở.")
            return false
        }

        // Try standard Accessibility scroll action
        val action = if (isForward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null && scrollableNode.performAction(action)) {
            return true
        }

        // Fallback: Perform vertical swipe gesture
        return dispatchSwipeGesture(isSwipeUp = isForward)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun dispatchSwipeGesture(isSwipeUp: Boolean): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val path = Path()
        if (isSwipeUp) {
            // Swipe from bottom to top to view new content below
            path.moveTo(width / 2f, height * 0.75f)
            path.lineTo(width / 2f, height * 0.25f)
        } else {
            // Swipe from top to bottom to view content above
            path.moveTo(width / 2f, height * 0.25f)
            path.lineTo(width / 2f, height * 0.75f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun showToastVietnamese(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
```

---

## 6. Development & Verification Checklist
- [x] Code comments and internal logic specified in English (`code lang en`).
- [x] UI labels, spoken feedback, and toast notifications localized in Vietnamese (`UI text lang vi`).
- [x] openWakeWord detection configured without cloud API key requirements.
- [x] Transient audio focus requested only during active TTS to prevent TikTok video pausing.
- [x] Gesture fallbacks (`dispatchGesture`) implemented for Accessibility scrolling.
