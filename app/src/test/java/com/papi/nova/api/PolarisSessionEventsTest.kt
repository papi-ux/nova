package com.papi.nova.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisSessionEventsTest {
    @Test
    fun terminalStreamEndedFinishesGameActivity() {
        assertTrue(PolarisSessionEvents.shouldFinishGameActivity("stream_ended", "idle"))
    }

    @Test
    fun streamResumeTimeoutFinishesGameActivity() {
        assertTrue(PolarisSessionEvents.shouldFinishGameActivity("stream_resume_timeout", "idle"))
    }

    @Test
    fun activeStreamingEventsDoNotFinishGameActivity() {
        assertFalse(PolarisSessionEvents.shouldFinishGameActivity("stream_active", "streaming"))
        assertFalse(PolarisSessionEvents.shouldFinishGameActivity("cage_starting", "cage_starting"))
    }
}
