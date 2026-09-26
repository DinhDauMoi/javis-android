package com.dinh.javis.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.view.WindowManager
import com.dinh.javis.ai.capabilities.ScreenObservation
import com.dinh.javis.service.JavisAccessibilityService

data class ObservationResult(
    val observation: ScreenObservation,
    val bitmap: Bitmap? = null,
    val ocrBlocks: List<OcrBlock> = emptyList()
)

/**
 * Động cơ quan sát màn hình đa tầng (Multi-Layer Perception Engine):
 * - Tầng 1: Cây giao diện Accessibility (nhanh ~5-15ms, 100% cục bộ, $0).
 * - Tầng 2: ML Kit Text Recognition on-device OCR (nhanh ~40-90ms, 100% cục bộ, $0).
 * - Tầng 3: Ảnh chụp màn hình chuẩn bị sẵn sàng gửi VLM nếu tầng 1 và 2 chưa đủ thông tin.
 */
class ScreenObservationEngine(private val context: Context) {

    private val ocrEngine = OcrEngine()

    suspend fun observeScreen(captureVisual: Boolean = true): ObservationResult {
        val a11yService = JavisAccessibilityService.instance
        val activePackage = a11yService?.getActivePackageName()
        val nodeHierarchy = a11yService?.dumpNodeHierarchy() ?: ""

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        var bitmap: Bitmap? = null
        var ocrText = ""
        val ocrBlocks = mutableListOf<OcrBlock>()

        if (captureVisual && ScreenCaptureService.isCapturing()) {
            bitmap = ScreenCaptureService.instance?.captureBitmap()
            if (bitmap != null) {
                // Chạy OCR On-Device nếu cây giao diện quá ít nút hoặc là web/canvas
                if (nodeHierarchy.length < 150) {
                    val ocrResult = ocrEngine.recognizeText(bitmap)
                    ocrText = ocrResult.fullText
                    ocrBlocks.addAll(ocrResult.blocks)
                }
            }
        }

        val observation = ScreenObservation(
            nodeHierarchyText = nodeHierarchy.ifBlank { null },
            ocrText = ocrText.ifBlank { null },
            screenshotWidth = screenWidth,
            screenshotHeight = screenHeight,
            currentPackage = activePackage
        )

        return ObservationResult(
            observation = observation,
            bitmap = bitmap,
            ocrBlocks = ocrBlocks
        )
    }
}
