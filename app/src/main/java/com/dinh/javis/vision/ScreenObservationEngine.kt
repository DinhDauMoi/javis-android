package com.dinh.javis.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.dinh.javis.ai.capabilities.ScreenObservation
import com.dinh.javis.service.JavisAccessibilityService

data class ObservationResult(
    val observation: ScreenObservation,
    /** Bitmap owned by caller; caller MUST call recycle() after use. Null if not captured. */
    val bitmap: Bitmap? = null,
    val ocrBlocks: List<OcrBlock> = emptyList(),
    /** Monotonic timestamp of when this observation was captured (for staleness checks). */
    val capturedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    /** What evidence sources contributed to this observation. */
    val evidenceSources: Set<EvidenceSource> = emptySet()
) {
    /** Returns true if this observation is older than [maxAgeMs] milliseconds. */
    fun isStale(maxAgeMs: Long = 3000L): Boolean =
        (SystemClock.elapsedRealtime() - capturedAtElapsedMs) > maxAgeMs
}

enum class EvidenceSource {
    ACCESSIBILITY_TREE,
    LOCAL_OCR,
    SCREENSHOT_CAPTURED
}

/**
 * Multi-layer screen observation engine (BA-06).
 *
 * Evidence sufficiency strategy:
 * - Tier 1 (Accessibility node tree): Always attempted first. Considered sufficient if
 *   the hierarchy text is substantial (>= MIN_NODE_TEXT_LENGTH characters).
 * - Tier 2 (Local OCR): Applied when the node hierarchy is short (indicating a web/canvas view).
 *   OCR is run on the captured bitmap in RAM; never written to disk.
 * - Tier 3 (Screenshot for VLM): Captured only when visual analysis is requested AND
 *   ScreenCaptureService is active. The bitmap is returned as an owned reference that
 *   the caller MUST recycle() after use.
 *
 * Privacy:
 * - Screenshots stay in RAM only (JPEG compression happens in OpenAiCompatibleClient).
 * - ScreenCaptureService per-session consent is checked via isCapturing().
 * - Bitmaps are never written to flash storage.
 *
 * Freshness:
 * - Each ObservationResult includes a capturedAtElapsedMs timestamp.
 * - Callers should check isStale() before re-using cached results.
 */
class ScreenObservationEngine(private val context: Context) {

    private val ocrEngine = OcrEngine()

    /** Minimum node hierarchy text length to consider Tier 1 sufficient. */
    private val MIN_NODE_TEXT_LENGTH = 100

    suspend fun observeScreen(captureVisual: Boolean = true): ObservationResult {
        val capturedAt = SystemClock.elapsedRealtime()
        val evidenceSources = mutableSetOf<EvidenceSource>()

        val a11yService = JavisAccessibilityService.instance
        val activePackage = a11yService?.getActivePackageName()
        val nodeHierarchy = a11yService?.dumpNodeHierarchy() ?: ""

        if (nodeHierarchy.isNotBlank()) {
            evidenceSources.add(EvidenceSource.ACCESSIBILITY_TREE)
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        var bitmap: Bitmap? = null
        var ocrText = ""
        val ocrBlocks = mutableListOf<OcrBlock>()

        // Tier 3: Capture screenshot only when visual mode is enabled and session is active
        if (captureVisual && ScreenCaptureService.isCapturing()) {
            bitmap = ScreenCaptureService.instance?.captureBitmap()
            if (bitmap != null) {
                evidenceSources.add(EvidenceSource.SCREENSHOT_CAPTURED)

                // Tier 2: Run OCR when node hierarchy has insufficient evidence
                // (e.g., WebView, Canvas-based apps, games)
                val nodeTextSufficient = nodeHierarchy.length >= MIN_NODE_TEXT_LENGTH
                if (!nodeTextSufficient) {
                    val ocrResult = ocrEngine.recognizeText(bitmap)
                    ocrText = ocrResult.fullText
                    ocrBlocks.addAll(ocrResult.blocks)
                    if (ocrText.isNotBlank()) {
                        evidenceSources.add(EvidenceSource.LOCAL_OCR)
                    }
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
            bitmap = bitmap,        // Caller owns this bitmap and MUST recycle() it
            ocrBlocks = ocrBlocks,
            capturedAtElapsedMs = capturedAt,
            evidenceSources = evidenceSources
        )
    }
}
