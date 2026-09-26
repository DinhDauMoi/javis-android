package com.dinh.javis.vision

data class Point2D(val x: Float, val y: Float)

/**
 * Tiện ích chuyển đổi tọa độ hiển thị và ánh xạ giữa ảnh chụp màn hình và tọa độ vật lý
 */
object CoordinateUtils {

    /**
     * Ánh xạ tọa độ từ tỷ lệ ảnh nén/thu nhỏ (imageWidth x imageHeight)
     * sang tọa độ thực trên màn hình vật lý (screenWidth x screenHeight)
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
        return Point2D(imageX * scaleX, imageY * scaleY)
    }

    /**
     * Giới hạn tọa độ nằm trong biên an toàn của màn hình
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }
}
