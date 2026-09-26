package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Thống kê tổng hợp số lần thực hiện tác vụ và tỉ lệ thành công/thất bại (không lưu nội dung màn hình)
 */
@Entity(tableName = "behavior_aggregates")
data class BehaviorAggregate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskKey: String,
    val date: String, // Định dạng YYYY-MM-DD
    val runCount: Int = 0,
    val failureCount: Int = 0
)
