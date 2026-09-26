package com.dinh.javis.vision

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateUtilsTest {

    @Test
    fun testMapImageToScreen() {
        val imageX = 540f
        val imageY = 960f
        val imageWidth = 1080
        val imageHeight = 1920

        val screenWidth = 1440
        val screenHeight = 2560

        val mapped = CoordinateUtils.mapImageToScreen(
            imageX, imageY,
            imageWidth, imageHeight,
            screenWidth, screenHeight
        )

        assertEquals(720f, mapped.x, 0.01f)
        assertEquals(1280f, mapped.y, 0.01f)
    }

    @Test
    fun testClamp() {
        assertEquals(0f, CoordinateUtils.clamp(-10f, 0f, 100f), 0.001f)
        assertEquals(50f, CoordinateUtils.clamp(50f, 0f, 100f), 0.001f)
        assertEquals(100f, CoordinateUtils.clamp(150f, 0f, 100f), 0.001f)
    }
}
