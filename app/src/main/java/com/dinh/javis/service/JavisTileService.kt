package com.dinh.javis.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.dinh.javis.MainActivity

/**
 * Ô Quick Settings Tile trên thanh thông báo Android
 * Giúp người dùng gọi JAVIS bất cứ lúc nào khi đang dùng các app khác
 */
class JavisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = "JAVIS Voice"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Đã nhấn Quick Settings Tile JAVIS")

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TRIGGER_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ yêu cầu PendingIntent cho startActivityAndCollapse
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "JavisTileService"
    }
}
