package com.dinh.javis.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dinh.javis.MainActivity
import com.dinh.javis.R

/**
 * Foreground Service giúp JAVIS duy trì hoạt động lắng nghe Wake Word "javis" ngầm
 * ngay cả khi người dùng đang xem TikTok, YouTube hoặc tắt màn hình.
 *
 * Tối ưu riêng cho OPPO Find X8 Ultra (ColorOS 15 / Android 14+):
 * - Đăng ký foregroundServiceType="microphone".
 * - Tạo persistent notification rõ ràng, không bị hệ thống tự động tắt (kill).
 */
class HotwordService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification("Đang chờ gọi 'javis'...")
        Log.i(TAG, "HotwordService đã được tạo và kích hoạt Foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_STATUS_MESSAGE) ?: "Đang chờ gọi 'javis'..."
        updateNotification(message)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "HotwordService đã dừng")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dịch vụ Wake Word JAVIS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Duy trì lắng nghe từ khóa 'javis' khi xem TikTok và các ứng dụng khác"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JAVIS — Đang lắng nghe")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundWithNotification(message: String) {
        val notification = buildNotification(message)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi gọi startForeground", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (inner: Exception) {
                Log.e(TAG, "Fallback startForeground lỗi", inner)
            }
        }
    }

    fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "HotwordService"
        const val CHANNEL_ID = "javis_hotword_channel"
        const val NOTIFICATION_ID = 2026
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"

        fun start(context: Context, statusMessage: String = "Đang chờ gọi 'javis'...") {
            val intent = Intent(context, HotwordService::class.java).apply {
                putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Không thể khởi động HotwordService", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, HotwordService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi dừng HotwordService", e)
            }
        }
    }
}
