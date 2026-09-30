package com.dinh.javis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying audio frame accumulation and carry-over buffer logic
 * used in OpenWakeWord to prevent frame dropping on OEM devices with partial reads.
 */
class AudioFrameBufferTest {

    companion object {
        private const val FRAME_SAMPLES = 1280
    }

    /**
     * Simulates the exact buffer accumulation algorithm implemented in OpenWakeWord.audioLoop.
     */
    private class FrameAccumulator {
        private val carryBuffer = ShortArray(FRAME_SAMPLES)
        private var carryCount = 0
        val outputFrames = mutableListOf<ShortArray>()

        fun processRead(frame: ShortArray, read: Int) {
            if (read < 0) {
                // Error code from AudioRecord; drop and continue
                return
            }

            if (carryCount > 0) {
                val needed = FRAME_SAMPLES - carryCount
                if (read < needed) {
                    System.arraycopy(frame, 0, carryBuffer, carryCount, read)
                    carryCount += read
                    return
                }
                val leftover = read - needed
                val leftoverTemp = if (leftover > 0) ShortArray(leftover) else null
                if (leftover > 0) {
                    System.arraycopy(frame, needed, leftoverTemp!!, 0, leftover)
                }
                System.arraycopy(frame, 0, carryBuffer, carryCount, needed)
                val fullFrame = ShortArray(FRAME_SAMPLES)
                System.arraycopy(carryBuffer, 0, fullFrame, 0, FRAME_SAMPLES)
                outputFrames.add(fullFrame)
                carryCount = leftover
                if (leftover > 0 && leftoverTemp != null) {
                    System.arraycopy(leftoverTemp, 0, carryBuffer, 0, leftover)
                }
            } else {
                if (read < FRAME_SAMPLES) {
                    System.arraycopy(frame, 0, carryBuffer, 0, read)
                    carryCount = read
                    return
                }
                val fullFrame = ShortArray(FRAME_SAMPLES)
                System.arraycopy(frame, 0, fullFrame, 0, FRAME_SAMPLES)
                outputFrames.add(fullFrame)
            }
        }
    }

    @Test
    fun exactFrames_passThroughUnmodified() {
        val accumulator = FrameAccumulator()
        val input1 = ShortArray(FRAME_SAMPLES) { (it + 1).toShort() }
        val input2 = ShortArray(FRAME_SAMPLES) { (it + 2000).toShort() }

        accumulator.processRead(input1, FRAME_SAMPLES)
        accumulator.processRead(input2, FRAME_SAMPLES)

        assertEquals(2, accumulator.outputFrames.size)
        assertArrayEquals(input1, accumulator.outputFrames[0])
        assertArrayEquals(input2, accumulator.outputFrames[1])
    }

    @Test
    fun twoHalfFrames_assembleIntoSingleFullFrame() {
        val accumulator = FrameAccumulator()
        val half1 = ShortArray(FRAME_SAMPLES) { it.toShort() } // First 640 used
        val half2 = ShortArray(FRAME_SAMPLES) { (it + 640).toShort() } // Next 640 used

        accumulator.processRead(half1, 640)
        assertEquals(0, accumulator.outputFrames.size) // Not ready yet

        accumulator.processRead(half2, 640)
        assertEquals(1, accumulator.outputFrames.size)

        val assembled = accumulator.outputFrames[0]
        val expected = ShortArray(FRAME_SAMPLES) { it.toShort() }
        assertArrayEquals(expected, assembled)
    }

    @Test
    fun arbitraryPartialReads_preserveContinuousAudioStreamWithoutLoss() {
        val accumulator = FrameAccumulator()
        val totalSamples = 1280 * 3 // 3 full frames = 3840 samples
        val sourceAudio = ShortArray(totalSamples) { (it and 0x7FFF).toShort() }

        // Feed with arbitrary uneven chunk sizes: 500, 300, 800, 400, 700, 1140
        val chunkSizes = intArrayOf(500, 300, 800, 400, 700, 1140)
        assertEquals(totalSamples, chunkSizes.sum())

        var offset = 0
        for (chunk in chunkSizes) {
            val buf = ShortArray(FRAME_SAMPLES)
            System.arraycopy(sourceAudio, offset, buf, 0, chunk)
            accumulator.processRead(buf, chunk)
            offset += chunk
        }

        assertEquals(3, accumulator.outputFrames.size)

        // Verify assembled stream matches source stream sample-for-sample
        val reconstructed = ShortArray(totalSamples)
        System.arraycopy(accumulator.outputFrames[0], 0, reconstructed, 0, 1280)
        System.arraycopy(accumulator.outputFrames[1], 0, reconstructed, 1280, 1280)
        System.arraycopy(accumulator.outputFrames[2], 0, reconstructed, 2560, 1280)

        assertArrayEquals(sourceAudio, reconstructed)
    }

    @Test
    fun errorCodes_doNotCorruptCarriedBuffer() {
        val accumulator = FrameAccumulator()
        val part1 = ShortArray(FRAME_SAMPLES) { it.toShort() }
        val part2 = ShortArray(FRAME_SAMPLES) { (it + 640).toShort() }

        accumulator.processRead(part1, 640)
        // Simulate AudioRecord error (-1 ERROR, -2 ERROR_BAD_VALUE, -3 ERROR_INVALID_OPERATION)
        accumulator.processRead(ShortArray(FRAME_SAMPLES), -1)
        accumulator.processRead(ShortArray(FRAME_SAMPLES), -2)
        accumulator.processRead(part2, 640)

        assertEquals(1, accumulator.outputFrames.size)
        val expected = ShortArray(FRAME_SAMPLES) { it.toShort() }
        assertArrayEquals(expected, accumulator.outputFrames[0])
    }
}
