package com.papi.nova.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolarisSessionEventsGameSourceGuardTest {
    @Test
    fun gameIgnoresTerminalSseEventsUntilCurrentSessionEventArrives() {
        val source = File("src/main/java/com/papi/nova/Game.kt").readText()
        val startEventSource = functionBody(source, "private fun startNovaEventSourceIfSupported()")

        assertTrue(
            "Game should track whether this SSE connection has seen a current launch/session event before accepting terminal events.",
            source.contains("polarisSseSawCurrentSessionEvent")
        )
        assertTrue(
            "Game should mark current launch/session events through PolarisSessionEvents instead of ad-hoc string checks.",
            startEventSource.contains("PolarisSessionEvents.isCurrentSessionEvent(event, state)")
        )
        assertTrue(
            "Game should gate terminal events through PolarisSessionEvents with the observed-current-session flag.",
            startEventSource.contains(
                "PolarisSessionEvents.shouldFinishGameActivity(event, state, polarisSseSawCurrentSessionEvent)"
            )
        )
        assertFalse(
            "Raw stream_ended checks in the SSE callback can tear down a fresh Auto Safe launch with an old paused-session terminal event.",
            startEventSource.contains("event == \"stream_ended\" || (state == \"idle\"")
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        assertTrue("Missing function signature: $signature", signatureIndex >= 0)
        val openBrace = source.indexOf('{', signatureIndex)
        assertTrue("Missing function body for: $signature", openBrace >= 0)
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBrace, index + 1)
                    }
                }
            }
        }
        error("Unterminated function body for: $signature")
    }
}
