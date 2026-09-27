package com.dinh.javis.agent

import com.dinh.javis.ai.capabilities.ScreenObservation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TaskVerifier (BA-07).
 * Tests:
 * - Scroll postcondition: content must change
 * - CLICK/TYPE: unknown effect treated as valid (avoid blind replay)
 * - Task completion criteria check
 * - Stuck detection via repeated identical observations
 */
class TaskVerifierTest {

    private lateinit var verifier: TaskVerifier

    @Before
    fun setup() {
        verifier = TaskVerifier()
    }

    private fun obs(pkg: String = "com.example", nodeText: String? = null, ocrText: String? = null) =
        ScreenObservation(currentPackage = pkg, nodeHierarchyText = nodeText, ocrText = ocrText)

    private fun proposal(action: String, direction: String = "DOWN") = ActionProposal(
        runId = "r1", stepIndex = 1, action = action, direction = direction
    )

    // ── Scroll postcondition ─────────────────────────────────────────────

    @Test
    fun `SCROLL returns true when content changes`() {
        val before = obs(nodeText = "Item A, Item B")
        val after = obs(nodeText = "Item C, Item D")
        assertTrue(verifier.verifyActionPostcondition(proposal("SCROLL"), before, after))
    }

    @Test
    fun `SCROLL returns false when content is unchanged`() {
        val same = obs(nodeText = "Same content")
        assertFalse(verifier.verifyActionPostcondition(proposal("SCROLL"), same, same))
    }

    // ── CLICK postcondition (hardened: zero screen change returns false) ──
    @Test
    fun `CLICK returns false when content does not change`() {
        val same = obs(nodeText = "Unchanged content")
        assertFalse(verifier.verifyActionPostcondition(proposal("CLICK"), same, same))
    }

    @Test
    fun `CLICK returns true when content does change`() {
        val before = obs(nodeText = "Product list")
        val after = obs(nodeText = "Product detail page")
        assertTrue(verifier.verifyActionPostcondition(proposal("CLICK"), before, after))
    }

    // ── Task completion criteria ─────────────────────────────────────────

    @Test
    fun `checkTaskCompletion returns null when no criteria defined`() {
        val request = TaskRequest(goal = "test", completionCriteria = null)
        val result = verifier.checkTaskCompletion(request, obs())
        assertNull("No criteria should return null (needs input)", result)
    }

    @Test
    fun `checkTaskCompletion returns true when criteria passes`() {
        val request = TaskRequest(
            goal = "test",
            completionCriteria = { observation -> observation.currentPackage == "com.target.app" }
        )
        val result = verifier.checkTaskCompletion(request, obs(pkg = "com.target.app"))
        assertTrue(result == true)
    }

    @Test
    fun `checkTaskCompletion returns false when criteria fails`() {
        val request = TaskRequest(
            goal = "test",
            completionCriteria = { observation -> observation.currentPackage == "com.target.app" }
        )
        val result = verifier.checkTaskCompletion(request, obs(pkg = "com.wrong.app"))
        assertTrue(result == false)
    }

    @Test
    fun `checkTaskCompletion returns false when criteria throws`() {
        val request = TaskRequest(
            goal = "test",
            completionCriteria = { throw RuntimeException("Criteria exception") }
        )
        val result = verifier.checkTaskCompletion(request, obs())
        assertTrue(result == false)
    }

    // ── Stuck detection ──────────────────────────────────────────────────

    @Test
    fun `isStuck returns false with fewer than windowSize observations`() {
        val obs1 = obs(nodeText = "Same")
        val obs2 = obs(nodeText = "Same")
        assertFalse(verifier.isStuck(listOf(obs1, obs2), windowSize = 3))
    }

    @Test
    fun `isStuck returns true when last N observations are identical`() {
        val sameObs = obs(nodeText = "Stuck screen content")
        val observations = listOf(sameObs, sameObs, sameObs)
        assertTrue(verifier.isStuck(observations, windowSize = 3))
    }

    @Test
    fun `isStuck returns false when observations differ`() {
        val observations = listOf(
            obs(nodeText = "Screen 1"),
            obs(nodeText = "Screen 2"),
            obs(nodeText = "Screen 3")
        )
        assertFalse(verifier.isStuck(observations, windowSize = 3))
    }

    @Test
    fun `isStuck uses only last N observations`() {
        val observations = listOf(
            obs(nodeText = "Different early"),
            obs(nodeText = "Stuck"),
            obs(nodeText = "Stuck"),
            obs(nodeText = "Stuck")
        )
        assertTrue(verifier.isStuck(observations, windowSize = 3))
    }
}
