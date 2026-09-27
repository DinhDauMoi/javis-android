package com.dinh.javis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Wrapper quản lý chuyển văn bản thành giọng nói (TextToSpeech) tiếng Việt
 * và phát âm thanh beep phản hồi ngắn (100ms) không chiếm audio focus.
 *
 * Tối ưu giảm thiểu TikTok dừng video (Audio Focus):
 * - Khi TTS nói: dùng AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (không cướp hẳn focus).
 * - Khi TTS xong/lỗi: gọi abandonAudioFocusRequest ngay lập tức trong onDone/onError.
 * - Khi phát lệnh thành công: chỉ beep ngắn 100ms qua ToneGenerator, KHÔNG gọi TTS.
 * - Tự động dừng micro khi TTS nói và bật lại sau khi xong (tránh micro tự nghe giọng app).
 */
class Speaker(private val context: Context) : TextToSpeech.OnInitListener {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var pendingSpeech: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    /** Tone generator cho các âm beep ngắn phản hồi */
    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    } catch (e: Exception) {
        Log.w(TAG, "Không thể khởi tạo ToneGenerator", e)
        null
    }

    /**
     * Gán ContinuousVoiceListener nếu dùng cơ chế nghe liên tục cũ
     */
    var continuousListener: ContinuousVoiceListener? = null

    /**
     * Callbacks thông báo khi TTS bắt đầu / kết thúc nói để HotwordManager tạm dừng / resume micro
     */
    var onSpeechStarted: (() -> Unit)? = null
    var onSpeechFinished: (() -> Unit)? = null

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
                    Log.d(TAG, "TTS bắt đầu phát âm: $utteranceId")
                    onSpeechStarted?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS xong — giải phóng audio focus và resume micro")
                    abandonDuckFocus()
                    continuousListener?.resumeAfterTts()
                    onSpeechFinished?.invoke()
                    pendingOnDone?.invoke()
                    pendingOnDone = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Lỗi phát âm Utterance: $utteranceId")
                    abandonDuckFocus()
                    continuousListener?.resumeAfterTts()
                    onSpeechFinished?.invoke()
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
     * Xin Audio Focus dạng TRANSIENT_MAY_DUCK:
     * Ứng dụng khác (TikTok, YouTube, Spotify) chỉ giảm nhẹ âm lượng (duck) chứ không dừng hẳn video.
     */
    private fun requestDuckFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* tạm thời không cần can thiệp */ }
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi xin audio focus", e)
            false
        }
    }

    /**
     * Trả ngay Audio Focus cho app khác ngay khi dứt câu nói
     */
    private fun abandonDuckFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng audio focus", e)
        }
    }

    /**
     * Phát tiếng beep ngắn 100ms khi nhận lệnh thành công
     * Mục đích: KHÔNG cướp audio focus, giúp TikTok tiếp tục phát không bị khựng video.
     */
    fun playAckBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi phát tiếng beep xác nhận", e)
        }
    }

    /**
     * Phát âm thanh ngắn khi phát hiện từ khóa "javis" thức dậy (beep hoặc tone)
     */
    fun playWakeBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi phát tiếng wake beep", e)
        }
    }

    /**
     * Phát âm thanh đọc phản hồi bằng tiếng Việt.
     * Tự động pause micro trước khi nói và resume sau khi nói xong.
     * Chỉ dùng cho thông báo lỗi, câu hỏi thời gian hoặc câu trả lời AI.
     * Tự động lọc sạch ký hiệu Markdown (**, *, #, ```, ``, v.v.) để TTS đọc giọng nói tự nhiên.
     *
     * @param text  Văn bản cần đọc
     * @param onDone Callback khi đọc xong (tuỳ chọn)
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val cleanSpokenText = com.dinh.javis.utils.TextNormalizer.stripMarkdownForSpeech(text)
        if (cleanSpokenText.isBlank()) {
            onDone?.invoke()
            return
        }

        // Tạm dừng micro TRƯỚC khi nói
        continuousListener?.pauseForTts()
        onSpeechStarted?.invoke()

        if (!isInitialized) {
            pendingSpeech = cleanSpokenText
            pendingOnDone = onDone
            return
        }

        speakInternal(cleanSpokenText, onDone)
    }

    private fun speakInternal(text: String, onDone: (() -> Unit)?) {
        this.pendingOnDone = onDone
        try {
            // Xin focus duck trước khi phát
            requestDuckFocus()

            val utteranceId = "JAVIS_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi trong quá trình speak()", e)
            abandonDuckFocus()
            continuousListener?.resumeAfterTts()
            onSpeechFinished?.invoke()
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
            abandonDuckFocus()
            continuousListener?.resumeAfterTts()
            onSpeechFinished?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng TTS", e)
        }
    }

    /**
     * Giải phóng tài nguyên khi Activity/Service onDestroy
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            abandonDuckFocus()
            toneGenerator?.release()
            toneGenerator = null
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
