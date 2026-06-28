package com.papi.nova.api

object PolarisSessionEvents {
    private val terminalEvents = setOf(
        "stream_ended",
        "session_ended",
        "stream_resume_timeout",
    )

    @JvmStatic
    fun shouldFinishGameActivity(event: String?, state: String?): Boolean {
        val normalizedEvent = event?.trim()?.lowercase().orEmpty()
        val normalizedState = state?.trim()?.lowercase().orEmpty()
        if (terminalEvents.contains(normalizedEvent)) {
            return true
        }
        return normalizedState == "idle" && normalizedEvent == "stream_ended"
    }
}
