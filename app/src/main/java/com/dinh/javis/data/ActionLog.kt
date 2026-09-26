package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Nhật ký kiểm tra từng hành động riêng lẻ (Click, Scroll, Type, Wait)
 */
@Entity(tableName = "action_logs")
data class ActionLog(
    @PrimaryKey(autoGenerate = true)
    val actionId: Long = 0,
    val runId: String,
    val actionType: String, // CLICK, SCROLL, INPUT, WAIT, TERMINATE
    val targetPackage: String,
    val sanitizedDetails: String,
    val timestamp: Long
) {
    companion object {
        const val ACTION_CLICK = "CLICK"
        const val ACTION_SCROLL = "SCROLL"
        const val ACTION_INPUT = "INPUT"
        const val ACTION_WAIT = "WAIT"
        const val ACTION_TERMINATE = "TERMINATE"
    }
}
