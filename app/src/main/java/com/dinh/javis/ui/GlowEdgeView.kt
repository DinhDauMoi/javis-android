package com.dinh.javis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom View vẽ viền phát sáng đa sắc (gradient nhiều màu phong cách Siri / Google Assistant / Gemini)
 * chạy dọc 4 cạnh màn hình với góc bo tròn mượt mà.
 */
class GlowEdgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Bảng màu gradient rực rỡ phong cách AI Assistant
    private val gradientColors = intArrayOf(
        Color.parseColor("#00E5FF"), // Cyan
        Color.parseColor("#2979FF"), // Blue
        Color.parseColor("#7C4DFF"), // Purple
        Color.parseColor("#FF1744"), // Red-Pink
        Color.parseColor("#FF9100"), // Orange
        Color.parseColor("#00E5FF")  // Quay về Cyan khép vòng
    )

    private val colorPositions = floatArrayOf(0.0f, 0.2f, 0.4f, 0.65f, 0.85f, 1.0f)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val boundsRect = RectF()
    private val shaderMatrix = Matrix()

    private var currentAngle = 0f
    private var currentAlphaFactor = 1.0f

    private var rotateAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private val cornerRadius: Float
        get() = 36f * resources.displayMetrics.density

    private val coreStrokeWidth: Float
        get() = 5f * resources.displayMetrics.density

    private val glowStrokeWidth: Float
        get() = 14f * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfGlow = glowStrokeWidth / 2f
        boundsRect.set(halfGlow, halfGlow, w - halfGlow, h - halfGlow)

        // Tạo SweepGradient quanh tâm màn hình
        val sweepGradient = SweepGradient(
            w / 2f,
            h / 2f,
            gradientColors,
            colorPositions
        )
        glowPaint.shader = sweepGradient
        corePaint.shader = sweepGradient

        glowPaint.strokeWidth = glowStrokeWidth
        corePaint.strokeWidth = coreStrokeWidth
    }

    fun startAnimation() {
        stopAnimation()

        // 1. Animation xoay màu vòng quanh 360 độ liên tục (2 giây / vòng)
        rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentAngle = animation.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 2. Animation nhịp thở pulse nhẹ tăng giảm độ sáng (800ms)
        pulseAnimator = ValueAnimator.ofFloat(0.70f, 1.0f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                currentAlphaFactor = animation.animatedValue as Float
            }
            start()
        }
    }

    fun stopAnimation() {
        rotateAnimator?.cancel()
        rotateAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Xoay gradient theo góc hiện tại
        shaderMatrix.setRotate(currentAngle, width / 2f, height / 2f)
        glowPaint.shader?.setLocalMatrix(shaderMatrix)
        corePaint.shader?.setLocalMatrix(shaderMatrix)

        // Lớp 1: Viền tỏa sáng mờ ảo (Aura Glow)
        glowPaint.alpha = (140 * currentAlphaFactor).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, glowPaint)

        // Lớp 2: Lõi sáng rực rỡ sắc nét
        corePaint.alpha = (255 * currentAlphaFactor).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, corePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
