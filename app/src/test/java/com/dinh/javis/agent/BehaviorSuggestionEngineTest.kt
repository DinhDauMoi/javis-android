package com.dinh.javis.agent

import com.dinh.javis.data.BehaviorAggregate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for BehaviorSuggestionEngine (BA-10).
 * Validates:
 * - No suggestions when analytics disabled
 * - Minimum sample count enforced before suggesting
 * - High failure rate triggers suggestion
 * - Frequent successful tasks trigger custom command suggestion
 * - Suggestions include sample count and window info
 */
class BehaviorSuggestionEngineTest {

    private lateinit var engine: BehaviorSuggestionEngine

    @Before
    fun setup() {
        engine = BehaviorSuggestionEngine()
    }

    private fun aggregate(
        taskKey: String,
        runCount: Int,
        failureCount: Int,
        cancelledCount: Int = 0,
        blockedCount: Int = 0
    ) = BehaviorAggregate(
        taskKey = taskKey,
        date = "2026-09-27",
        runCount = runCount,
        failureCount = failureCount,
        cancelledCount = cancelledCount,
        blockedCount = blockedCount
    )

    @Test
    fun `no suggestions returned when analytics disabled`() {
        val agg = aggregate("media", runCount = 10, failureCount = 0)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = false)
        assertTrue("Must return empty when analytics disabled", suggestions.isEmpty())
    }

    @Test
    fun `no suggestions returned for empty aggregates`() {
        val suggestions = engine.generateSuggestions(emptyList(), analyticsEnabled = true)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `no suggestions when sample count below minimum`() {
        val agg = aggregate("search", runCount = 4, failureCount = 0)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = true)
        assertTrue("Must not suggest with only 4 samples (< 5)", suggestions.isEmpty())
    }

    @Test
    fun `custom command suggestion generated for frequent successful tasks`() {
        val agg = aggregate("media", runCount = 10, failureCount = 1)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = true)
        assertTrue("Should suggest custom command for frequent success", suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.actionLabelVi?.contains("lệnh") == true })
    }

    @Test
    fun `high failure rate triggers suggestion`() {
        val agg = aggregate("navigation", runCount = 10, failureCount = 6)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = true)
        assertTrue("High failure rate must trigger suggestion", suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.titleVi.contains("thất bại") || it.titleVi.contains("navigation") })
    }

    @Test
    fun `suggestions include sample count`() {
        val agg = aggregate("search", runCount = 8, failureCount = 1)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = true)
        for (s in suggestions) {
            assertTrue("Sample count must be included", s.sampleCount > 0)
        }
    }

    @Test
    fun `suggestions include time window label`() {
        val agg = aggregate("media", runCount = 8, failureCount = 0)
        val suggestions = engine.generateSuggestions(listOf(agg), analyticsEnabled = true)
        for (s in suggestions) {
            assertFalse("Window label must not be blank", s.windowLabel.isBlank())
        }
    }

    @Test
    fun `summary text is returned in Vietnamese`() {
        val agg = aggregate("general", runCount = 5, failureCount = 1)
        val summary = engine.buildAnalyticsSummaryVi(listOf(agg))
        assertFalse(summary.isBlank())
        assertTrue("Summary must be in Vietnamese", summary.contains("tác vụ"))
    }

    @Test
    fun `empty aggregates returns default message`() {
        val summary = engine.buildAnalyticsSummaryVi(emptyList())
        assertFalse(summary.isBlank())
    }
}
