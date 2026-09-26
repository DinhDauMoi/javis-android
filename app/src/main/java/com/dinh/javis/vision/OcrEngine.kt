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
    val boundingBox: Rect,
    val centerX: Float = boundingBox.centerX().toFloat(),
    val centerY: Float = boundingBox.centerY().toFloat()
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>
)

/**
 * Bộ nhận diện chữ trên màn hình On-Device bằng Google ML Kit Text Recognition
 * Chạy 100% cục bộ, tốc độ ~40-90ms, độ trễ thấp và không tốn phí token AI.
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
                        blocks.add(OcrBlock(text = block.text, boundingBox = rect))
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
