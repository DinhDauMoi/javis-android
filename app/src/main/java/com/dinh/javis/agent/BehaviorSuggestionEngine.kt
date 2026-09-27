package com.dinh.javis.agent

import android.util.Log
import com.dinh.javis.data.BehaviorAggregate

/**
 * Generates deterministic, explainable suggestions from local aggregate statistics (BA-10).
 *
 * Design constraints:
 * - Uses only deterministic statistics: task types, success rates, durations, error categories.
 * - Requires a minimum sample count before suggesting anything.
 * - Never describes suggestions as "AI training" or "guaranteed optimization".
 * - Suggestions never automatically change policy, permissions, or spending limits.
 * - All suggestions are optional and dismissible; user must explicitly approve changes.
 * - All text is in Vietnamese (UI-facing).
 */
class BehaviorSuggestionEngine {

    private val TAG = "BehaviorSuggestionEngine"

    /** Minimum number of task runs required before producing a suggestion. */
    private val MIN_SAMPLE_COUNT = 5

    /** Failure rate threshold above which a suggestion is generated. */
    private val HIGH_FAILURE_RATE_THRESHOLD = 0.5

    /**
     * A deterministic, explainable suggestion for the user.
     */
    data class Suggestion(
        /** Vietnamese display title. */
        val titleVi: String,
        /** Vietnamese explanation including sample count and time window. */
        val descriptionVi: String,
        /** Optional suggested action text (e.g., "Tạo lệnh tùy chỉnh"). */
        val actionLabelVi: String? = null,
        /** Evidence: how many runs this is based on. */
        val sampleCount: Int,
        /** Evidence: time window string shown to user (e.g., "7 ngày qua"). */
        val windowLabel: String = "7 ngày qua"
    )

    /**
     * Generates suggestions based on aggregate behavior stats.
     * Returns an empty list when no meaningful suggestion can be made.
     *
     * @param aggregates All local aggregate records.
     * @param analyticsEnabled Whether the user has opted into behavior analytics.
     * @return A list of ordered, relevant suggestions (most actionable first).
     */
    fun generateSuggestions(
        aggregates: List<BehaviorAggregate>,
        analyticsEnabled: Boolean
    ): List<Suggestion> {
        if (!analyticsEnabled) {
            Log.d(TAG, "Analytics disabled — no suggestions generated.")
            return emptyList()
        }
        if (aggregates.isEmpty()) return emptyList()

        val suggestions = mutableListOf<Suggestion>()

        // Group by taskCategory
        val byCategory = aggregates.groupBy { it.taskKey }

        for ((category, records) in byCategory) {
            val totalRuns = records.sumOf { it.runCount }
            val totalFailures = records.sumOf { it.failureCount }
            val totalCancelled = records.sumOf { it.cancelledCount }
            val totalBlocked = records.sumOf { it.blockedCount }

            if (totalRuns < MIN_SAMPLE_COUNT) continue

            val successCount = totalRuns - totalFailures - totalCancelled - totalBlocked
            val failureRate = if (totalRuns > 0) totalFailures.toDouble() / totalRuns else 0.0

            // Suggest creating a custom command for frequently-run tasks with good success rates
            if (totalRuns >= MIN_SAMPLE_COUNT && failureRate < 0.3 && successCount >= MIN_SAMPLE_COUNT) {
                suggestions.add(
                    Suggestion(
                        titleVi = "Tạo lệnh nhanh cho \"$category\"",
                        descriptionVi = "Bạn đã thực hiện loại tác vụ này $totalRuns lần trong 7 ngày qua " +
                                "với tỉ lệ thành công ${((1 - failureRate) * 100).toInt()}%. " +
                                "Bạn có muốn tạo một lệnh tùy chỉnh để thực hiện nhanh hơn không?",
                        actionLabelVi = "Tạo lệnh tùy chỉnh",
                        sampleCount = totalRuns
                    )
                )
            }

            // Suggest adjusting task profile limits for frequently failing tasks
            if (failureRate >= HIGH_FAILURE_RATE_THRESHOLD && totalRuns >= MIN_SAMPLE_COUNT) {
                suggestions.add(
                    Suggestion(
                        titleVi = "Tác vụ \"$category\" thường xuyên thất bại",
                        descriptionVi = "Trong $totalRuns lần thực hiện gần đây, tỉ lệ thất bại là " +
                                "${(failureRate * 100).toInt()}%. Bạn có thể thử tăng giới hạn bước " +
                                "hoặc thời gian tối đa trong Cài đặt tác vụ.",
                        actionLabelVi = "Điều chỉnh cấu hình tác vụ",
                        sampleCount = totalRuns
                    )
                )
            }
        }

        // Sort: most samples first (most evidence)
        return suggestions.sortedByDescending { it.sampleCount }
    }

    /**
     * Returns a simple summary of overall analytics in Vietnamese.
     * Includes sample count and window, never raw goal content.
     */
    fun buildAnalyticsSummaryVi(aggregates: List<BehaviorAggregate>): String {
        if (aggregates.isEmpty()) return "Chưa có dữ liệu thống kê nào được ghi lại."
        val totalRuns = aggregates.sumOf { it.runCount }
        val totalSuccess = aggregates.sumOf { it.runCount - it.failureCount - it.cancelledCount - it.blockedCount }
        val successRate = if (totalRuns > 0) (totalSuccess * 100 / totalRuns) else 0
        return "Đã thực hiện $totalRuns tác vụ trong 30 ngày qua. Tỉ lệ thành công: $successRate%."
    }
}
