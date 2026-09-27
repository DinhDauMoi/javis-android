package com.dinh.javis.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for core task contracts (BA-02, BA-04, BA-05).
 * Covers:
 * - TaskBudget deadline and step cap
 * - TaskOutcome taxonomy
 * - RunContext deadline detection
 * - ApprovalRequest expiry
 */
class TaskContractsTest {

    // ── TaskBudget ────────────────────────────────────────────────────────

    @Test
    fun `effectiveActionSteps reserves steps for verification`() {
        val budget = TaskBudget(maxSteps = 8, verificationReserveSteps = 1)
        assertEquals("Should reserve 1 step for verification", 7, budget.effectiveActionSteps)
    }

    @Test
    fun `effectiveActionSteps is at least 1`() {
        val budget = TaskBudget(maxSteps = 1, verificationReserveSteps = 3)
        assertEquals("Effective steps must be at least 1", 1, budget.effectiveActionSteps)
    }

    @Test
    fun `DEFAULT budget has expected values`() {
        val b = TaskBudget.DEFAULT
        assertEquals(8, b.maxSteps)
        assertEquals(60_000L, b.deadlineMs)
        assertEquals(12, b.maxModelCalls)
        assertEquals(3, b.maxRetries)
    }

    @Test
    fun `QUICK budget is more restrictive than DEFAULT`() {
        assertTrue(TaskBudget.QUICK.maxSteps < TaskBudget.DEFAULT.maxSteps)
        assertTrue(TaskBudget.QUICK.deadlineMs < TaskBudget.DEFAULT.deadlineMs)
        assertTrue(TaskBudget.QUICK.maxModelCalls < TaskBudget.DEFAULT.maxModelCalls)
    }

    // ── TaskOutcome ───────────────────────────────────────────────────────

    @Test
    fun `only SUCCESS is terminal success`() {
        assertEquals(TaskOutcome.SUCCESS, TaskOutcome.values().single { it.isTerminalSuccess })
    }

    @Test
    fun `FAILED CANCELLED BLOCKED all have isNonSuccess`() {
        for (outcome in listOf(TaskOutcome.FAILED, TaskOutcome.CANCELLED, TaskOutcome.BLOCKED,
                               TaskOutcome.BUDGET_EXHAUSTED, TaskOutcome.NO_MATCH,
                               TaskOutcome.NEEDS_INPUT, TaskOutcome.INTERRUPTED)) {
            assertTrue("$outcome must be non-success", outcome.isNonSuccess)
        }
    }

    // ── TaskRequest ───────────────────────────────────────────────────────

    @Test
    fun `TaskRequest defaults to CATEGORY_GENERAL`() {
        val r = TaskRequest(goal = "test")
        assertEquals(TaskRequest.CATEGORY_GENERAL, r.taskCategory)
    }

    @Test
    fun `TaskRequest with no criteria has null completionCriteria`() {
        val r = TaskRequest(goal = "test")
        assertNull(r.completionCriteria)
    }

    @Test
    fun `TaskRequest with criteria can evaluate`() {
        val r = TaskRequest(
            goal = "test",
            completionCriteria = { obs -> obs.currentPackage == "com.target" }
        )
        assertNotNull(r.completionCriteria)
    }

    // ── ApprovalRequest expiry ────────────────────────────────────────────

    @Test
    fun `fresh ApprovalRequest is not expired given large timeout`() {
        val proposal = ActionProposal(runId = "r1", stepIndex = 1, action = "CLICK")
        // createdElapsedMs = 0 (past), timeoutMs = Long.MAX_VALUE → never expires
        val req = ApprovalRequest(
            runId = "r1",
            stepIndex = 1,
            action = proposal,
            reasonVi = "Test",
            contextPackage = null,
            createdAtMs = 0L,
            timeoutMs = Long.MAX_VALUE
        )
        assertFalse("Request with MAX timeout must not be expired", req.isExpired())
    }

    @Test
    fun `expired ApprovalRequest with past deadline reports expired`() {
        val proposal = ActionProposal(runId = "r1", stepIndex = 1, action = "CLICK")
        val req = ApprovalRequest(
            runId = "r1",
            stepIndex = 1,
            action = proposal,
            reasonVi = "Test",
            contextPackage = null,
            createdAtMs = 0L,
            timeoutMs = 1L  // 1ms — immediately expired given any positive elapsedRealtime
        )
        // elapsedRealtime() will be > 1ms since boot, so this is always expired
        assertTrue("Request with 1ms timeout must be expired", req.isExpired())
    }
}
