package com.openwakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * OpenWakeWord Engine cho Android sử dụng ONNX Runtime.
 *
 * Chạy toàn bộ pipeline suy luận 3 giai đoạn on-device:
 * 1. Mel-spectrogram: sinh spectrogram từ tín hiệu âm thanh thô 16kHz
 * 2. Speech Embedding: sinh vector nhúng 96 chiều
 * 3. Wake Word Classifier: phân loại từ khóa đánh thức ("hey jarvis" / "javis")
 *
 * Tích hợp logging chi tiết từng bước & streaming detection score realtime.
 */
class OpenWakeWord private constructor(
    private val context: Context,
    private val wakeWordModelSource: ModelSource,
    private var threshold: Float,
    private val debounceMs: Long,
) {

    enum class BuiltInModel(val assetPath: String, val displayName: String) {
        HEY_JARVIS("openwakeword/hey_jarvis_v0.1.onnx", "Hey Jarvis"),
        ALEXA("openwakeword/alexa_v0.1.onnx", "Alexa"),
        HEY_MYCROFT("openwakeword/hey_mycroft_v0.1.onnx", "Hey Mycroft"),
    }

    fun interface OnDetectionListener {
        fun onDetected(score: Float)
    }

    fun interface OnScoreListener {
        fun onScore(score: Float, rms: Float)
    }

    fun interface OnStatusListener {
        fun onStatus(step: String, isSuccess: Boolean, detail: String)
    }

    class Builder(private val context: Context) {
        private var modelSource: ModelSource = ModelSource.BuiltIn(BuiltInModel.HEY_JARVIS)
        private var threshold = 0.30f // Mặc định 0.3 theo yêu cầu
        private var debounceMs = 1500L

        fun setModel(model: BuiltInModel) = apply {
            modelSource = ModelSource.BuiltIn(model)
        }

        fun setModelAsset(assetPath: String) = apply {
            modelSource = ModelSource.Asset(assetPath)
        }

        fun setModelBytes(bytes: ByteArray) = apply {
            modelSource = ModelSource.Raw(bytes)
        }

        fun setThreshold(threshold: Float) = apply {
            this.threshold = threshold.coerceIn(0.01f, 0.99f)
        }

        fun setDebounceMs(ms: Long) = apply {
            this.debounceMs = ms.coerceAtLeast(0)
        }

        fun build(): OpenWakeWord = OpenWakeWord(
            context.applicationContext,
            modelSource,
            threshold,
            debounceMs,
        )
    }

    internal sealed class ModelSource {
        data class BuiltIn(val model: BuiltInModel) : ModelSource()
        data class Asset(val path: String) : ModelSource()
        data class Raw(val bytes: ByteArray) : ModelSource()
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1280 // 80 ms
        private const val MEL_CONTEXT_SAMPLES = 480 // 160 * 3 overlap
        private const val MEL_BINS = 32
        private const val MEL_WINDOW_FRAMES = 76
        private const val EMBEDDING_DIM = 96
        private const val FEATURE_WINDOW = 16
        private const val FEATURE_BUFFER_MAX = 120
        private const val MEL_BUFFER_MAX = 970
        private const val SKIP_INITIAL_PREDICTIONS = 5
        private const val RAW_BUFFER_SECONDS = 10
    }

    private var ortEnv: OrtEnvironment? = null
    private var melSpecSession: OrtSession? = null
    private var melSpecInputName: String? = null
    private var embeddingSession: OrtSession? = null
    private var embeddingInputName: String? = null
    private var wakeWordSession: OrtSession? = null
    private var wakeWordInputName: String? = null

    private var audioRecord: AudioRecord? = null
    private var processingThread: Thread? = null
    @Volatile private var isRunning = false

    private var detectionListener: OnDetectionListener? = null
    private var scoreListener: OnScoreListener? = null
    private var statusListener: OnStatusListener? = null

    private var lastDetectionTime = 0L
    private var predictionCount = 0

    private lateinit var rawBuffer: FloatArray
    private var rawWritePos = 0
    private var rawTotalWritten = 0L

    private val melBuffer = ArrayList<FloatArray>()
    private val featureBuffer = ArrayList<FloatArray>()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setScoreListener(listener: OnScoreListener?) {
        this.scoreListener = listener
    }

    fun setStatusListener(listener: OnStatusListener?) {
        this.statusListener = listener
    }

    fun updateThreshold(newThreshold: Float) {
        this.threshold = newThreshold.coerceIn(0.01f, 0.99f)
        Log.i(TAG, "Đã cập nhật threshold mới: $threshold")
    }

    /**
     * Bắt đầu luồng nghe và nhận diện từ khóa
     */
    fun start(listener: OnDetectionListener) {
        if (isRunning) return
        this.detectionListener = listener
        isRunning = true

        processingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                notifyStatus("Khởi tạo Model ONNX", true, "Đang tải các file model...")
                if (melSpecSession == null) {
                    initModels()
                }
                initBuffers()

                if (!initAudioRecord()) {
                    val msg = "AudioRecord thất bại: Hãy kiểm tra quyền RECORD_AUDIO hoặc mic đang bị chiếm dụng"
                    Log.e(TAG, msg)
                    notifyStatus("AudioRecord", false, msg)
                    return@Thread
                }
                notifyStatus("AudioRecord", true, "Khởi tạo mic 16kHz thành công, bắt đầu nhận diện...")

                audioLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi luồng xử lý OpenWakeWord", e)
                notifyStatus("Lỗi Xử Lý", false, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                releaseAudioRecord()
            }
        }, "OpenWakeWordEngine")

        processingThread?.start()
    }

    /**
     * Dừng lắng nghe
     */
    fun stop() {
        isRunning = false
        try {
            processingThread?.join(1500)
        } catch (_: Exception) {}
        processingThread = null
        releaseAudioRecord()
    }

    /**
     * Giải phóng tài nguyên
     */
    fun release() {
        stop()
        try {
            wakeWordSession?.close()
            embeddingSession?.close()
            melSpecSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi giải phóng phiên ONNX", e)
        }
        wakeWordSession = null
        embeddingSession = null
        melSpecSession = null
        ortEnv = null
        detectionListener = null
        scoreListener = null
        statusListener = null
    }

    private fun notifyStatus(step: String, isSuccess: Boolean, detail: String) {
        Log.i(TAG, "[$step] Success=$isSuccess: $detail")
        val listener = statusListener
        mainHandler.post { listener?.onStatus(step, isSuccess, detail) }
    }

    // ------------------------------------------------------------------
    // Nạp Models ONNX
    // ------------------------------------------------------------------

    private fun initModels() {
        // Kiểm tra danh sách file assets trước khi nạp
        try {
            val assetList = context.assets.list("openwakeword") ?: emptyArray()
            Log.i(TAG, "Kiểm tra APK assets/openwakeword: tìm thấy ${assetList.size} files: [${assetList.joinToString(", ")}]")
            notifyStatus("Asset Check", true, "Đã kiểm tra thư mục assets/openwakeword: ${assetList.joinToString(", ")}")
        } catch (e: Exception) {
            Log.w(TAG, "Không thể duyệt assets/openwakeword", e)
        }

        ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }

        // 1. Mel-spectrogram
        val melBytes = loadAssetWithVerify("openwakeword/melspectrogram.onnx")
        melSpecSession = ortEnv!!.createSession(melBytes, opts)
        melSpecInputName = melSpecSession!!.inputNames.first()
        Log.i(TAG, "Đã nạp melspectrogram.onnx (${melBytes.size} bytes), Input: $melSpecInputName, Output: ${melSpecSession!!.outputNames}")

        // 2. Embedding Model
        val embBytes = loadAssetWithVerify("openwakeword/embedding_model.onnx")
        embeddingSession = ortEnv!!.createSession(embBytes, opts)
        embeddingInputName = embeddingSession!!.inputNames.first()
        Log.i(TAG, "Đã nạp embedding_model.onnx (${embBytes.size} bytes), Input: $embeddingInputName, Output: ${embeddingSession!!.outputNames}")

        // 3. Wake Word Classifier
        val (modelName, wwBytes) = when (val src = wakeWordModelSource) {
            is ModelSource.BuiltIn -> Pair(src.model.assetPath, loadAssetWithVerify(src.model.assetPath))
            is ModelSource.Asset -> Pair(src.path, loadAssetWithVerify(src.path))
            is ModelSource.Raw -> Pair("raw_bytes", src.bytes)
        }
        wakeWordSession = ortEnv!!.createSession(wwBytes, opts)
        wakeWordInputName = wakeWordSession!!.inputNames.first()
        Log.i(TAG, "Đã nạp wake word model: $modelName (${wwBytes.size} bytes), Input: $wakeWordInputName, Output: ${wakeWordSession!!.outputNames}")

        notifyStatus("Model Load", true, "Đã nạp thành công 3 models ONNX (Spectrogram: ${melBytes.size / 1024}KB, Embedding: ${embBytes.size / 1024}KB, Classifier: ${wwBytes.size / 1024}KB)")
    }

    private fun loadAssetWithVerify(path: String): ByteArray {
        return try {
            context.assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            val errorMsg = "Không tìm thấy file model trong assets: $path"
            Log.e(TAG, errorMsg, e)
            notifyStatus("Missing Model Asset", false, errorMsg)
            throw IllegalStateException(errorMsg, e)
        }
    }

    private fun initBuffers() {
        rawBuffer = FloatArray(SAMPLE_RATE * RAW_BUFFER_SECONDS)
        rawWritePos = 0
        rawTotalWritten = 0L

        melBuffer.clear()
        repeat(MEL_WINDOW_FRAMES) { melBuffer.add(FloatArray(MEL_BINS) { 1.0f }) }

        featureBuffer.clear()
        repeat(FEATURE_WINDOW) { featureBuffer.add(FloatArray(EMBEDDING_DIM)) }

        predictionCount = 0
        lastDetectionTime = 0L
    }

    // ------------------------------------------------------------------
    // Thu âm Micro AudioRecord
    // ------------------------------------------------------------------

    private fun initAudioRecord(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Chưa cấp quyền RECORD_AUDIO")
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Lỗi kích thước buffer AudioRecord: $minBuf")
            return false
        }
        val bufferSize = minBuf.coerceAtLeast(FRAME_SAMPLES * 4)

        // Thử VOICE_RECOGNITION trước, nếu không thành công fallback sang MIC (tương thích cao trên OPPO ColorOS)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    val srcName = if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC"
                    Log.i(TAG, "AudioRecord khởi tạo thành công với source=$srcName, buffer=$bufferSize")
                    notifyStatus("AudioRecord Init", true, "Khởi tạo mic thành công (source: $srcName, buffer: $bufferSize)")
                    return true
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không thể khởi tạo AudioRecord với source=$source", e)
            }
        }

        return false
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ------------------------------------------------------------------
    // Vòng lặp xử lý âm thanh chính
    // ------------------------------------------------------------------

    private fun audioLoop() {
        try {
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val msg = "AudioRecord không thể chuyển sang trạng thái RECORDING (mã trạng thái=${audioRecord?.recordingState})"
                Log.e(TAG, msg)
                notifyStatus("AudioRecord Start", false, msg)
                return
            }
            notifyStatus("AudioRecord Start", true, "AudioRecord start thành công, đang thu âm mic 16kHz")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi gọi audioRecord.startRecording", e)
            notifyStatus("AudioRecord Start", false, e.message ?: "Lỗi bắt đầu thu âm")
            return
        }

        val frame = ShortArray(FRAME_SAMPLES)
        var frameCounter = 0

        while (isRunning) {
            val read = audioRecord?.read(frame, 0, FRAME_SAMPLES) ?: break
            if (read < 0) {
                Log.w(TAG, "AudioRecord read error code: $read")
                continue
            }
            if (read != FRAME_SAMPLES) continue

            // Tính năng lượng RMS của frame để theo dõi âm lượng mic
            var sumSquare = 0.0
            for (sample in frame) {
                sumSquare += sample * sample
            }
            val rms = sqrt(sumSquare / FRAME_SAMPLES).toFloat()

            addToRawBuffer(frame)
            if (rawTotalWritten < FRAME_SAMPLES + MEL_CONTEXT_SAMPLES) continue

            val audioSlice = getLastNSamples(FRAME_SAMPLES + MEL_CONTEXT_SAMPLES)
            val newMelFrames = computeMelSpectrogram(audioSlice) ?: continue

            for (melFrame in newMelFrames) melBuffer.add(melFrame)
            while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeAt(0)

            if (melBuffer.size >= MEL_WINDOW_FRAMES) {
                val start = melBuffer.size - MEL_WINDOW_FRAMES
                val melWindow = ArrayList<FloatArray>(MEL_WINDOW_FRAMES)
                for (i in start until melBuffer.size) melWindow.add(melBuffer[i])

                val embedding = computeEmbedding(melWindow) ?: continue
                featureBuffer.add(embedding)
                while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeAt(0)
            }

            if (featureBuffer.size >= FEATURE_WINDOW) {
                predictionCount++
                if (predictionCount <= SKIP_INITIAL_PREDICTIONS) continue

                val fStart = featureBuffer.size - FEATURE_WINDOW
                val features = ArrayList<FloatArray>(FEATURE_WINDOW)
                for (i in fStart until featureBuffer.size) features.add(featureBuffer[i])

                val score = runWakeWordModel(features)
                frameCounter++

                // Gửi callback điểm số realtime (mỗi 160ms hoặc khi có âm thanh)
                val sListener = scoreListener
                if (sListener != null && (frameCounter % 2 == 0 || score > 0.10f || rms > 150f)) {
                    mainHandler.post { sListener.onScore(score, rms) }
                }

                if (score >= threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime > debounceMs) {
                        lastDetectionTime = now
                        Log.i(TAG, "🔥 KÍCH HOẠT WAKE WORD! Score: $score >= Threshold: $threshold (RMS: $rms)")
                        val cb = detectionListener
                        mainHandler.post { cb?.onDetected(score) }
                    }
                }
            }
        }
    }

    private fun addToRawBuffer(frame: ShortArray) {
        for (sample in frame) {
            rawBuffer[rawWritePos] = sample.toFloat()
            rawWritePos = (rawWritePos + 1) % rawBuffer.size
        }
        rawTotalWritten += frame.size
    }

    private fun getLastNSamples(n: Int): FloatArray {
        val result = FloatArray(n)
        val available = minOf(n, rawBuffer.size, rawTotalWritten.toInt())
        var readPos = (rawWritePos - available + rawBuffer.size) % rawBuffer.size
        for (i in 0 until available) {
            result[n - available + i] = rawBuffer[readPos]
            readPos = (readPos + 1) % rawBuffer.size
        }
        return result
    }

    private fun computeMelSpectrogram(audio: FloatArray): List<FloatArray>? {
        val env = ortEnv ?: return null
        val session = melSpecSession ?: return null
        val inName = melSpecInputName ?: session.inputNames.first()
        return try {
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
            ).use { input ->
                session.run(mapOf(inName to input)).use { result ->
                    val output = result[0] as OnnxTensor
                    val shape = output.info.shape // [1, 1, T, 32]
                    val flat = output.floatBuffer
                    val numFrames = shape[2].toInt()
                    val numBins = shape[3].toInt()

                    ArrayList<FloatArray>(numFrames).also { frames ->
                        for (f in 0 until numFrames) {
                            val melFrame = FloatArray(numBins)
                            for (b in 0 until numBins) {
                                melFrame[b] = flat.get(f * numBins + b) / 10.0f + 2.0f
                            }
                            frames.add(melFrame)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tính mel spectrogram", e)
            null
        }
    }

    private fun computeEmbedding(melWindow: List<FloatArray>): FloatArray? {
        val env = ortEnv ?: return null
        val session = embeddingSession ?: return null
        val inName = embeddingInputName ?: session.inputNames.first()
        return try {
            val flatData = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
            for (i in 0 until MEL_WINDOW_FRAMES) {
                System.arraycopy(melWindow[i], 0, flatData, i * MEL_BINS, MEL_BINS)
            }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flatData),
                longArrayOf(1, MEL_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1)
            ).use { input ->
                session.run(mapOf(inName to input)).use { result ->
                    val buf = (result[0] as OnnxTensor).floatBuffer
                    FloatArray(EMBEDDING_DIM) { buf.get(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tính speech embedding", e)
            null
        }
    }

    private fun runWakeWordModel(features: List<FloatArray>): Float {
        val env = ortEnv ?: return 0f
        val session = wakeWordSession ?: return 0f
        val inputName = wakeWordInputName ?: session.inputNames.first()
        return try {
            val flatData = FloatArray(FEATURE_WINDOW * EMBEDDING_DIM)
            for (i in 0 until FEATURE_WINDOW) {
                System.arraycopy(features[i], 0, flatData, i * EMBEDDING_DIM, EMBEDDING_DIM)
            }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flatData),
                longArrayOf(1, FEATURE_WINDOW.toLong(), EMBEDDING_DIM.toLong())
            ).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    var rawScore = (result[0] as OnnxTensor).floatBuffer.get(0)
                    // Nếu model xuất ra logit (ví dụ < 0 hoặc > 1), chuyển đổi sigmoid sang xác suất [0, 1]
                    if (rawScore < 0f || rawScore > 1f) {
                        rawScore = (1.0 / (1.0 + Math.exp(-rawScore.toDouble()))).toFloat()
                    }
                    rawScore
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tính wake word score", e)
            0f
        }
    }
}
