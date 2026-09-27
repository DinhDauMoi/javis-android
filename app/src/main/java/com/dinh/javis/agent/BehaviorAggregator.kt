package com.dinh.javis.agent

import android.content.Context
import android.util.Log
import com.dinh.javis.data.AppDatabase
import com.dinh.javis.data.BehaviorAggregate
import com.dinh.javis.data.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local behavior aggregator with explicit opt-in consent (BA-08, BA-09).
 *
 * Consent rules:
 * - Analytics collection is OFF by default.
 * - Stopping analytics collection immediately prevents new records from being written.
 * - The user can independently delete task history and behavior statistics.
 * - No raw user goals, screen content, or model reasoning is ever stored in aggregates.
 *
 * Counter rules (BA-09):
 * - Success, failure, cancellation, blocked, and interrupted outcomes are tracked separately.
 * - Counters are updated atomically using an idempotent upsert strategy.
 * - Duplicate finalization of the same run cannot count twice.
 * - Date boundaries use UTC to avoid local timezone drift.
 *
 * Retention (BA-09):
 * - Action audit logs: 7 days (operational history).
 * - Behavior aggregates: 30 days (opted-in analytics).
 * - Cleanup runs independently on suitable lifecycle events, not only after task completion.
 * - User-created commands, profiles, and settings are NEVER deleted by this cleanup.
 */
class BehaviorAggregator(private val context: Context) {

    private val TAG = "BehaviorAggregator"
    private val database = AppDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)

    // UTC date format for consistent day-boundary behavior across timezones
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Track recorded runIds to prevent double-counting (in-memory guard for a single session)
    private val recordedRunIds = mutableSetOf<String>()

    /**
     * Records the outcome of a completed task run.
     *
     * @param runId Unique run identifier — used to prevent double-counting.
     * @param taskCategory Safe category identifier; must NOT be the raw user goal.
     * @param event Sanitized metric event (no raw screen/goal content).
     */
    suspend fun recordTaskMetric(
        runId: String,
        taskCategory: String,
        event: TaskMetricEvent
    ) = withContext(Dispatchers.IO) {
        // BA-08: skip if analytics not enabled
        if (!preferenceManager.isBehaviorAnalyticsEnabled) {
            Log.d(TAG, "Analytics disabled — skipping aggregate record for run $runId.")
            return@withContext
        }

        // BA-09: idempotency — skip if this run was already finalized
        synchronized(recordedRunIds) {
            if (runId in recordedRunIds) {
                Log.w(TAG, "Duplicate finalization for run $runId — skipping aggregate update.")
                return@withContext
            }
            recordedRunIds.add(runId)
        }

        val today = dateFormat.format(Date())
        val existing = database.behaviorAggregateDao().getAggregate(taskCategory, today)

        if (existing != null) {
            // Atomic update using explicit UPDATE query
            val updated = existing.copy(
                runCount = existing.runCount + 1,
                failureCount = existing.failureCount + if (event.outcome == TaskOutcome.FAILED) 1 else 0,
                cancelledCount = existing.cancelledCount + if (event.outcome == TaskOutcome.CANCELLED) 1 else 0,
                blockedCount = existing.blockedCount + if (event.outcome == TaskOutcome.BLOCKED) 1 else 0,
                interruptedCount = existing.interruptedCount + if (event.outcome == TaskOutcome.INTERRUPTED) 1 else 0,
                totalDurationMs = existing.totalDurationMs + event.durationMs,
                totalVerifiedActions = existing.totalVerifiedActions + event.verifiedActionCount
            )
            database.behaviorAggregateDao().updateCounts(
                taskKey = updated.taskKey,
                date = updated.date,
                runCount = updated.runCount,
                failureCount = updated.failureCount,
                cancelledCount = updated.cancelledCount,
                blockedCount = updated.blockedCount,
                interruptedCount = updated.interruptedCount,
                totalDurationMs = updated.totalDurationMs,
                totalVerifiedActions = updated.totalVerifiedActions
            )
        } else {
            database.behaviorAggregateDao().insertOrUpdate(
                BehaviorAggregate(
                    taskKey = taskCategory,
                    date = today,
                    runCount = 1,
                    failureCount = if (event.outcome == TaskOutcome.FAILED) 1 else 0,
                    cancelledCount = if (event.outcome == TaskOutcome.CANCELLED) 1 else 0,
                    blockedCount = if (event.outcome == TaskOutcome.BLOCKED) 1 else 0,
                    interruptedCount = if (event.outcome == TaskOutcome.INTERRUPTED) 1 else 0,
                    totalDurationMs = event.durationMs,
                    totalVerifiedActions = event.verifiedActionCount
                )
            )
        }
        Log.d(TAG, "Recorded metric for category=$taskCategory outcome=${event.outcome}")
    }

    /**
     * Gets all aggregate stats for the suggestion engine.
     */
    suspend fun getAllStats(): List<BehaviorAggregate> = withContext(Dispatchers.IO) {
        database.behaviorAggregateDao().getAllAggregates()
    }

    /**
     * Deletes ONLY behavior analytics aggregates (BA-09: independent deletion control).
     * Does NOT delete task history (task_runs, action_logs) or any user-created data.
     */
    suspend fun clearBehaviorStatistics() = withContext(Dispatchers.IO) {
        database.behaviorAggregateDao().clearAll()
        recordedRunIds.clear()
        Log.i(TAG, "Behavior analytics statistics cleared by user request.")
    }

    /**
     * Deletes task run history and action logs only.
     * Does NOT delete behavior aggregates or user-created commands/profiles/settings.
     */
    suspend fun clearTaskHistory() = withContext(Dispatchers.IO) {
        database.taskRunDao().clearAllRuns()
        database.actionLogDao().clearAll()
        Log.i(TAG, "Task history cleared by user request.")
    }

    /**
     * Performs scheduled retention cleanup.
     * - Action logs: purge older than 7 days (operational history).
     * - Behavior aggregates: purge older than 30 days (opted-in analytics, if enabled).
     *
     * Must NOT delete user-created commands, profiles, or settings.
     * Call this from a WorkManager or suitable lifecycle event, NOT only after task completion.
     */
    suspend fun runRetentionCleanup() = withContext(Dispatchers.IO) {
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        database.actionLogDao().purgeOlderThan(sevenDaysAgo)

        if (preferenceManager.isBehaviorAnalyticsEnabled) {
            // 30-day retention cutoff as UTC date string
            val cutoff = dateFormat.format(Date(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)))
            database.behaviorAggregateDao().purgeOlderThan(cutoff)
            Log.d(TAG, "Retention cleanup complete. Action logs >7d purged. Aggregates >${cutoff} purged.")
        } else {
            Log.d(TAG, "Retention cleanup: action logs purged. Aggregates skipped (analytics disabled).")
        }
    }

    /**
     * On consent withdrawal: stop collection and clear associated aggregates per policy (BA-09).
     */
    suspend fun onAnalyticsConsentWithdrawn() = withContext(Dispatchers.IO) {
        database.behaviorAggregateDao().clearAll()
        recordedRunIds.clear()
        Log.i(TAG, "Analytics consent withdrawn. All aggregate records cleared.")
    }
}
