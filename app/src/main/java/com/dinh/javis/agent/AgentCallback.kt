package com.dinh.javis.agent

/**
 * Legacy backward-compatible callback interface for AgentOrchestrator.
 * Used by CommandExecutor until it migrates to TaskProgressCallback.
 *
 * New consumers should use TaskProgressCallback instead.
 */
interface AgentCallback {
    fun onStepStarted(stepIndex: Int, maxSteps: Int)
    /** Receives a concise status summary (NOT raw model reasoning). */
    fun onThought(thought: String)
    fun onActionExecuted(action: String, details: String)
    /** Real confirmation UI must be shown; onUserResponse MUST NOT be auto-called. */
    fun onConfirmationRequired(question: String, onUserResponse: (Boolean) -> Unit)
    fun onCompleted(success: Boolean, message: String)
}
