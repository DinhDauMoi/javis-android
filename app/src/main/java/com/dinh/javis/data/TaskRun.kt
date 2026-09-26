package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lịch sử thực thi một phiên tác vụ tự động của hành vi Behavior Agent
 */
@Entity(tableName = "task_runs")
data class TaskRun(
    @PrimaryKey
    val runId: String,
    val profileId: String,
    val taskGoal: String,
    val startTime: Long,
    val endTime: Long,
    val status: String, // SUCCESS, FAILED, CANCELLED
    val stepCount: Int,
    val failureReason: String? = null
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
