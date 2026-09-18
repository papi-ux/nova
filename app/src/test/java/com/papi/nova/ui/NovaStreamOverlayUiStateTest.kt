package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaStreamOverlayUiStateTest {

    @Test fun spaceProgressDoesNotClaimUnobservedDesktopOrSteamReadiness() {
        val starting = NovaSessionProgressUiState.fromSpace("space_starting")!!
        val video = NovaSessionProgressUiState.fromSpace("video stream establishment")!!
        assertEquals("Starting Space", starting.title)
        assertEquals("Connecting Video", video.title)
        assertTrue(starting.indeterminate)
        assertTrue(video.completedStages.isEmpty())
        assertEquals(null, NovaSessionProgressUiState.fromSpace("streaming"))
        assertEquals(null, NovaSessionProgressUiState.fromSpace("cage_starting"))
        assertEquals("Waiting For Picture", NovaSessionProgressUiState.fromSpace("input_ready")!!.title)
    }
    @Test
    fun reconnectOverlayReadsItsWordsFromStringResources() {
        val state = NovaReconnectOverlayState(attempt = 2, maxAttempts = 5)
        assertEquals(2, state.attempt)
        assertEquals(5, state.maxAttempts)

        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"nova_reconnect_title\">Reconnecting stream…</string>"))
        assertTrue(
            strings.contains(
                "<string name=\"nova_reconnect_subtitle\">Nova is checking with the host and will resume the stream if it can.</string>"
            )
        )
        assertTrue(strings.contains("<string name=\"nova_reconnect_attempt\">Attempt %1\$d of %2\$d</string>"))

        val source = File("src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt").readText()
        val overlay = source.substringAfter("fun NovaReconnectOverlayContent(").substringBefore("fun NovaSessionProgressOverlayContent(")
        assertTrue(overlay.contains("stringResource(R.string.nova_reconnect_title)"))
        assertTrue(overlay.contains("stringResource(R.string.nova_reconnect_subtitle)"))
        assertTrue(overlay.contains("stringResource(R.string.nova_reconnect_attempt, state.attempt, state.maxAttempts)"))
        assertFalse("the overlay carries no English of its own", overlay.contains("text = \""))
    }

    @Test
    fun progressStateMapsKnownStagesWithConfidenceCopy() {
        val state = NovaSessionProgressUiState.from("game_launching")

        assertEquals("Launching game...", state.title)
        assertEquals("Game launch requested", state.confidenceLabel)
        assertEquals("Nova is waiting for the host to expose the game window.", state.confidenceDetail)
        assertEquals(0.64f, state.progressFraction, 0.001f)
        assertTrue(state.completedStages.contains("Preparing session..."))
        assertTrue(state.completedStages.contains("Starting compositor..."))
    }

    @Test
    fun progressStateUsesExplicitMoonlightHandshakeStages() {
        val state = NovaSessionProgressUiState.from(" Video ")
        val audio = NovaSessionProgressUiState.from("audio")
        val input = NovaSessionProgressUiState.from("input")

        assertEquals("video", state.state)
        assertEquals("Decoder handshake", state.confidenceLabel)
        assertEquals("Nova is initializing video decoding for the stream.", state.confidenceDetail)
        assertEquals(0.78f, state.progressFraction, 0.001f)
        assertTrue(state.completedStages.contains("Launching game..."))
        assertEquals("Starting audio stream...", audio.title)
        assertEquals("Audio handshake", audio.confidenceLabel)
        assertEquals(0.88f, audio.progressFraction, 0.001f)
        assertEquals("Enabling input...", input.title)
        assertEquals("Input handshake", input.confidenceLabel)
        assertEquals(0.94f, input.progressFraction, 0.001f)
    }

    @Test
    fun progressStateDoesNotAliasControlOrRtspToOtherStages() {
        val control = NovaSessionProgressUiState.from("control")
        val rtsp = NovaSessionProgressUiState.from("rtsp")

        assertEquals("control", control.state)
        assertEquals("Connecting controls...", control.title)
        assertEquals("Control channel", control.confidenceLabel)
        assertEquals("rtsp", rtsp.state)
        assertEquals("Opening stream session...", rtsp.title)
        assertEquals("RTSP handshake", rtsp.confidenceLabel)
    }

    @Test
    fun progressStateMapsRawMoonlightLifecycleStageNames() {
        assertEquals("connecting", NovaSessionProgressUiState.from("audio stream initialization").state)
        assertEquals("rtsp", NovaSessionProgressUiState.from("RTSP handshake").state)
        assertEquals("control", NovaSessionProgressUiState.from("control stream establishment").state)
        assertEquals("video", NovaSessionProgressUiState.from("video stream establishment").state)
        assertEquals("audio", NovaSessionProgressUiState.from("audio stream establishment").state)
        assertEquals("input", NovaSessionProgressUiState.from("input stream establishment").state)
    }

    @Test
    fun progressStateDistinguishesStreamActiveFromInputReady() {
        val streamActive = NovaSessionProgressUiState.from("streaming")
        val inputReady = NovaSessionProgressUiState.from("input_ready")

        assertEquals("stream_active", streamActive.state)
        assertEquals("Stream active", streamActive.stageLabel)
        assertEquals("Waiting for first frame", streamActive.confidenceLabel)
        assertEquals(
            "The host reports streaming; Nova is waiting for the first painted frame before clearing the overlay.",
            streamActive.confidenceDetail
        )
        assertEquals(0.97f, streamActive.progressFraction, 0.001f)

        assertEquals("input_ready", inputReady.state)
        assertEquals("Input ready", inputReady.stageLabel)
        assertEquals("Input ready", inputReady.confidenceLabel)
        assertEquals("Controller, audio, and video channels are established.", inputReady.confidenceDetail)
        assertEquals(1f, inputReady.progressFraction, 0.001f)
    }

    @Test
    fun progressStateExposesExplicitStageLabelsForStartupPhases() {
        val expectedLabels = mapOf(
            "idle" to "Preflight check",
            "rtsp" to "RTSP session",
            "control" to "Control path",
            "video" to "Video pipeline",
            "audio" to "Audio pipeline",
            "input" to "Input path"
        )

        expectedLabels.forEach { (stage, expectedLabel) ->
            assertEquals(expectedLabel, NovaSessionProgressUiState.from(stage).stageLabel)
        }
        assertEquals(
            "Startup update",
            NovaSessionProgressUiState.from("waiting_for_host", "Waiting for host").stageLabel
        )
    }

    @Test
    fun progressStateDoesNotExposeRawIdleState() {
        val state = NovaSessionProgressUiState.from("idle")

        assertEquals("Preparing session...", state.title)
        assertEquals("Session preflight", state.confidenceLabel)
        assertEquals("Checking host readiness before opening the stream.", state.confidenceDetail)
        assertEquals(0.12f, state.progressFraction, 0.001f)
        assertTrue(state.completedStages.isEmpty())
    }

    @Test
    fun progressStateUsesMessageForUnknownStage() {
        val state = NovaSessionProgressUiState.from("waiting_for_host", "Waiting for host")

        assertEquals("Waiting for host", state.title)
        assertEquals("Working on it", state.confidenceLabel)
        assertEquals("Waiting for host", state.confidenceDetail)
        assertEquals(0.5f, state.progressFraction, 0.001f)
        assertTrue(state.completedStages.isEmpty())
    }

    @Test
    fun progressStateMapsUnlockingOrStartingRetryToVerboseHostReadinessCopy() {
        val state = NovaSessionProgressUiState.from("unlocking_or_starting", "Server is starting or computer is unlocking, please wait…")

        assertEquals("unlocking_or_starting", state.state)
        assertEquals("Waiting on host...", state.title)
        assertEquals("Host readiness", state.stageLabel)
        assertEquals("Server starting or unlocking", state.confidenceLabel)
        assertEquals("The host is starting the app or unlocking before video can continue.", state.confidenceDetail)
        assertEquals(0.96f, state.progressFraction, 0.001f)
    }

    @Test
    fun progressStateMapsLockedHostToNovaUnlockPrompt() {
        val state = NovaSessionProgressUiState.from("host_locked")

        assertEquals("host_locked", state.state)
        assertEquals("Host locked", state.title)
        assertEquals("Unlock host", state.stageLabel)
        assertEquals("Unlock host to continue", state.confidenceLabel)
        assertEquals("Nova is connected; unlock the host to continue into the stream.", state.confidenceDetail)
        assertEquals(0.96f, state.progressFraction, 0.001f)
    }

    @Test
    fun progressStateNeverExposesRawStateTokenAsTitleForUnknownStageWithoutMessage() {
        val state = NovaSessionProgressUiState.from("sess_negotiate_v2")

        assertEquals("sess_negotiate_v2", state.state)
        assertEquals("Working on it", state.title)
        assertEquals("Startup update", state.stageLabel)
    }

}
