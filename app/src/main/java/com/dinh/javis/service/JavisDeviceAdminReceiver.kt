package com.dinh.javis.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receiver nhận sự kiện quản trị thiết bị (Device Admin)
 * Phục vụ lệnh khóa màn hình bằng giọng nói
 */
class JavisDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "JAVIS đã được kích hoạt quyền Khóa màn hình", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "JAVIS đã tắt quyền Khóa màn hình", Toast.LENGTH_SHORT).show()
    }
}
