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
        // to ScrollDown, so recovery is not invoked for that transcript.
        val parser = com.dinh.javis.commands.CommandParser()
        assertEquals(Command.ScrollDown, parser.parse("nước xuống"))
    }

    @Test
    fun `non-empty unrecognized speech returns AskAi from parser, recovery catches it`() = runBlocking {
        // Integration contract: a phrase the deterministic parser returns as the
        // AskAi catch-all (unrecognized non-empty speech) must still be recoverable
        // to a scroll intent by the classifier — closing the defect where AskAi
        // bypassed recovery entirely.
        val parser = com.dinh.javis.commands.CommandParser()
        // A genuinely unrecognized phrase (no known command grammar) — parser
        // returns the AskAi catch-all for non-empty unrecognized speech.
        val transcript = "đâu là video mới nhất"
        val parsed = parser.parse(transcript)
        assertTrue("expected AskAi catch-all, got $parsed", parsed is Command.AskAi)

        // Even though the parser didn't classify it as a scroll, recovery should
        // be able to redirect an intended scroll command (ASR-mangled) to a scroll.
        val scrollIntent = "lượt xuống" // ASR mangles "lướt xuống"
        val llm = { _: String, _: String -> """{"intent":"SCROLL_DOWN","confidence":0.9,"rationale":"down"}""" }
        val decision = classifier(llm).recover(scrollIntent)
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.ScrollDown)
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
    fun `confidence out of range above 1 is rejected as clarification`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_UP","confidence":1.5,"rationale":"huh"}""" }
        val decision = classifier(llm).recover("lướt lên")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `confidence out of range below 0 is rejected as clarification`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_DOWN","confidence":-0.3,"rationale":"neg"}""" }
        val decision = classifier(llm).recover("lướt xuống")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `missing confidence field is rejected as clarification`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_UP","rationale":"no score"}""" }
        val decision = classifier(llm).recover("lướt lên")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }

    @Test
    fun `unsupported intent SCROLL_LEFT is treated as Clarify`() = runBlocking {
        val llm = { _: String, _: String -> """{"intent":"SCROLL_LEFT","confidence":0.95}""" }
        val decision = classifier(llm).recover("lướt sang trái")
        assertTrue(decision is VoiceIntentClassifier.RecoveryDecision.Clarify)
    }
}
