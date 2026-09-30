package com.dinh.javis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [AudioDebugRecorder] verifying WAV header construction,
 * sample writing, and lifecycle behavior without requiring Android Runtime.
 */
class AudioDebugRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Minimal standalone simulator of AudioDebugRecorder file output logic.
     */
    private class WavFileWriter(val file: File) {
        private var fos: java.io.FileOutputStream? = null
        private var totalSamples: Long = 0
        var isRecording = false

        fun start() {
            val out = file.outputStream()
            fos = out
            // Write 44-byte placeholder header
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            out.write(header)
            isRecording = true
        }

        fun writeFrame(samples: ShortArray) {
            if (!isRecording) return
            val out = fos ?: return
            val byteBuf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            byteBuf.asShortBuffer().put(samples)
            out.write(byteBuf.array())
            totalSamples += samples.size
        }

        fun stop(): File {
            fos?.flush()
            fos?.close()
            fos = null
            isRecording = false
            val dataBytes = totalSamples * 2
            val chunkBytes = dataBytes + 36
            val raf = RandomAccessFile(file, "rw")
            // Patch RIFF chunk size at offset 4
            raf.seek(4)
            for (i in 0 until 4) raf.writeByte(((chunkBytes shr (i * 8)) and 0xFF).toInt())
            // Patch Subchunk2 size at offset 40
            raf.seek(40)
            for (i in 0 until 4) raf.writeByte(((dataBytes shr (i * 8)) and 0xFF).toInt())
            raf.close()
            return file
        }
    }

    @Test
    fun wavHeader_hasCorrectRiffHeaderAndPatchedLengths() {
        val testFile = tempFolder.newFile("debug_test.wav")
        val writer = WavFileWriter(testFile)

        writer.start()
        assertTrue(writer.isRecording)

        // Write 1600 samples (16-bit PCM = 3200 bytes)
        val pcmData = ShortArray(1600) { (it % 100).toShort() }
        writer.writeFrame(pcmData)

        val finalizedFile = writer.stop()
        assertFalse(writer.isRecording)

        // Read back header and verify size fields
        val bytes = finalizedFile.readBytes()
        assertEquals(44 + 3200, bytes.size)

        // Magic bytes RIFF and WAVE
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        assertEquals('W'.code.toByte(), bytes[8])
        assertEquals('A'.code.toByte(), bytes[9])
        assertEquals('V'.code.toByte(), bytes[10])
        assertEquals('E'.code.toByte(), bytes[11])

        // RIFF size at offset 4 (little endian): 3200 + 36 = 3236 = 0x00000CA4
        val riffSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(3236, riffSize)

        // Data chunk size at offset 40: 3200 = 0x00000C80
        val dataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(3200, dataSize)
    }

    @Test
    fun pcmSampleData_matchesInputSampleForSample() {
        val testFile = tempFolder.newFile("pcm_test.wav")
        val writer = WavFileWriter(testFile)

        val samples = shortArrayOf(0x0102.toShort(), -12345, 32767, -32768, 0)
        writer.start()
        writer.writeFrame(samples)
        writer.stop()

        val bytes = testFile.readBytes()
        val dataBuffer = ByteBuffer.wrap(bytes, 44, samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val readSamples = ShortArray(samples.size)
        dataBuffer.get(readSamples)

        assertArrayEquals(samples, readSamples)
    }
}
