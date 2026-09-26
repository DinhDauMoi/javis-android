package com.dinh.javis.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.dinh.javis.data.PreferenceManager

/**
 * Trình quản lý lớp phủ viền sáng màn hình toàn diện (Full-Screen Glowing Edge Overlay).
 *
 * Sử dụng WindowManager + TYPE_APPLICATION_OVERLAY để vẽ viền đa sắc phong cách Siri / Google Assistant
 * khi AI thức dậy và đang lắng nghe lệnh.
 *
 * Đảm bảo:
 * - KHÔNG chặn bất kỳ thao tác chạm nào của người dùng (FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE).
 * - Tự động tắt ngay khi lệnh thực thi xong hoặc hết thời gian chờ.
 * - Kiểm tra quyền SYSTEM_ALERT_WINDOW và cấu hình bật/tắt trong Cài đặt.
 */
class GlowOverlayManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var glowEdgeView: GlowEdgeView? = null
    @Volatile private var isShown = false

    /**
     * Hiển thị viền sáng màn hình
     */
    fun show() {
        mainHandler.post {
            if (isShown) return@post

            // Kiểm tra cài đặt của người dùng
            if (!preferenceManager.isGlowOverlayEnabled) {
                return@post
            }

            // Kiểm tra quyền vẽ đè
            if (!Settings.canDrawOverlays(context)) {
                Log.d(TAG, "Chưa được cấp quyền SYSTEM_ALERT_WINDOW — không hiển thị viền sáng")
                return@post
            }

            try {
                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val view = GlowEdgeView(context)
                windowManager.addView(view, params)
                view.startAnimation()

                glowEdgeView = view
                isShown = true
                Log.i(TAG, "Đã bật hiệu ứng viền màn hình phát sáng khi nghe lệnh")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi hiển thị viền sáng màn hình", e)
            }
        }
    }

    /**
     * Tắt viền sáng màn hình ngay lập tức
     */
    fun hide() {
        mainHandler.post {
            if (!isShown && glowEdgeView == null) return@post

            try {
                glowEdgeView?.stopAnimation()
                glowEdgeView?.let { windowManager.removeView(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi gỡ viền sáng màn hình", e)
            } finally {
                glowEdgeView = null
                isShown = false
                Log.i(TAG, "Đã tắt hiệu ứng viền sáng màn hình")
            }
        }
    }

    companion object {
        private const val TAG = "GlowOverlayManager"
    }
}
