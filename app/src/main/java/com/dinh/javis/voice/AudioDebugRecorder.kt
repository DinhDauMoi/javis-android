package com.dinh.javis.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Developer-only recorder that persists raw wake-word mic frames to a WAV file.
 *
 * Intended for diagnosing flaky wake-word detection: captures the exact PCM
 * stream fed into the ONNX pipeline so it can be replayed in the Python
 * openwakeword reference for comparison.
 *
 * Recording is gated by [com.dinh.javis.data.PreferenceManager.isDebugAudioCaptureEnabled]
 * and must never activate in production builds. Files are written to the app's
 * cache directory and are not persisted to external storage.
 */
class AudioDebugRecorder(private val context: Context) {

    private val outputDir: File = File(context.cacheDir, "debug_audio")
    private var fileOutput: FileOutputStream? = null
    private var dataStartPos: Long = 0
    private var totalSamples: Long = 0
    private var currentFile: File? = null
    @Volatile
    private var isRecording = false

    /**
     * Start a new recording session.
     * @return File handle for the WAV file, or null if recording failed.
     */
    fun start(): File? {
        if (isRecording) {
            Log.w(TAG, "start() called while already recording")
            return null
        }
        try {
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val file = File(outputDir, "wake_debug_${System.currentTimeMillis()}.wav")
            val fos = FileOutputStream(file)
            fileOutput = fos
            currentFile = file

            // Write WAV header (44-byte placeholder; sizes patched on stop).
            val header = ByteArray(WAV_HEADER_SIZE)
            fos.write(PCM_FORMAT_WAV_HEADER)
            dataStartPos = fos.channel.position()
            totalSamples = 0
            isRecording = true
            Log.i(TAG, "Started debug WAV recording to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start debug recording", e)
            fileOutput?.close()
            fileOutput = null
            currentFile = null
            return null
        }
    }

    /**
     * Write a frame of 16-bit PCM samples to the WAV file.
     * @param samples Raw 16-bit PCM samples (little-endian).
     */
    fun writeFrame(samples: ShortArray) {
        if (!isRecording) return
        try {
            val byteBuf = ByteBuffer.allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            byteBuf.asShortBuffer().put(samples)
            fileOutput?.write(byteBuf.array())
            totalSamples += samples.size
        } catch (e: Exception) {
            Log.w(TAG, "Error writing debug PCM frame", e)
        }
    }

    /**
     * Stop recording and finalize the WAV header.
     * @return File that was recorded, or null if nothing was recorded.
     */
    fun stop(): File? {
        val fos = fileOutput ?: return null
        val result = currentFile
        try {
            fos.flush()
            fos.close()
            val dataBytes = totalSamples * 2
            val raf = RandomAccessFile(result!!, "rw")
            writeWavSizes(raf, dataBytes, dataBytes + 36)
            raf.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing WAV header", e)
        } finally {
            fileOutput = null
            currentFile = null
            isRecording = false
            totalSamples = 0
            dataStartPos = 0
        }
        return result
    }

    /** Returns true if a recording session is currently active. */
    fun isRecording(): Boolean = isRecording

    /** Returns the directory where debug WAV files are written. */
    fun getOutputDir(): File = outputDir

    companion object {
        private const val TAG = "AudioDebugRecorder"
        private const val WAV_HEADER_SIZE = 44
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1

        private val PCM_FORMAT_WAV_HEADER = run {
            val header = ByteArray(WAV_HEADER_SIZE)
            // "RIFF" chunk id
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            // "WAVE" format
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // "fmt " subchunk
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            // Subchunk1 Size (16 for PCM)
            val sc1Size = 16
            header[16] = (sc1Size and 0xFF).toByte()
            header[17] = ((sc1Size shr 8) and 0xFF).toByte()
            header[18] = ((sc1Size shr 16) and 0xFF).toByte()
            header[19] = ((sc1Size shr 24) and 0xFF).toByte()
            // AudioFormat (1 = PCM)
            header[20] = 1.toByte()
            header[21] = 0.toByte()
            // NumChannels
            header[22] = CHANNELS.toByte()
            header[23] = 0.toByte()
            // SampleRate
            header[24] = (SAMPLE_RATE and 0xFF).toByte()
            header[25] = ((SAMPLE_RATE shr 8) and 0xFF).toByte()
            header[26] = ((SAMPLE_RATE shr 16) and 0xFF).toByte()
            header[27] = ((SAMPLE_RATE shr 24) and 0xFF).toByte()
            // ByteRate = SampleRate * NumChannels * BitsPerSample/8
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
            header[28] = (byteRate and 0xFF).toByte()
            header[29] = ((byteRate shr 8) and 0xFF).toByte()
            header[30] = ((byteRate shr 16) and 0xFF).toByte()
            header[31] = ((byteRate shr 24) and 0xFF).toByte()
            // BlockAlign = NumChannels * BitsPerSample/8
            val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
            header[32] = blockAlign.toByte()
            header[33] = 0.toByte()
            // BitsPerSample
            header[34] = BITS_PER_SAMPLE.toByte()
            header[35] = 0.toByte()
            // "data" subchunk
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header
        }

        private fun writeWavSizes(
            raf: RandomAccessFile,
            dataBytes: Long,
            chunkBytes: Long,
        ) {
            // RIFF chunk size at offset 4
            writeLittleEndian(raf, 4, chunkBytes)
            // Subchunk2 size (data length) at offset 40
            writeLittleEndian(raf, 40, dataBytes)
        }

        private fun writeLittleEndian(
            raf: RandomAccessFile,
            offset: Long,
            value: Long,
        ) {
            raf.seek(offset)
            for (i in 0 until 4) {
                raf.writeByte(((value shr (i * 8)) and 0xFF).toInt())
            }
        }
    }
}
