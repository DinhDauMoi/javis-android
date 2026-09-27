package com.dinh.javis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single task run record. Stores outcome metadata but never raw screen content.
 *
 * Schema: compatible with existing v2 schema for outcome/verifiedActionCount additions.
 * New columns (outcome, verifiedActionCount, durationMs) have nullable/default values.
 */
@Entity(tableName = "task_runs")
data class TaskRun(
    @PrimaryKey
    val runId: String,
    val profileId: String,

    /** Sanitized goal — should be truncated at storage time if too long. */
    val taskGoal: String,

    val startTime: Long,
    val endTime: Long,

    /** Legacy string status for backward compatibility. */
    val status: String,

    val stepCount: Int,
    val failureReason: String? = null,

    /** Verified action count (only counting dispatched+confirmed steps). */
    val verifiedActionCount: Int = 0,

    /** Task duration in milliseconds (endTime - startTime). */
    val durationMs: Long = 0L,

    /** Task category key from TaskRequest (not raw goal). */
    val taskCategory: String = "general"
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
        const val STATUS_NO_MATCH = "NO_MATCH"
        const val STATUS_NEEDS_INPUT = "NEEDS_INPUT"
        const val STATUS_INTERRUPTED = "INTERRUPTED"

        /** Max goal length stored to prevent leaking sensitive content in run history. */
        const val MAX_STORED_GOAL_LENGTH = 120

        fun statusFromOutcome(outcome: com.dinh.javis.agent.TaskOutcome): String {
            return when (outcome) {
                com.dinh.javis.agent.TaskOutcome.SUCCESS -> STATUS_SUCCESS
                com.dinh.javis.agent.TaskOutcome.NO_MATCH -> STATUS_NO_MATCH
                com.dinh.javis.agent.TaskOutcome.NEEDS_INPUT -> STATUS_NEEDS_INPUT
                com.dinh.javis.agent.TaskOutcome.BLOCKED -> STATUS_BLOCKED
                com.dinh.javis.agent.TaskOutcome.BUDGET_EXHAUSTED -> STATUS_BUDGET_EXHAUSTED
                com.dinh.javis.agent.TaskOutcome.FAILED -> STATUS_FAILED
                com.dinh.javis.agent.TaskOutcome.CANCELLED -> STATUS_CANCELLED
                com.dinh.javis.agent.TaskOutcome.INTERRUPTED -> STATUS_INTERRUPTED
            }
        }
    }
}
