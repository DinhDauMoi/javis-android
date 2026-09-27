package com.dinh.javis.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrBlock(
    val text: String,
    val boundingBox: Rect = Rect(),
    val centerX: Float = (boundingBox.left + boundingBox.right) / 2f,
    val centerY: Float = (boundingBox.top + boundingBox.bottom) / 2f,
    val top: Float = boundingBox.top.toFloat(),
    val bottom: Float = boundingBox.bottom.toFloat(),
    val left: Float = boundingBox.left.toFloat(),
    val right: Float = boundingBox.right.toFloat()
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>
)

/**
 * On-device screen text recognition engine powered by Google ML Kit Text Recognition.
 * Runs 100% locally with ~40-90ms latency, zero network cost, and zero AI token overhead.
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = mutableListOf<OcrBlock>()
                    for (block in visionText.textBlocks) {
                        val rect = block.boundingBox ?: Rect()
                        val bTop = rect.top.toFloat()
                        val bBottom = rect.bottom.toFloat()
                        val bLeft = rect.left.toFloat()
                        val bRight = rect.right.toFloat()
                        blocks.add(
                            OcrBlock(
                                text = block.text,
                                boundingBox = rect,
                                centerX = (bLeft + bRight) / 2f,
                                centerY = (bTop + bBottom) / 2f,
                                top = bTop,
                                bottom = bBottom,
                                left = bLeft,
                                right = bRight
                            )
                        )
                    }
                    continuation.resume(OcrResult(fullText = visionText.text, blocks = blocks))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Lỗi nhận dạng OCR trên màn hình", e)
                    continuation.resume(OcrResult(fullText = "", blocks = emptyList()))
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ngoại lệ khi gọi ML Kit OCR", e)
            continuation.resume(OcrResult(fullText = "", blocks = emptyList()))
        }
    }

    companion object {
        private const val TAG = "OcrEngine"
    }
}
