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
        assertEquals(null, video.step)
        assertEquals(null, NovaSessionProgressUiState.fromSpace("streaming"))
        assertEquals(null, NovaSessionProgressUiState.fromSpace("cage_starting"))
        assertEquals("Waiting For Picture", NovaSessionProgressUiState.fromSpace("input_ready")!!.title)
    }
    @Test
    fun reconnectPageReadsItsWordsFromStringResources() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"nova_reconnect_title\">Reconnecting stream…</string>"))
        assertTrue(
            strings.contains(
                "<string name=\"nova_reconnect_subtitle\">Nova is checking with the host and will resume the stream if it can.</string>"
            )
        )
        assertTrue(strings.contains("<string name=\"nova_reconnect_attempt\">Attempt %1\$d of %2\$d</string>"))

        // Reconnecting is a Busy state page now, with Disconnect as its way out; its words are
        // still all resources.
        val source = File("src/main/java/com/papi/nova/ui/ReconnectOverlay.kt").readText()
        assertTrue(source.contains("getString(R.string.nova_reconnect_title)"))
        assertTrue(source.contains("getString(R.string.nova_reconnect_subtitle)"))
        assertTrue(source.contains("getString(R.string.nova_reconnect_attempt, attempt, maxAttempts)"))
        assertTrue(source.contains("getString(R.string.game_menu_disconnect)"))
        assertFalse("the page carries no English of its own", Regex("""(title|label|message) = """").containsMatchIn(source))
    }

    @Test
    fun progressStateMapsKnownStagesWithConfidenceCopy() {
        val state = NovaSessionProgressUiState.from("game_launching")

        assertEquals("Launching game...", state.title)
        assertEquals("Game launch requested", state.confidenceLabel)
        assertEquals("Nova is waiting for the host to expose the game window.", state.confidenceDetail)
        assertEquals(0.64f, state.progressFraction, 0.001f)
        assertEquals(NovaStartupStep.STARTING_GAME, state.step)
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
        assertEquals(NovaStartupStep.WAITING_FOR_VIDEO, state.step)
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
    fun onlyNovasOwnConnectionNamesTheHostInTheReadyTitle() {
        // connectionStarted's input_ready for a stream the player asked for (#386): the headline
        // names the host, and every other word and the progress stay the stage's own.
        val connected = NovaSessionProgressUiState.from("input_ready", localConnectionTitle = "Connected to Den")
        assertEquals("Connected to Den", connected.title)
        assertEquals(NovaSessionProgressUiState.from("input_ready").copy(title = "Connected to Den"), connected)
        // A reconnect or unnamed host supplies no local title, and keeps the stage's own words.
        assertEquals("Ready", NovaSessionProgressUiState.from("input_ready", "").title)
        assertEquals("Ready", NovaSessionProgressUiState.from("input_ready", localConnectionTitle = " ").title)
        // SSE's exact canonical token and its connected alias both stay ordinary host messages.
        assertEquals("Ready", NovaSessionProgressUiState.from("input_ready", "Connected to Den").title)
        assertEquals("Ready", NovaSessionProgressUiState.from("connected", "Connected to Den").title)
        assertEquals("Ready", NovaSessionProgressUiState.from(" INPUT_READY ", "arbitrary host words").title)
        assertEquals("Stream active...", NovaSessionProgressUiState.from("streaming", "Den").title)
        assertEquals("Stream active...", NovaSessionProgressUiState.from("streaming", localConnectionTitle = "Connected to Den").title)
        // A Space waits for its picture, whatever the stream says.
        assertEquals("Waiting For Picture", NovaSessionProgressUiState.fromSpace("input_ready")!!.title)
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
        // A stage the table does not know has no label of its own; the overlay draws
        // nova_stream_progress_update ("Startup update") for it.
        assertEquals(
            "",
            NovaSessionProgressUiState.from("waiting_for_host", "Waiting for host").stageLabel
        )
    }

    /** The steps only ever move forward through a start, in the table's own order (#422). */
    @Test
    fun everyKnownStageBelongsToAStepAndTheStepsFollowTheStages() {
        val inOrder = listOf(
            "initializing", "connecting", "rtsp", "control", "cage_starting", "game_launching", "video",
            "audio", "input", "unlocking_or_starting", "host_locked", "stream_active", "input_ready",
        )
        val stages = inOrder.map(NovaSessionProgressUiState::from)
        stages.forEach { assertTrue("${it.state} has no step", it.step != null) }
        assertEquals(inOrder, stages.map { it.state })
        val waiting = stages.first { it.state == "video" }.step!!
        stages.filter { it.state in setOf("initializing", "connecting", "rtsp", "control") }
            .forEach { assertEquals(NovaStartupStep.CONTACTING_HOST, it.step) }
        stages.filter { it.state in setOf("cage_starting", "game_launching", "unlocking_or_starting", "host_locked") }
            .forEach { assertEquals(NovaStartupStep.STARTING_GAME, it.step) }
        stages.filter { it.state in setOf("video", "audio", "input", "stream_active") }
            .forEach { assertEquals(waiting, it.step) }
        assertEquals(NovaStartupStep.VIDEO_READY, stages.last().step)
    }

    @Test
    fun aStateWithNoWordsHasNoneOfItsOwnAndAStageOrMessageDoes() {
        assertFalse(NovaSessionProgressUiState.from("paused", "").hasOwnWords)
        assertTrue(NovaSessionProgressUiState.from("paused", "Paused for now").hasOwnWords)
        assertTrue(NovaSessionProgressUiState.from("rtsp", "").hasOwnWords)
    }

    @Test
    fun progressStateDoesNotExposeRawIdleState() {
        val state = NovaSessionProgressUiState.from("idle")

        assertEquals("Preparing session...", state.title)
        assertEquals("Session preflight", state.confidenceLabel)
        assertEquals("Checking host readiness before opening the stream.", state.confidenceDetail)
        assertEquals(0.12f, state.progressFraction, 0.001f)
        assertEquals(NovaStartupStep.CONTACTING_HOST, state.step)
    }

    @Test
    fun progressStateUsesMessageForUnknownStage() {
        val state = NovaSessionProgressUiState.from("waiting_for_host", "Waiting for host")

        assertEquals("Waiting for host", state.title)
        // Filled from nova_stream_progress_working where the overlay draws it.
        assertEquals("", state.confidenceLabel)
        assertEquals("Waiting for host", state.confidenceDetail)
        assertEquals(0.5f, state.progressFraction, 0.001f)
        assertEquals(null, state.step)
    }

    @Test
    fun progressStateMapsUnlockingOrStartingRetryToVerboseHostReadinessCopy() {
        val state = NovaSessionProgressUiState.from("unlocking_or_starting", "The host is starting or unlocking, please wait…")

        assertEquals("unlocking_or_starting", state.state)
        assertEquals("Waiting on host...", state.title)
        assertEquals("Host readiness", state.stageLabel)
        assertEquals("Host starting or unlocking", state.confidenceLabel)
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
        // Never the raw token: the state leaves the headline empty and the overlay draws its
        // fallback words from resources.
        assertEquals("", state.title)
        assertEquals("", state.stageLabel)
        val overlay = File("src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt").readText()
        assertTrue(overlay.contains("state.title.ifEmpty { stringResource(R.string.nova_stream_progress_working) }"))
        assertTrue(overlay.contains("state.stageLabel.ifEmpty { stringResource(R.string.nova_stream_progress_update) }"))
        assertTrue(overlay.contains("state.confidenceDetail.ifEmpty { stringResource(R.string.nova_stream_progress_waiting) }"))
        assertFalse("the progress state carries no English fallback of its own", overlay.contains("\"Working on it\""))
    }

}
