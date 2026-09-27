package com.dinh.javis.vision

import android.graphics.Rect

data class Point2D(val x: Float, val y: Float)

/**
 * Utilities for display coordinate transformation and mapping between screenshot and physical screen.
 * Handles status bar offset, insets, and screen boundary checks.
 */
object CoordinateUtils {

    /**
     * Maps coordinates from compressed/downscaled image dimensions (imageWidth x imageHeight)
     * to physical device display coordinates (screenWidth x screenHeight).
     */
    fun mapImageToScreen(
        imageX: Float,
        imageY: Float,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Point2D {
        if (imageWidth <= 0 || imageHeight <= 0) return Point2D(imageX, imageY)
        val scaleX = screenWidth.toFloat() / imageWidth.toFloat()
        val scaleY = screenHeight.toFloat() / imageHeight.toFloat()
        val mappedX = clamp(imageX * scaleX, 0f, screenWidth.toFloat())
        val mappedY = clamp(imageY * scaleY, 0f, screenHeight.toFloat())
        return Point2D(mappedX, mappedY)
    }

    /**
     * Calculates the center tap checkpoint from a bounding rectangle,
     * applying insets and status bar compensation if needed.
     */
    fun resolveTapPoint(
        bounds: Rect,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        statusBarOffset: Int = 0
    ): Point2D {
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = ((bounds.top + bounds.bottom) / 2f) + statusBarOffset

        val clampedX = if (screenWidth > 0) clamp(centerX, 0f, screenWidth.toFloat()) else centerX
        val clampedY = if (screenHeight > 0) clamp(centerY, 0f, screenHeight.toFloat()) else centerY

        return Point2D(clampedX, clampedY)
    }

    /**
     * Checks if coordinates fall within the safe physical screen bounds.
     */
    fun isWithinScreen(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        return x in 0f..screenWidth.toFloat() && y in 0f..screenHeight.toFloat()
    }

    /**
     * Clamps coordinate value to safe range.
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }
}
