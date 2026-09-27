package com.dinh.javis.vision

import android.graphics.Rect
import android.util.Log
import com.dinh.javis.utils.TextNormalizer

/**
 * Maps OCR blocks and node bounds to verified physical screen tap checkpoints (x, y) (Phase 1).
 */
class ScreenTargetResolver(
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 2340
) {

    private val TAG = "ScreenTargetResolver"

    /**
     * Resolves a tap checkpoint for a given query text using on-device OCR blocks.
     *
     * @param query The text to locate (e.g., "Tìm kiếm", product title, price string).
     * @param ocrBlocks List of recognized OCR blocks from the screen.
     * @param imageWidth Source image width (for coordinate scaling).
     * @param imageHeight Source image height (for coordinate scaling).
     * @return Target Point2D or null if not found.
     */
    fun findTargetFromOcr(
        query: String,
        ocrBlocks: List<OcrBlock>,
        imageWidth: Int = 0,
        imageHeight: Int = 0
    ): Point2D? {
        if (query.isBlank() || ocrBlocks.isEmpty()) return null

        val normQuery = TextNormalizer.removeAccents(query.lowercase()).trim()

        // 1. Try exact containment match
        var matchedBlock = ocrBlocks.firstOrNull { block ->
            val normText = TextNormalizer.removeAccents(block.text.lowercase())
            normText.contains(normQuery)
        }

        // 2. Fallback: try keyword token match (e.g. multi-word product title)
        if (matchedBlock == null && normQuery.length > 8) {
            val queryTokens = normQuery.split("\\s+".toRegex()).filter { it.length >= 3 }
            if (queryTokens.isNotEmpty()) {
                matchedBlock = ocrBlocks.maxByOrNull { block ->
                    val normBlock = TextNormalizer.removeAccents(block.text.lowercase())
                    queryTokens.count { normBlock.contains(it) }
                }?.takeIf { block ->
                    val normBlock = TextNormalizer.removeAccents(block.text.lowercase())
                    val matchCount = queryTokens.count { normBlock.contains(it) }
                    matchCount >= (queryTokens.size / 2).coerceAtLeast(1)
                }
            }
        }

        if (matchedBlock != null) {
            val rawCenter = Point2D(matchedBlock.centerX, matchedBlock.centerY)
            val resolvedPoint = if (imageWidth > 0 && imageHeight > 0 &&
                (imageWidth != screenWidth || imageHeight != screenHeight)
            ) {
                CoordinateUtils.mapImageToScreen(
                    rawCenter.x, rawCenter.y,
                    imageWidth, imageHeight,
                    screenWidth, screenHeight
                )
            } else {
                rawCenter
            }

            if (CoordinateUtils.isWithinScreen(resolvedPoint.x, resolvedPoint.y, screenWidth, screenHeight)) {
                Log.d(TAG, "Resolved target \"$query\" to OCR checkpoint: (${resolvedPoint.x}, ${resolvedPoint.y})")
                return resolvedPoint
            } else {
                Log.w(TAG, "OCR target coordinates (${resolvedPoint.x}, ${resolvedPoint.y}) out of screen bounds.")
            }
        }

        return null
    }

    /**
     * Resolves a tap checkpoint for a product card by matching both title tokens and price tokens.
     */
    fun findProductCardCheckpoint(
        title: String,
        priceText: String?,
        ocrBlocks: List<OcrBlock>,
        imageWidth: Int = 0,
        imageHeight: Int = 0
    ): Point2D? {
        // Try title first
        val titlePoint = findTargetFromOcr(title, ocrBlocks, imageWidth, imageHeight)
        if (titlePoint != null) return titlePoint

        // Fallback to price block ONLY if unique (matches exactly 1 OCR block)
        if (!priceText.isNullOrBlank()) {
            val normPrice = TextNormalizer.removeAccents(priceText.lowercase()).replace(" ", "")
            val matchingBlocksCount = ocrBlocks.count { block ->
                val normBlock = TextNormalizer.removeAccents(block.text.lowercase()).replace(" ", "")
                normBlock.contains(normPrice)
            }
            if (matchingBlocksCount == 1) {
                val pricePoint = findTargetFromOcr(priceText, ocrBlocks, imageWidth, imageHeight)
                if (pricePoint != null) return pricePoint
            } else {
                Log.w(TAG, "Price text \"$priceText\" matched $matchingBlocksCount OCR blocks (non-unique); rejecting price fallback.")
            }
        }

        return null
    }

    /**
     * Resolves tap point from an Android Rect bounds in screen.
     */
    fun resolveNodeBoundsCheckpoint(bounds: Rect): Point2D? {
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0 || height <= 0) return null
        val point = CoordinateUtils.resolveTapPoint(bounds, screenWidth, screenHeight)
        return if (CoordinateUtils.isWithinScreen(point.x, point.y, screenWidth, screenHeight)) {
            point
        } else {
            null
        }
    }
}
