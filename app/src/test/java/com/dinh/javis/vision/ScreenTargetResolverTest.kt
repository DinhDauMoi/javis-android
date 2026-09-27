package com.dinh.javis.vision

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenTargetResolverTest {

    private lateinit var resolver: ScreenTargetResolver

    @Before
    fun setUp() {
        resolver = ScreenTargetResolver(screenWidth = 1080, screenHeight = 2400)
    }

    @Test
    fun findTargetFromOcr_exactMatch_resolvesCoordinates() {
        val blocks = listOf(
            OcrBlock(
                text = "Tìm kiếm",
                boundingBox = Rect(100, 150, 400, 250),
                centerX = 250f,
                centerY = 200f
            ),
            OcrBlock(
                text = "Thông báo",
                boundingBox = Rect(500, 150, 800, 250),
                centerX = 650f,
                centerY = 200f
            )
        )

        val target = resolver.findTargetFromOcr("Tìm kiếm", blocks)
        assertNotNull(target)
        assertEquals(250f, target!!.x, 0.01f)
        assertEquals(200f, target.y, 0.01f)
    }

    @Test
    fun findTargetFromOcr_tokenFuzzyMatch_resolvesCoordinates() {
        val blocks = listOf(
            OcrBlock(
                text = "Tai nghe Bluetooth True Wireless F9",
                boundingBox = Rect(50, 600, 500, 800),
                centerX = 275f,
                centerY = 700f
            )
        )

        // Query with missing accents and slight reordering
        val target = resolver.findTargetFromOcr("tai nghe bluetooth f9", blocks)
        assertNotNull(target)
        assertEquals(275f, target!!.x, 0.01f)
        assertEquals(700f, target.y, 0.01f)
    }

    @Test
    fun findTargetFromOcr_withImageScaling_scalesCoordinatesCorrectly() {
        // Image resolution is half of screen resolution (540x1200 vs 1080x2400)
        val blocks = listOf(
            OcrBlock(
                text = "Mua ngay",
                boundingBox = Rect(100, 500, 200, 600),
                centerX = 150f,
                centerY = 550f
            )
        )

        val target = resolver.findTargetFromOcr(
            query = "Mua ngay",
            ocrBlocks = blocks,
            imageWidth = 540,
            imageHeight = 1200
        )

        assertNotNull(target)
        assertEquals(300f, target!!.x, 0.01f)
        assertEquals(1100f, target.y, 0.01f)
    }

    @Test
    fun findProductCardCheckpoint_fallbackToPrice_whenTitleMissing() {
        val blocks = listOf(
            OcrBlock(
                text = "150.000 đ",
                boundingBox = Rect(50, 800, 250, 880),
                centerX = 150f,
                centerY = 840f
            )
        )

        val target = resolver.findProductCardCheckpoint(
            title = "San pham khong co trong OCR",
            priceText = "150.000 đ",
            ocrBlocks = blocks
        )

        assertNotNull(target)
        assertEquals(150f, target!!.x, 0.01f)
        assertEquals(840f, target.y, 0.01f)
    }

    @Test
    fun resolveNodeBoundsCheckpoint_emptyRect_returnsNull() {
        val emptyRect = Rect().apply { left = 0; top = 0; right = 0; bottom = 0 }
        val target = resolver.resolveNodeBoundsCheckpoint(emptyRect)
        assertNull(target)
    }

    @Test
    fun resolveNodeBoundsCheckpoint_validRect_resolvesCenter() {
        val rect = Rect().apply { left = 100; top = 200; right = 500; bottom = 400 }
        val target = resolver.resolveNodeBoundsCheckpoint(rect)
        assertNotNull(target)
        assertEquals(300f, target!!.x, 0.01f)
        assertEquals(300f, target.y, 0.01f)
    }
}
