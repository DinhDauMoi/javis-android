package com.dinh.javis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Unit tests for [VoiceSessionCoordinator].
 * Verifies R13 requirements:
 * - Debouncing rapid manual mic taps (within 350ms).
 * - Toggle cancellation when mic is pressed while listening or starting.
 * - Session ID generation and stale callback rejection.
 * - Teardown / error invalidation.
 */
class VoiceSessionCoordinatorTest {

    private lateinit var coordinator: VoiceSessionCoordinator

    @Before
    fun setUp() {
        coordinator = VoiceSessionCoordinator(debounceIntervalMs = 350L)
    }

    @Test
    fun manualTrigger_whenIdle_startsNewSession() {
        val decision = coordinator.onManualTrigger(isListeningOrStarting = false, currentTime = 1000L)

        assertTrue(decision is VoiceSessionCoordinator.TriggerDecision.StartNew)
        val newSessionId = (decision as VoiceSessionCoordinator.TriggerDecision.StartNew).newSessionId
        assertEquals(1L, newSessionId)
        assertTrue(coordinator.isSessionValid(newSessionId))
    }

    @Test
    fun manualTrigger_whenRapidTap_debounces() {
        val firstDecision = coordinator.onManualTrigger(isListeningOrStarting = false, currentTime = 1000L)
        assertTrue(firstDecision is VoiceSessionCoordinator.TriggerDecision.StartNew)

        // Rapid tap at 1150ms (delta = 150ms < 350ms debounce threshold)
        val secondDecision = coordinator.onManualTrigger(isListeningOrStarting = true, currentTime = 1150L)
        assertEquals(VoiceSessionCoordinator.TriggerDecision.Debounced, secondDecision)

        // Session ID should remain 1L and valid
        assertEquals(1L, coordinator.currentSessionId)
        assertTrue(coordinator.isSessionValid(1L))
    }

    @Test
    fun manualTrigger_afterDebounceWindow_startsNewSessionIfIdle() {
        coordinator.onManualTrigger(isListeningOrStarting = false, currentTime = 1000L)

        // Tap after 400ms (> 350ms)
        val secondDecision = coordinator.onManualTrigger(isListeningOrStarting = false, currentTime = 1400L)
        assertTrue(secondDecision is VoiceSessionCoordinator.TriggerDecision.StartNew)
        val secondSessionId = (secondDecision as VoiceSessionCoordinator.TriggerDecision.StartNew).newSessionId
        assertEquals(2L, secondSessionId)
        assertFalse(coordinator.isSessionValid(1L))
        assertTrue(coordinator.isSessionValid(2L))
    }

    @Test
    fun manualTrigger_whenAlreadyListening_cancelsActiveSession() {
        // First tap starts listening
        val firstDecision = coordinator.onManualTrigger(isListeningOrStarting = false, currentTime = 1000L)
        val activeSessionId = (firstDecision as VoiceSessionCoordinator.TriggerDecision.StartNew).newSessionId
        assertTrue(coordinator.isSessionValid(activeSessionId))

        // Second tap while listening (after debounce window, e.g. at 1500ms)
        val cancelDecision = coordinator.onManualTrigger(isListeningOrStarting = true, currentTime = 1500L)
        assertTrue(cancelDecision is VoiceSessionCoordinator.TriggerDecision.CancelActive)

        // The previous session ID must now be obsolete/invalid
        assertFalse(coordinator.isSessionValid(activeSessionId))
        // And current session id is incremented to block any late callbacks
        assertEquals(2L, coordinator.currentSessionId)
    }

    @Test
    fun nextSession_invalidatesPreviousSession() {
        val s1 = coordinator.nextSession()
        assertEquals(1L, s1)
        assertTrue(coordinator.isSessionValid(s1))

        val s2 = coordinator.nextSession()
        assertEquals(2L, s2)
        assertFalse(coordinator.isSessionValid(s1))
        assertTrue(coordinator.isSessionValid(s2))
    }

    @Test
    fun invalidateSession_makesCurrentSessionInvalid() {
        val s1 = coordinator.nextSession()
        assertTrue(coordinator.isSessionValid(s1))

        val invalidatedId = coordinator.invalidateSession()
        assertEquals(2L, invalidatedId)
        assertFalse(coordinator.isSessionValid(s1))
    }

    @Test
    fun threadSafety_concurrentNextSession_producesUniqueMonotonicIds() {
        val threadCount = 10
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val generatedIds = ConcurrentHashMap.newKeySet<Long>()

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        val id = coordinator.nextSession()
                        generatedIds.add(id)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(threadCount * iterationsPerThread, generatedIds.size)
        assertEquals((threadCount * iterationsPerThread).toLong(), coordinator.currentSessionId)
    }

    @Test
    fun staleCallback_fromObsoleteSession_isRejected() {
        val oldSessionId = coordinator.nextSession()
        // User cancels or starts a new session
        val newSessionId = coordinator.nextSession()

        // Simulating recognizer callback arriving late with oldSessionId
        val isOldCallbackAccepted = coordinator.isSessionValid(oldSessionId)
        val isNewCallbackAccepted = coordinator.isSessionValid(newSessionId)

        assertFalse("Stale callback from old session must be rejected (R13)", isOldCallbackAccepted)
        assertTrue("Callback from active session must be accepted", isNewCallbackAccepted)
    }
}
