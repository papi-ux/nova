package com.papi.nova.api

object PolarisSessionEvents {
    private val terminalEvents = setOf(
        "stream_ended",
        "session_ended",
        "stream_resume_timeout",
    )

    private val currentSessionEvents = setOf(
        "session_starting",
        "cage_starting",
        "game_launching",
        "stream_active",
    )

    private val currentSessionStates = setOf(
        "initializing",
        "cage_starting",
        "game_launching",
        "streaming",
    )

    @JvmStatic
    fun isCurrentSessionEvent(event: String?, state: String?): Boolean {
        val normalizedEvent = event.normalizedSessionToken()
        val normalizedState = state.normalizedSessionToken()
        return currentSessionEvents.contains(normalizedEvent) || currentSessionStates.contains(normalizedState)
    }

    @JvmStatic
    @JvmOverloads
    fun shouldFinishGameActivity(
        event: String?,
        state: String?,
        hasObservedCurrentSessionEvent: Boolean = true
    ): Boolean {
        val normalizedEvent = event.normalizedSessionToken()
        val normalizedState = state.normalizedSessionToken()
        if (!hasObservedCurrentSessionEvent) {
            return false
        }
        if (terminalEvents.contains(normalizedEvent)) {
            return true
        }
        return normalizedState == "idle" && normalizedEvent.isBlank()
    }

    private fun String?.normalizedSessionToken(): String = this?.trim()?.lowercase().orEmpty()
}
