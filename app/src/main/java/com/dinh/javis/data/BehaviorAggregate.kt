package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local behavior aggregate record (BA-08, BA-09).
 *
 * Privacy constraints:
 * - taskKey MUST be a safe category identifier (e.g., "search", "media") — never raw user goal text.
 * - No raw UI trees, screenshots, typed content, or model reasoning stored here.
 * - Outcome counts are separate to avoid distorting failure rate.
 *
 * Schema change from v2: added cancelledCount, blockedCount, interruptedCount columns,
 * added unique index on (taskKey, date) to prevent duplicate buckets.
 * New columns have default = 0 for backward compatibility.
 */
@Entity(
    tableName = "behavior_aggregates",
    indices = [Index(value = ["taskKey", "date"], unique = true)]
)
data class BehaviorAggregate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Safe task category — must NOT encode raw user-entered goal text. */
    val taskKey: String,

    /** Date bucket in UTC format YYYY-MM-DD. */
    val date: String,

    /** Total number of task run attempts in this bucket. */
    val runCount: Int = 0,

    /** Number of runs that ended with FAILED outcome. */
    val failureCount: Int = 0,

    /** Number of runs explicitly cancelled by the user. */
    val cancelledCount: Int = 0,

    /** Number of runs blocked by policy. */
    val blockedCount: Int = 0,

    /** Number of runs interrupted by process/lifecycle termination. */
    val interruptedCount: Int = 0,

    /** Sum of durations in milliseconds (for computing average). */
    val totalDurationMs: Long = 0L,

    /** Sum of verified action counts (for computing average). */
    val totalVerifiedActions: Int = 0
) {
    /** Computed success count (not stored, derived from other fields). */
    val successCount: Int
        get() = (runCount - failureCount - cancelledCount - blockedCount - interruptedCount)
            .coerceAtLeast(0)

    /** Success rate as a value from 0.0 to 1.0. */
    val successRate: Float
        get() = if (runCount > 0) successCount.toFloat() / runCount else 0f

    /** Average task duration in milliseconds. */
    val averageDurationMs: Long
        get() = if (runCount > 0) totalDurationMs / runCount else 0L
}
