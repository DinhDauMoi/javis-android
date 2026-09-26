package com.dinh.javis.agent

import android.content.Context
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.BehaviorAggregate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ tổng hợp số liệu hành vi cục bộ (100% on-device).
 * Giúp cải thiện trải nghiệm và thống kê các tác vụ phổ biến mà không lưu trữ thông tin nhạy cảm.
 */
class BehaviorAggregator(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    suspend fun recordTaskExecution(taskKey: String, isSuccess: Boolean) = withContext(Dispatchers.IO) {
        val today = dateFormat.format(Date())
        val existing = database.behaviorAggregateDao().getAggregate(taskKey, today)

        if (existing != null) {
            val updated = existing.copy(
                runCount = existing.runCount + 1,
                failureCount = if (isSuccess) existing.failureCount else existing.failureCount + 1
            )
            database.behaviorAggregateDao().insertOrUpdate(updated)
        } else {
            val newAggregate = BehaviorAggregate(
                taskKey = taskKey,
                date = today,
                runCount = 1,
                failureCount = if (isSuccess) 0 else 1
            )
            database.behaviorAggregateDao().insertOrUpdate(newAggregate)
        }

        // Tự động dọn dẹp các bản ghi audit log cũ hơn 7 ngày để tối ưu bộ nhớ
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        database.actionLogDao().purgeOlderThan(sevenDaysAgo)
    }

    suspend fun getAllStats(): List<BehaviorAggregate> = withContext(Dispatchers.IO) {
        database.behaviorAggregateDao().getAllAggregates()
    }

    suspend fun clearAllStats() = withContext(Dispatchers.IO) {
        database.behaviorAggregateDao().clearAll()
        database.taskRunDao().clearAllRuns()
        database.actionLogDao().clearAll()
    }
}
