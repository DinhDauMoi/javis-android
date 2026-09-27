package com.dinh.javis.agent

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages real user-approval requests for the Behavior Agent (BA-01).
 *
 * Design rules enforced:
 * - Only one valid user response can authorize a bound action (no duplicate approvals).
 * - Approval is scoped to exact (runId, stepIndex, action). Stale or wrong-run responses are ignored.
 * - Approval expires if not answered within the request's timeout, on screen change, or on cancellation.
 * - The model cannot self-approve; this class never calls onUserResponse automatically.
 * - If no eligible UI/callback is available, returns denied (execution must not proceed).
 */
class ApprovalManager {

    private val TAG = "ApprovalManager"

    /** Tracks whether a response for the current request has already been consumed. */
    @Volatile
    private var responseConsumed = false

    /**
     * Suspends until the user explicitly approves or denies the [request], or until
     * the approval timeout expires. The callback [onShowToUser] is invoked on the calling
     * coroutine dispatcher to present the UI prompt.
     *
     * Returns true only for an explicit, unexpired, unconsumed approval for the exact request.
     */
    suspend fun requestApproval(
        activeRunContext: RunContext,
        request: ApprovalRequest,
        onShowToUser: (ApprovalRequest, onUserResponse: (Boolean) -> Unit) -> Unit
    ): Boolean {
        // Guard: reject mismatched run
        if (request.runId != activeRunContext.runId) {
            Log.w(TAG, "Approval request runId mismatch — rejecting silently.")
            return false
        }

        responseConsumed = false

        val result: Boolean? = withTimeoutOrNull(request.timeoutMs) {
            suspendCancellableCoroutine { cont ->
                // Pass a one-shot, consumption-guarded responder to the UI
                val responder: (Boolean) -> Unit = responder@{ approved ->
                    // Reject if: already consumed, expired, or stale run
                    if (responseConsumed) {
                        Log.w(TAG, "Duplicate approval response discarded.")
                        return@responder
                    }
                    if (request.isExpired()) {
                        Log.w(TAG, "Approval response arrived after expiry — discarded.")
                        if (cont.isActive) cont.resume(false)
                        return@responder
                    }
                    responseConsumed = true
                    if (cont.isActive) cont.resume(approved)
                }

                onShowToUser(request, responder)

                // On cancellation (task cancelled, screen changed), treat as denied
                cont.invokeOnCancellation {
                    Log.d(TAG, "Approval coroutine cancelled — treating as denied.")
                }
            }
        }

        // Timeout or cancellation → denied
        if (result == null) {
            Log.w(TAG, "Approval timed out after ${request.timeoutMs}ms — denying.")
        }
        return result == true
    }

    /**
     * Immediately invalidates any pending approval (e.g., on screen/app change or run cancellation).
     * Forces any active [requestApproval] call to return denied at next check.
     */
    fun invalidatePending() {
        responseConsumed = true
    }

    companion object {
        /**
         * Creates an [ApprovalRequest] bound to the given run context and proposed action.
         */
        fun createRequest(
            context: RunContext,
            action: ActionProposal,
            reasonVi: String,
            timeoutMs: Long = 30_000L
        ): ApprovalRequest = ApprovalRequest(
            runId = context.runId,
            stepIndex = action.stepIndex,
            action = action,
            reasonVi = reasonVi,
            contextPackage = null, // filled by caller with current observation
            createdAtMs = System.currentTimeMillis(),
            timeoutMs = timeoutMs
        )
    }
}
