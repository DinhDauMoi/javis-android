package com.dinh.javis.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dinh.javis.service.JavisAccessibilityService
import com.dinh.javis.service.JavisDeviceAdminReceiver

/**
 * Tiện ích kiểm tra và yêu cầu các quyền hạn cần thiết trong JAVIS
 */
object PermissionHelper {

    const val REQUEST_CODE_CORE_PERMISSIONS = 1001

    /**
     * Danh sách các quyền Runtime cơ bản cần xin
     */
    fun getRequiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )

        // Quyền Bluetooth trên Android 12 (API 31) trở lên
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Quyền thông báo trên Android 13 (API 33) trở lên
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * Kiểm tra xem đã được cấp tất cả các quyền runtime cơ bản chưa
     */
    fun hasCorePermissions(context: Context): Boolean {
        for (perm in getRequiredRuntimePermissions()) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    /**
     * Kiểm tra xem một quyền cụ thể đã được cấp hay chưa
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Yêu cầu cấp các quyền runtime còn thiếu
     */
    fun requestCorePermissions(activity: Activity) {
        val missingPermissions = getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_CORE_PERMISSIONS
            )
        }
    }

    /**
     * Kiểm tra xem dịch vụ Trợ năng JavisAccessibilityService đã được người dùng bật chưa
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // Kiểm tra instance trực tiếp từ service nếu đang chạy
        if (JavisAccessibilityService.instance != null) {
            return true
        }

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedComponentName = ComponentName(context, JavisAccessibilityService::class.java)

        for (service in enabledServices) {
            val serviceInfo = service.resolveInfo?.serviceInfo ?: continue
            val currentComponent = ComponentName(serviceInfo.packageName, serviceInfo.name)
            if (currentComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * Mở trực tiếp màn hình Cài đặt Trợ năng để người dùng kích hoạt JAVIS
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Kiểm tra quyền vẽ đè lên màn hình khác (cho nút mic nổi)
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Mở màn hình cấp quyền vẽ trên ứng dụng khác
     */
    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Kiểm tra quyền Quản trị viên thiết bị (Device Admin) dùng để khóa màn hình
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val adminComponent = ComponentName(context, JavisDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * Mở intent yêu cầu người dùng kích hoạt quyền Device Admin
     */
    fun requestDeviceAdmin(activity: Activity) {
        val adminComponent = ComponentName(activity, JavisDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "JAVIS cần quyền này để thực hiện lệnh khóa màn hình bằng giọng nói theo yêu cầu của bạn."
            )
        }
        activity.startActivity(intent)
    }
}
