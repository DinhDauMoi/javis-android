package com.dinh.javis.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dinh.javis.utils.TextNormalizer

/**
 * Dịch vụ Trợ năng trung tâm của JAVIS
 * Cho phép tự động điều khiển các ứng dụng khác (TikTok, YouTube, Facebook, v.v.):
 * - Cuộn trang lên/xuống (Scroll / Swipe)
 * - Chạm / Click vào nút bấm theo nội dung text hiển thị
 * - Nhấn nút Quay lại (Back), Màn hình chính (Home), Khóa màn hình (Lock), Chụp ảnh màn hình
 */
class JavisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JavisAccessibilityService đã kết nối thành công!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lắng nghe sự kiện nếu cần theo dõi thay đổi cửa sổ
    }

    override fun onInterrupt() {
        Log.w(TAG, "JavisAccessibilityService bị gián đoạn")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "JavisAccessibilityService đã bị hủy")
    }

    // =========================================================================
    // CÁC HÀM ĐIỀU KHIỂN CỬ CHỈ VÀ CUỘN MÀN HÌNH
    // =========================================================================

    /**
     * Lướt lên: chuyển sang nội dung tiếp theo (ví dụ: video tiếp theo trên TikTok)
     * Thử tìm node cuộn để ACTION_SCROLL_FORWARD, nếu không được thì fallback vuốt màn hình
     */
    fun scrollForward(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null) {
                val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrollableNode.recycle()
                if (success) {
                    Log.d(TAG, "scrollForward: ACTION_SCROLL_FORWARD accepted via node.")
                    return true
                }
                Log.d(TAG, "scrollForward: ACTION_SCROLL_FORWARD rejected by node; falling back to gesture.")
            } else {
                Log.d(TAG, "scrollForward: no scrollable node found; falling back to gesture.")
            }
        } else {
            Log.w(TAG, "scrollForward: rootInActiveWindow is null; falling back to gesture.")
        }

        // Fallback: swipe finger from bottom to top
        val gestureAccepted = swipeUp()
        Log.d(TAG, "scrollForward: swipeUp gesture dispatch accepted=$gestureAccepted")
        return gestureAccepted
    }

    /**
     * Lướt xuống: xem lại nội dung trước đó
     */
    fun scrollBackward(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null) {
                val success = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                scrollableNode.recycle()
                if (success) {
                    Log.d(TAG, "scrollBackward: ACTION_SCROLL_BACKWARD accepted via node.")
                    return true
                }
                Log.d(TAG, "scrollBackward: ACTION_SCROLL_BACKWARD rejected by node; falling back to gesture.")
            } else {
                Log.d(TAG, "scrollBackward: no scrollable node found; falling back to gesture.")
            }
        } else {
            Log.w(TAG, "scrollBackward: rootInActiveWindow is null; falling back to gesture.")
        }

        // Fallback: swipe finger from top to bottom
        val gestureAccepted = swipeDown()
        Log.d(TAG, "scrollBackward: swipeDown gesture dispatch accepted=$gestureAccepted")
        return gestureAccepted
    }

    /**
     * Vuốt từ dưới lên (nội dung tiếp)
     */
    fun swipeUp(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.8f
        val endX = width / 2f
        val endY = height * 0.2f

        return swipe(startX, startY, endX, endY, 280)
    }

    /**
     * Vuốt từ trên xuống (nội dung trước)
     */
    fun swipeDown(): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.25f
        val endX = width / 2f
        val endY = height * 0.8f

        return swipe(startX, startY, endX, endY, 280)
    }

    /**
     * Mô phỏng cử chỉ vuốt mượt từ tọa độ (x1, y1) đến (x2, y2)
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Chạm vào một tọa độ cụ thể trên màn hình
     */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Tìm kiếm node giao diện có chứa văn bản khớp và click vào nó
     * Hỗ trợ tìm cả node cha nếu node con không click được
     */
    fun clickNodeByText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val normalizedTarget = TextNormalizer.removeAccents(targetText).trim()

        val targetNode = findNodeMatchingText(rootNode, normalizedTarget)
        if (targetNode != null) {
            // Tìm node có thuộc tính isClickable = true (node hiện tại hoặc node cha của nó)
            var clickableNode: AccessibilityNodeInfo? = targetNode
            while (clickableNode != null && !clickableNode.isClickable) {
                val parent = clickableNode.parent
                clickableNode = parent
            }

            val nodeToClick = clickableNode ?: targetNode
            val clicked = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            if (!clicked) {
                // Nếu ACTION_CLICK không ăn, lấy tọa độ hiển thị trên màn hình để dispatchGesture Tap
                val rect = Rect()
                nodeToClick.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    val centerX = rect.centerX().toFloat()
                    val centerY = rect.centerY().toFloat()
                    Log.d(TAG, "Fallback chạm tọa độ màn hình nút: ($centerX, $centerY)")
                    return tapAt(centerX, centerY)
                }
            }

            return clicked
        }

        return false
    }

    private fun findNodeMatchingText(node: AccessibilityNodeInfo, normalizedTarget: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        val normText = TextNormalizer.removeAccents(text)
        val normDesc = TextNormalizer.removeAccents(contentDesc)

        if (normText.contains(normalizedTarget) || normDesc.contains(normalizedTarget)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeMatchingText(child, normalizedTarget)
            if (result != null) {
                return result
            }
            child.recycle()
        }

        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) {
                return result
            }
            child.recycle()
        }

        return null
    }

    // =========================================================================
    // CÁC LỆNH HỆ THỐNG GLOBAL
    // =========================================================================

    /**
     * Nhấn nút Quay lại (Back)
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Về màn hình chính (Home)
     */
    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Khóa màn hình (Hỗ trợ từ Android 9.0 Pie trở lên)
     */
    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    /**
     * Chụp ảnh màn hình (Hỗ trợ từ Android 11 trở lên)
     */
    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    /**
     * Lấy tên package của ứng dụng đang mở ở tiền cảnh (foreground active window)
     */
    fun getActivePackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Trích xuất cây phân cấp giao diện người dùng thành chuỗi văn bản cô đọng
     * phục vụ lập kế hoạch cho ReAct Behavior Agent
     */
    fun dumpNodeHierarchy(maxNodes: Int = 80): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        val rect = Rect()
        var nodeCount = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (nodeCount >= maxNodes) return
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val isClickable = node.isClickable
            val isScrollable = node.isScrollable
            val isEditable = node.isEditable

            // Chỉ thu thập các phần tử có thông tin hữu ích hoặc tương tác được
            if (text.isNotEmpty() || desc.isNotEmpty() || isClickable || isScrollable || isEditable) {
                node.getBoundsInScreen(rect)
                val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
                builder.append("[#${nodeCount + 1}] $className")
                if (text.isNotEmpty()) builder.append(" text=\"$text\"")
                if (desc.isNotEmpty()) builder.append(" desc=\"$desc\"")
                builder.append(" bounds=(${rect.left},${rect.top},${rect.right},${rect.bottom})")
                builder.append(" center=(${rect.centerX()},${rect.centerY()})")
                if (isClickable) builder.append(" [clickable]")
                if (isScrollable) builder.append(" [scrollable]")
                if (isEditable) builder.append(" [editable]")
                builder.append("\n")
                nodeCount++
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)
        return builder.toString()
    }

    /**
     * Nhập văn bản vào ô nhập liệu đang focus hoặc ô nhập liệu đầu tiên tìm thấy
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)

        if (focusedNode != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Gets current text inside the focused or first editable node.
     */
    fun getEditableText(): String? {
        val root = rootInActiveWindow ?: return null
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
        return focusedNode?.text?.toString()
    }

    /**
     * Finds the search input box or search trigger button on screen.
     */
    fun findSearchBox(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
        if (editable != null) return editable

        return findNodeMatchingText(root, "tim kiem")
            ?: findNodeMatchingText(root, "shopee")
            ?: findNodeMatchingText(root, "search")
    }

    /**
     * Attempts to trigger search submission via IME action or clicking search button.
     */
    fun performSearchAction(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (focused.actionList.contains(imeAction)) {
                val imeSubmitted = focused.performAction(imeAction.id)
                if (imeSubmitted) return true
            }
        }

        return clickNodeByText("Tìm kiếm") || clickNodeByText("Search")
    }

    /**
     * Verifies if a specific package is currently in the active foreground window.
     */
    fun isAppForeground(packageName: String): Boolean {
        return getActivePackageName() == packageName
    }

    companion object {
        private const val TAG = "JavisAccessibility"

        @Volatile
        var instance: JavisAccessibilityService? = null
            private set
    }
}
