package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Thực thể lưu trữ lệnh tùy chỉnh do người dùng tự thiết lập
 */
@Entity(tableName = "custom_commands")
data class CustomCommand(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val triggerPhrase: String,       // Câu nói người dùng sẽ nói (ví dụ: "lướt video tiếp")
    val normalizedPhrase: String,   // Câu nói đã loại bỏ dấu để so khớp nhanh
    val actionType: String,         // OPEN_APP, SCROLL_UP, SCROLL_DOWN, CLICK_TEXT, OPEN_URL
    val targetParam: String         // Tham số: package name, url, text nút bấm
) {
    companion object {
        const val ACTION_OPEN_APP = "OPEN_APP"
        const val ACTION_SCROLL_UP = "SCROLL_UP"
        const val ACTION_SCROLL_DOWN = "SCROLL_DOWN"
        const val ACTION_CLICK_TEXT = "CLICK_TEXT"
        const val ACTION_OPEN_URL = "OPEN_URL"
    }
}
