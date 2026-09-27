package com.dinh.javis.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import com.dinh.javis.MainActivity
import com.dinh.javis.R
import com.dinh.javis.agent.ConfirmationDialogActivity

/**
 * Service hiển thị nút Mic nổi (Floating Overlay Bubble) trên màn hình
 * Cho phép người dùng chạm để nói lệnh khi đang lướt TikTok/YouTube/Facebook
 * và hiển thị hộp thoại xác nhận tương tác nổi (BA-01).
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var confirmationView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        instance = this

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        val micButton = floatingView?.findViewById<ImageView>(R.id.floatingMicBtn)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }

                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onBubbleClicked() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TRIGGER_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    /**
     * Shows an interactive overlay confirmation card right over whatever screen is currently active.
     */
    @SuppressLint("InflateParams")
    fun showConfirmationOverlay(question: String, onUserResponse: (Boolean) -> Unit) {
        mainHandler.post {
            dismissConfirmationOverlay()

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val view = LayoutInflater.from(this).inflate(R.layout.layout_confirmation_overlay, null)
            val tvQuestion = view.findViewById<TextView>(R.id.tvConfirmationQuestion)
            val btnApprove = view.findViewById<Button>(R.id.btnApproveConfirmation)
            val btnDeny = view.findViewById<Button>(R.id.btnDenyConfirmation)

            tvQuestion.text = question

            var answered = false
            btnApprove.setOnClickListener {
                if (!answered) {
                    answered = true
                    dismissConfirmationOverlay()
                    onUserResponse(true)
                }
            }

            btnDeny.setOnClickListener {
                if (!answered) {
                    answered = true
                    dismissConfirmationOverlay()
                    onUserResponse(false)
                }
            }

            try {
                windowManager?.addView(view, overlayParams)
                confirmationView = view
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to dialog activity if overlay add fails
                dismissConfirmationOverlay()
                ConfirmationDialogActivity.show(this, question, onUserResponse)
            }
        }
    }

    fun dismissConfirmationOverlay() {
        confirmationView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
            confirmationView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissConfirmationOverlay()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        @Volatile
        var instance: FloatingBubbleService? = null

        /**
         * Requests real user confirmation via overlay or dialog activity (BA-01).
         * Dispatches user action (Đồng ý / Từ chối) to onUserResponse.
         */
        fun showConfirmation(context: Context, question: String, onUserResponse: (Boolean) -> Unit) {
            val activeInstance = instance
            if (activeInstance != null && Settings.canDrawOverlays(context)) {
                activeInstance.showConfirmationOverlay(question, onUserResponse)
            } else {
                ConfirmationDialogActivity.show(context, question, onUserResponse)
            }
        }
    }
}
