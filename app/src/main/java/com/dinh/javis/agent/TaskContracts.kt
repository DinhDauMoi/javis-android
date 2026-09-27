package com.dinh.javis.agent

import java.util.UUID

// ─────────────────────────────────────────────────────────────
// Task outcome taxonomy (BA-02)
// ─────────────────────────────────────────────────────────────

/**
 * Typed terminal outcomes for a task run.
 * Model completion (TERMINATE/isGoalComplete) is never automatically mapped to SUCCESS.
 */
enum class TaskOutcome {
    /** Application-owned completion criteria passed on a fresh observation. */
    SUCCESS,
    /** A bounded search found no eligible result. */
    NO_MATCH,
    /** Clarification or manual intervention is necessary. */
    NEEDS_INPUT,
    /** Policy or permissions prevent continuation. */
    BLOCKED,
    /** A task limit (steps, time, model calls) was reached. */
    BUDGET_EXHAUSTED,
    /** A planning, network, execution, or observation error prevented completion. */
    FAILED,
    /** The user explicitly stopped execution. */
    CANCELLED,
    /** Process or lifecycle termination left the task incomplete. */
    INTERRUPTED;

    val isTerminalSuccess: Boolean get() = this == SUCCESS
    val isNonSuccess: Boolean get() = this != SUCCESS
}

// ─────────────────────────────────────────────────────────────
// Task budget (BA-05)
// ─────────────────────────────────────────────────────────────

/**
 * Hard upper bounds for a single task run.
 * All limits apply per run; unused budget is not transferred.
 */
data class TaskBudget(
    /** Maximum discrete action steps. */
    val maxSteps: Int = 8,
    /** Maximum elapsed wall-clock time in milliseconds. */
    val deadlineMs: Long = 60_000L,
    /** Maximum model planning calls. */
    val maxModelCalls: Int = 12,
    /** Maximum retries for transient network/observation errors. */
    val maxRetries: Int = 3,
    /** Steps reserved for final verification and user handoff before exhaustion. */
    val verificationReserveSteps: Int = 1
) {
    /** Effective action budget excluding verification reserve. */
    val effectiveActionSteps: Int get() = (maxSteps - verificationReserveSteps).coerceAtLeast(1)

    companion object {
        /** Default budget used when no task profile specifies overrides. */
        val DEFAULT = TaskBudget()

        /** Tighter budget for quick commands. */
        val QUICK = TaskBudget(maxSteps = 4, deadlineMs = 30_000L, maxModelCalls = 6)
    }
}

// ─────────────────────────────────────────────────────────────
// Task request (BA-01, BA-03)
// ─────────────────────────────────────────────────────────────

/**
 * An authorized, scoped task request. Captures what the user wants done,
 * with explicit scope and completion criteria.
 *
 * @param goal The user-expressed goal (sanitized, not raw model output).
 * @param taskCategory Safe category key; must NOT encode raw user content.
 * @param allowedPackages Set of package names the agent may interact with.
 *        An empty set means no restriction beyond policy.
 * @param budget Budget limits for this run.
 * @param completionCriteria Optional lambda returning true when the goal is verified
 *        on a fresh observation. Null means the orchestrator must request user verification.
 */
data class TaskRequest(
    val goal: String,
    val taskCategory: String = CATEGORY_GENERAL,
    val allowedPackages: Set<String> = emptySet(),
    val budget: TaskBudget = TaskBudget.DEFAULT,
    val completionCriteria: ((observation: com.dinh.javis.ai.capabilities.ScreenObservation) -> Boolean)? = null
) {
    companion object {
        const val CATEGORY_GENERAL = "general"
        const val CATEGORY_NAVIGATION = "navigation"
        const val CATEGORY_SEARCH = "search"
        const val CATEGORY_MEDIA = "media"
        const val CATEGORY_SHOPPING = "shopping"
    }
}

// ─────────────────────────────────────────────────────────────
// Run context (BA-04)
// ─────────────────────────────────────────────────────────────

/**
 * Immutable identity for a single task run.
 * Used to reject stale callbacks from previous or cancelled runs.
 */
data class RunContext(
    val runId: String = UUID.randomUUID().toString(),
    val request: TaskRequest,
    val startWallMs: Long = System.currentTimeMillis(),
    val profileId: String = ""
) {
    /** True when the run's deadline has been exceeded. */
    fun isDeadlineExceeded(): Boolean {
        return (System.currentTimeMillis() - startWallMs) >= request.budget.deadlineMs
    }

    /** Elapsed time since run start in milliseconds. */
    fun elapsedMs(): Long = System.currentTimeMillis() - startWallMs
}

// ─────────────────────────────────────────────────────────────
// Action proposal and execution result (BA-07)
// ─────────────────────────────────────────────────────────────

/**
 * A typed action proposed by the planner, bound to the originating run.
 * Must be validated before dispatch (ActionValidator).
 */
data class ActionProposal(
    val runId: String,
    val stepIndex: Int,
    val action: String,       // CLICK, SCROLL, TYPE, WAIT, NAVIGATE_BACK, NAVIGATE_HOME
    val targetText: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val direction: String = "DOWN",
    val inputText: String = "",
    val expectedPostcondition: String = "",
    val plannerThought: String = ""
) {
    val isCoordinateBased: Boolean get() = x > 0f && y > 0f
}

/**
 * Result of dispatching an action to the accessibility service.
 */
sealed class ActionExecutionResult {
    /** Gesture/action was accepted and completed; observe next. */
    data class Dispatched(val proposal: ActionProposal) : ActionExecutionResult()

    /** Gesture was accepted but completion state is unknown; re-observe before next action. */
    data class UnknownEffect(val proposal: ActionProposal, val reason: String) : ActionExecutionResult()

    /** Action was rejected before dispatch (policy, validation, no service). */
    data class Rejected(val proposal: ActionProposal, val reason: String) : ActionExecutionResult()

    /** Action failed during or after dispatch. */
    data class Failed(val proposal: ActionProposal, val reason: String) : ActionExecutionResult()
}

// ─────────────────────────────────────────────────────────────
// Approval request (BA-01)
// ─────────────────────────────────────────────────────────────

/**
 * A bounded user-approval request scoped to an exact action, run, and screen context.
 * The model cannot self-approve; only an explicit user response authorizes execution.
 */
data class ApprovalRequest(
    val runId: String,
    val stepIndex: Int,
    val action: ActionProposal,
    val reasonVi: String,           // Vietnamese explanation shown to user
    val contextPackage: String?,
    val createdAtMs: Long = System.currentTimeMillis(),
    val timeoutMs: Long = 30_000L
) {
    /** True when this approval has expired. */
    fun isExpired(): Boolean = (System.currentTimeMillis() - createdAtMs) >= timeoutMs
}

// ─────────────────────────────────────────────────────────────
// Behavior metric event (BA-08)
// ─────────────────────────────────────────────────────────────

/**
 * A single consent-gated, sanitized performance event.
 * Contains NO raw user content, screen text, images, or model reasoning.
 */
data class TaskMetricEvent(
    val taskCategory: String,
    val outcome: TaskOutcome,
    val durationMs: Long,
    val verifiedActionCount: Int,
    val retryCount: Int,
    val failureCategory: String? = null // e.g. "NETWORK", "TIMEOUT", "POLICY", "PARSE_ERROR"
)

// ─────────────────────────────────────────────────────────────
// Callback interface (BA-11)
// ─────────────────────────────────────────────────────────────

/**
 * Typed progress callbacks emitted by AgentOrchestrator.
 * Replaces raw onThought exposure with concise status/evidence summaries.
 */
interface TaskProgressCallback {
    fun onStepStarted(stepIndex: Int, maxSteps: Int)
    /** Concise Vietnamese step summary, never raw model reasoning. */
    fun onStatusUpdate(statusVi: String)
    fun onActionExecuted(action: String, detailsVi: String)
    fun onApprovalRequired(request: ApprovalRequest, onUserResponse: (approved: Boolean) -> Unit)
    fun onCompleted(outcome: TaskOutcome, messageVi: String)
}
