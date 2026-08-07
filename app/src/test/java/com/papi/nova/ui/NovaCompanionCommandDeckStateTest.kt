package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaCompanionCommandDeckStateTest {
    @Test
    fun actionOrderIsDeterministicAndEndSessionIsNeverInitialFocus() {
        val state = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(NovaHudMode.DEBUG),
            sessionState = "streaming",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
        )

        assertEquals(
            listOf(
                NovaCompanionCommandActionId.ANDROID_KEYBOARD,
                NovaCompanionCommandActionId.NOVA_KEYBOARD,
                NovaCompanionCommandActionId.QUICK_KEYS,
                NovaCompanionCommandActionId.NOVA_HUD,
                NovaCompanionCommandActionId.ZOOM_PAN,
                NovaCompanionCommandActionId.COMMAND_CENTER,
                NovaCompanionCommandActionId.HIDE_COMPANION,
                NovaCompanionCommandActionId.DISCONNECT,
                NovaCompanionCommandActionId.END_SESSION,
            ),
            state.actions.map { it.id },
        )
        assertEquals(NovaCompanionCommandActionId.ANDROID_KEYBOARD, state.initialFocusActionId())
        assertNotEquals(NovaCompanionCommandActionId.END_SESSION, state.initialFocusActionId())
        assertFalse(state.actions.first { it.id == NovaCompanionCommandActionId.HIDE_COMPANION }.destructive)
        assertFalse(state.actions.first { it.id == NovaCompanionCommandActionId.DISCONNECT }.destructive)
        assertTrue(state.actions.first { it.id == NovaCompanionCommandActionId.END_SESSION }.destructive)
    }

    @Test
    fun hideActionAvailabilityTracksTheExplicitReopenAuthority() {
        val disabled = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(),
            sessionState = "streaming",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
            hideCompanionEnabled = false,
        )
        val enabled = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(),
            sessionState = "streaming",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
            hideCompanionEnabled = true,
        )

        assertFalse(disabled.actions.single { it.id == NovaCompanionCommandActionId.HIDE_COMPANION }.enabled)
        assertTrue(enabled.actions.single { it.id == NovaCompanionCommandActionId.HIDE_COMPANION }.enabled)
        assertTrue(disabled.actions.single { it.id == NovaCompanionCommandActionId.DISCONNECT }.enabled)
    }

    @Test
    fun unavailableTelemetryIsLabeledTruthfully() {
        val state = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(NovaHudMode.DEBUG),
            sessionState = "",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
        )

        assertEquals("--", state.actualFps)
        assertEquals("--", state.targetFps)
        assertEquals("--ms", state.latency)
        assertEquals("--", state.bitrate)
        assertEquals("--", state.codec)
        assertEquals("--", state.resolution)
        assertEquals("Unavailable", state.profile)
        assertEquals("Unavailable", state.session)
        assertEquals("Companion", state.displayRole)
    }

    @Test
    fun hudProjectionPreservesAuthoritativeRuntimeLabels() {
        val hud = NovaHudUiState.from(
            mode = NovaHudMode.DEBUG,
            fps = 89.6,
            targetFps = 90.0,
            latencyMs = 18,
            codec = "hevc_nvenc",
            bitrateKbps = 30000,
            width = 1920,
            height = 1080,
            status = null,
            sparklineSamples = emptyList(),
        ).copy(autopilotLabel = "Auto Quality")

        val state = NovaCompanionCommandDeckState.from(hud, "streaming", "Companion", "Unavailable")

        assertEquals("90", state.actualFps)
        assertEquals("TGT 90", state.targetFps)
        assertEquals("18ms", state.latency)
        assertEquals("30 Mbps", state.bitrate)
        assertEquals("HEVC", state.codec)
        assertEquals("1920×1080", state.resolution)
        assertEquals("Auto Quality", state.profile)
        assertEquals("Streaming", state.session)
    }

    @Test
    fun activeActionStateIsExplicitAndDoesNotChangeSafeInitialFocus() {
        val state = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(),
            sessionState = "streaming",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
        ).withActionSelections(
            androidKeyboardVisible = true,
            novaKeyboardVisible = false,
            novaHudVisible = true,
            zoomPanEnabled = true,
        )

        assertTrue(state.actions.single { it.id == NovaCompanionCommandActionId.ANDROID_KEYBOARD }.selected)
        assertFalse(state.actions.single { it.id == NovaCompanionCommandActionId.NOVA_KEYBOARD }.selected)
        assertTrue(state.actions.single { it.id == NovaCompanionCommandActionId.NOVA_HUD }.selected)
        assertTrue(state.actions.single { it.id == NovaCompanionCommandActionId.ZOOM_PAN }.selected)
        assertEquals(NovaCompanionCommandActionId.ANDROID_KEYBOARD, state.initialFocusActionId())
    }

    @Test
    fun dimAndTouchpadActivityRemainExplicitUiState() {
        val base = NovaCompanionCommandDeckState.from(
            hud = NovaHudUiState.empty(),
            sessionState = "streaming",
            displayRole = "Companion",
            unavailableLabel = "Unavailable",
        )

        assertFalse(base.dimmed)
        assertFalse(base.touchpadActive)
        assertTrue(base.copy(dimmed = true).dimmed)
        assertTrue(base.copy(touchpadActive = true).touchpadActive)
    }
}
