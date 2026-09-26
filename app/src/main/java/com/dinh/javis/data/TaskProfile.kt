package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Hồ sơ tác vụ tự động hóa định nghĩa giới hạn và các ứng dụng được phép chạy
 */
@Entity(tableName = "task_profiles")
data class TaskProfile(
    @PrimaryKey
    val id: String,
    val name: String,
    val allowedPackages: String, // Danh sách package cho phép, phân tách bằng dấu phẩy
    val maxSteps: Int = 8
)
