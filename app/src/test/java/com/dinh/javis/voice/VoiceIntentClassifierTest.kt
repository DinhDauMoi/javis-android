package com.dinh.javis.voice

import com.dinh.javis.commands.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AI intent recovery path (plan-review remediations 4.7-4.8).
 *
 * Covers:
 * - Deterministic ASR-variant recovery (e.g. "nước xuống" -> SCROLL_DOWN)
 * - High-confidence AI mapping to allowed intents (SCROLL_UP / SCROLL_DOWN)
 * - Low-confidence clarification
 * - Unsupported intent returns clarification
 * - Malformed / non-JSON LLM output falls back to clarification
 * - LLM throwing (network failure) falls back to clarification without crash
 * - Null LLM (no API configured) returns clarification
 *
 * These tests exercise the classifier with an injected fake LLM so no real
 * network or Context is required.
 */
class VoiceIntentClassifierTest {

    private fun classifier(llm: suspend (String, String) -> String) = VoiceIntentClassifier(llmCall = llm)
    private fun noAiClientClassifier() = VoiceIntentClassifier(llmCall = null)

    @Test
    fun `ASR variant nuoc xuong is handled deterministically by parser`() = runBlocking {
        // The deterministic parser maps "nước xuống" (normalized "nuoc xuong")
        // to ScrollDown, so recovery must NOT be invoked for that transcript.
        val parser = com.dinh.javis.commands.CommandParser()
        assertEquals(Command.ScrollDown, parser.parse("nước xuống"))
    }

    @Test
    fun `high confidence AI scroll up maps to ScrollUp decision`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_UP","confidence":0.95,"rationale":"clear next-video intent"}""" }
        val decision = classifier(llm).recover("lướt lên giúp")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.ScrollUp)
        assertEquals(
            "clear next-video intent",
            (decision as VoiceIntentClassifier.RecoveryDecision.ScrollUp).rationale
        )
    }

    @Test
    fun `high confidence AI scroll down maps to ScrollDown decision`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_DOWN","confidence":0.9,"rationale":"previous content"}""" }
        val decision = classifier(llm).recover("lướt xuống tí")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.ScrollDown)
    }

    @Test
    fun `low confidence AI returns clarification, not a scroll`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_UP","confidence":0.4,"rationale":"unsure"}""" }
        val decision = classifier(llm).recover("cái gì đó")
        assertTrue(
            "Expected Clarify for low confidence, was $decision",
            decision is VoiceIntentClassifier.RecoveryDecision.Clarify
        )
    }

    @Test
    fun `unknown intent returns clarification`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"UNKNOWN","confidence":0.9,"rationale":"muddled"}""" }
        val decision = classifier(llm).recover("tăng nhạc lên")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `malformed LLM output returns clarification without crash`() = runBlocking {
        val llm = { _: String, _: String -> "...." }
        val decision = classifier(llm).recover("lướt")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `non-JSON LLM output returns clarification`() = runBlocking {
        val llm = { _: String, _: String -> "Tôi không hiểu." }
        val decision = classifier(llm).recover("âm thanh lên")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `LLM throwing falls back to clarification, no crash`() = runBlocking {
        val llm: suspend (String, String) -> String = { _, _ -> throw RuntimeException("network down") }
        val decision = classifier(llm).recover("lướt xuống")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `null LLM (no API configured) returns clarification`() = runBlocking {
        val decision = noAiClientClassifier().recover("lướt lên")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `empty transcript returns clarification`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_UP","confidence":0.95}""" }
        val decision = classifier(llm).recover("   ")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `unsupported intent SCROLL_LEFT is treated as Clarify`() = runBlocking {
        val llm = { _: String -> """{"intent":"SCROLL_LEFT","confidence":0.95}""" }
        val decision = classifier(llm).recover("lướt sang trái")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }
}
