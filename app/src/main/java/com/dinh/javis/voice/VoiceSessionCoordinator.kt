package com.dinh.javis.voice

/**
 * Coordinates voice session lifecycle, generational session IDs, manual mic tap debouncing,
 * and toggle-to-cancel behavior (R13).
 *
 * Ensures rapid taps and screen transitions (fold/unfold, rotation) on devices like OPPO
 * do not cause concurrent SpeechRecognizer leaks or obsolete callback processing.
 */
class VoiceSessionCoordinator(
    private val debounceIntervalMs: Long = 350L
) {
    /**
     * Monotonically increasing session generation ID.
     * Callbacks tagged with an older ID are dropped immediately.
     */
    @Volatile
    var currentSessionId: Long = 0L
        private set

    private var lastManualTriggerTime: Long = 0L

    /**
     * Action decision resulting from a manual mic trigger.
     */
    sealed class TriggerDecision {
        /** Tap happened too quickly after previous tap; ignored to prevent rapid multi-clicks. */
        object Debounced : TriggerDecision()

        /** Mic tapped while already listening or queued; cancels active session. */
        data class CancelActive(val obsoleteSessionId: Long) : TriggerDecision()

        /** Starts a new clean voice session. */
        data class StartNew(val newSessionId: Long) : TriggerDecision()
    }

    /**
     * Evaluates a manual microphone trigger.
     *
     * @param isListeningOrStarting True if voice listening is currently active or starting.
     * @param currentTime Current system time in milliseconds.
     */
    @Synchronized
    fun onManualTrigger(
        isListeningOrStarting: Boolean,
        currentTime: Long = System.currentTimeMillis()
    ): TriggerDecision {
        if (currentTime - lastManualTriggerTime < debounceIntervalMs) {
            return TriggerDecision.Debounced
        }
        lastManualTriggerTime = currentTime

        if (isListeningOrStarting) {
            val obsoleteSessionId = ++currentSessionId
            return TriggerDecision.CancelActive(obsoleteSessionId)
        }

        val newSessionId = ++currentSessionId
        return TriggerDecision.StartNew(newSessionId)
    }

    /**
     * Starts a new session explicitly (e.g. from wake word detection or retry).
     */
    @Synchronized
    fun nextSession(): Long {
        return ++currentSessionId
    }

    /**
     * Invalidates the active session without starting a new one (e.g. on teardown or cancellation).
     */
    @Synchronized
    fun invalidateSession(): Long {
        return ++currentSessionId
    }

    /**
     * Checks if a callback's session ID matches the currently active session.
     */
    fun isSessionValid(sessionId: Long): Boolean {
        return sessionId == currentSessionId
    }

    /**
     * Resets the coordinator state (for testing or full reset).
     */
    @Synchronized
    fun reset() {
        currentSessionId = 0L
        lastManualTriggerTime = 0L
    }
}
