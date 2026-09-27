package com.dinh.javis.agent

import android.util.Log
import com.dinh.javis.ai.capabilities.ScreenObservation
import com.dinh.javis.vision.ObservationResult
import kotlinx.coroutines.delay

/**
 * Verifies action postconditions and final task completion (BA-07).
 *
 * Principles:
 * - A successful gesture dispatch is NOT sufficient to mark a step or task as done.
 * - Each action has an expected postcondition checked on a fresh observation.
 * - Final task success requires a fresh re-observation and passing the task's completion criteria.
 * - If criteria are absent, the task is NOT automatically declared successful.
 *
 * This class is stateless and deterministic; inject fakes for unit testing.
 */
class TaskVerifier {

    private val TAG = "TaskVerifier"

    /**
     * Verifies the expected postcondition of an action by comparing before/after observations.
     *
     * @param proposal The action that was dispatched.
     * @param before Observation captured before the action.
     * @param after Fresh observation captured after the action.
     * @return True if the expected screen change is consistent with the action's effect.
     */
    fun verifyActionPostcondition(
        proposal: ActionProposal,
        before: ScreenObservation,
        after: ScreenObservation
    ): Boolean {
        return when (proposal.action.uppercase()) {
            "CLICK", "TYPE" -> {
                // Verify: UI content changed OR a new package/activity became visible
                val contentChanged = (after.nodeHierarchyText != before.nodeHierarchyText) ||
                        (after.ocrText != before.ocrText) ||
                        (after.currentPackage != before.currentPackage)
                if (!contentChanged) {
                    Log.w(TAG, "CLICK/TYPE: no observable screen change detected — verification failed, requires retry or re-observation.")
                }
                contentChanged
            }

            "SCROLL" -> {
                // Verify: content shifted (text or hierarchy differ after scroll)
                val scrolled = (after.nodeHierarchyText != before.nodeHierarchyText) ||
                        (after.ocrText != before.ocrText)
                if (!scrolled) {
                    Log.w(TAG, "SCROLL: content unchanged — possibly at list boundary.")
                }
                scrolled
            }

            "NAVIGATE_BACK" -> {
                // Package or hierarchy should change when back is pressed
                (after.currentPackage != before.currentPackage) ||
                        (after.nodeHierarchyText != before.nodeHierarchyText)
            }

            "NAVIGATE_HOME" -> {
                // Should land on launcher
                after.currentPackage?.contains("launcher", ignoreCase = true) == true ||
                        after.currentPackage?.contains("home", ignoreCase = true) == true ||
                        after.currentPackage != before.currentPackage
            }

            "WAIT" -> true // No postcondition to verify for waits

            else -> {
                Log.w(TAG, "No postcondition defined for action: ${proposal.action}")
                true
            }
        }
    }

    /**
     * Checks whether the task's declared completion criteria are satisfied on the given
     * fresh observation. Returns null if no criteria were provided (caller must request
     * user verification or report incomplete).
     *
     * @param request The task request containing optional completionCriteria.
     * @param freshObservation A freshly captured observation (not cached).
     * @return True if criteria pass, false if they fail, null if no criteria defined.
     */
    fun checkTaskCompletion(
        request: TaskRequest,
        freshObservation: ScreenObservation
    ): Boolean? {
        val criteria = request.completionCriteria ?: return null
        return try {
            criteria(freshObservation)
        } catch (e: Exception) {
            Log.e(TAG, "Task completion criteria threw exception", e)
            false
        }
    }

    /**
     * Determines whether the agent is making progress.
     * Detects repeated no-progress cycles by comparing recent observation fingerprints.
     *
     * @param observations Rolling window of recent observations (newest last).
     * @param windowSize How many consecutive identical observations count as stuck.
     * @return True if the agent appears stuck.
     */
    fun isStuck(
        observations: List<ScreenObservation>,
        windowSize: Int = 3
    ): Boolean {
        if (observations.size < windowSize) return false
        val recent = observations.takeLast(windowSize)
        val fingerprints = recent.map { obs ->
            "${obs.currentPackage}|${obs.nodeHierarchyText?.take(80)}|${obs.ocrText?.take(80)}"
        }
        val allSame = fingerprints.all { it == fingerprints.first() }
        if (allSame) {
            Log.w(TAG, "Agent appears stuck: $windowSize consecutive identical observations.")
        }
        return allSame
    }
}
