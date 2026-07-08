package com.papi.nova.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisSessionEventsTest {
    @Test
    fun terminalStreamEndedFinishesGameActivityAfterCurrentSessionEvent() {
        assertTrue(
            PolarisSessionEvents.shouldFinishGameActivity(
                "stream_ended",
                "idle",
                hasObservedCurrentSessionEvent = true
            )
        )
    }

    @Test
    fun streamResumeTimeoutFinishesGameActivityAfterCurrentSessionEvent() {
        assertTrue(
            PolarisSessionEvents.shouldFinishGameActivity(
                "stream_resume_timeout",
                "idle",
                hasObservedCurrentSessionEvent = true
            )
        )
    }

    @Test
    fun terminalEventBeforeCurrentSessionEventDoesNotFinishGameActivity() {
        assertFalse(
            "A terminal SSE event queued before the fresh launch starts belongs to the old paused session and must not tear down the new Game activity.",
            PolarisSessionEvents.shouldFinishGameActivity(
                "stream_ended",
                "idle",
                hasObservedCurrentSessionEvent = false
            )
        )
        assertFalse(
            PolarisSessionEvents.shouldFinishGameActivity(
                "stream_resume_timeout",
                "idle",
                hasObservedCurrentSessionEvent = false
            )
        )
    }

    @Test
    fun launchLifecycleEventsMarkCurrentSessionObserved() {
        assertTrue(PolarisSessionEvents.isCurrentSessionEvent("session_starting", "initializing"))
        assertTrue(PolarisSessionEvents.isCurrentSessionEvent("cage_starting", "cage_starting"))
        assertTrue(PolarisSessionEvents.isCurrentSessionEvent("game_launching", "game_launching"))
        assertTrue(PolarisSessionEvents.isCurrentSessionEvent("stream_active", "streaming"))
    }

    @Test
    fun activeStreamingEventsDoNotFinishGameActivity() {
        assertFalse(
            PolarisSessionEvents.shouldFinishGameActivity(
                "stream_active",
                "streaming",
                hasObservedCurrentSessionEvent = true
            )
        )
        assertFalse(
            PolarisSessionEvents.shouldFinishGameActivity(
                "cage_starting",
                "cage_starting",
                hasObservedCurrentSessionEvent = true
            )
        )
    }
}
