package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaStreamOverlayUiStateTest {
    @Test
    fun reconnectStateFormatsAttemptText() {
        val state = NovaReconnectOverlayState(attempt = 2, maxAttempts = 5)

        assertEquals("Reconnecting...", state.title)
        assertEquals("Stream will resume automatically", state.subtitle)
        assertEquals("Attempt 2 of 5", state.attemptLabel)
    }

    @Test
    fun progressStateMapsKnownStages() {
        val state = NovaSessionProgressUiState.from("game_launching")

        assertEquals("Launching game...", state.title)
        assertTrue(state.completedStages.contains("Preparing session..."))
        assertTrue(state.completedStages.contains("Starting compositor..."))
    }

    @Test
    fun progressStateDoesNotExposeRawIdleState() {
        val state = NovaSessionProgressUiState.from("idle")

        assertEquals("Preparing session...", state.title)
        assertTrue(state.completedStages.isEmpty())
    }

    @Test
    fun progressStateUsesMessageForUnknownStage() {
        val state = NovaSessionProgressUiState.from("waiting_for_host", "Waiting for host")

        assertEquals("Waiting for host", state.title)
        assertTrue(state.completedStages.isEmpty())
    }
}
