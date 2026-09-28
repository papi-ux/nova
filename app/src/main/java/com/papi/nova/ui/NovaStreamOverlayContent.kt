package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaPanelType
import java.util.Locale

data class NovaSessionProgressUiState(
    val state: String,
    val title: String,
    val stageLabel: String,
    val completedStages: List<String>,
    val confidenceLabel: String,
    val confidenceDetail: String,
    val progressFraction: Float,
    val indeterminate: Boolean = false,
) {
    companion object {
        private data class StageCopy(
            val state: String,
            val title: String,
            val stageLabel: String,
            val confidenceLabel: String,
            val confidenceDetail: String,
            val progressFraction: Float,
            val aliases: Set<String> = emptySet()
        )

        private val stages = listOf(
            StageCopy(
                state = "initializing",
                title = "Preparing session...",
                stageLabel = "Preflight check",
                confidenceLabel = "Session preflight",
                confidenceDetail = "Checking host readiness before opening the stream.",
                progressFraction = 0.12f,
                aliases = setOf("idle")
            ),
            StageCopy(
                state = "connecting",
                title = "Connecting to host...",
                stageLabel = "Connection setup",
                confidenceLabel = "Opening client connection",
                confidenceDetail = "Nova is resolving the host and preparing local stream services.",
                progressFraction = 0.18f,
                aliases = setOf(
                    "platform initialization",
                    "name resolution",
                    "audio stream initialization"
                )
            ),
            StageCopy(
                state = "rtsp",
                title = "Opening stream session...",
                stageLabel = "RTSP session",
                confidenceLabel = "RTSP handshake",
                confidenceDetail = "Nova is negotiating the stream session with Polaris.",
                progressFraction = 0.24f,
                aliases = setOf("rtsp handshake")
            ),
            StageCopy(
                state = "control",
                title = "Connecting controls...",
                stageLabel = "Control path",
                confidenceLabel = "Control channel",
                confidenceDetail = "Nova is establishing the control path for the session.",
                progressFraction = 0.34f,
                aliases = setOf("control stream initialization", "control stream establishment")
            ),
            StageCopy(
                state = "cage_starting",
                title = "Starting compositor...",
                stageLabel = "Host display",
                confidenceLabel = "Host display starting",
                confidenceDetail = "Polaris is preparing the display session.",
                progressFraction = 0.46f,
                aliases = setOf("app")
            ),
            StageCopy(
                state = "game_launching",
                title = "Launching game...",
                stageLabel = "Game launch",
                confidenceLabel = "Game launch requested",
                confidenceDetail = "Nova is waiting for the host to expose the game window.",
                progressFraction = 0.64f,
                aliases = setOf("launch", "game")
            ),
            StageCopy(
                state = "video",
                title = "Starting video stream...",
                stageLabel = "Video pipeline",
                confidenceLabel = "Decoder handshake",
                confidenceDetail = "Nova is initializing video decoding for the stream.",
                progressFraction = 0.78f,
                aliases = setOf("video stream initialization", "video stream establishment")
            ),
            StageCopy(
                state = "audio",
                title = "Starting audio stream...",
                stageLabel = "Audio pipeline",
                confidenceLabel = "Audio handshake",
                confidenceDetail = "Nova is connecting the audio stream.",
                progressFraction = 0.88f,
                aliases = setOf("audio stream establishment")
            ),
            StageCopy(
                state = "input",
                title = "Enabling input...",
                stageLabel = "Input path",
                confidenceLabel = "Input handshake",
                confidenceDetail = "Nova is enabling controller and keyboard input.",
                progressFraction = 0.94f,
                aliases = setOf("input stream initialization", "input stream establishment")
            ),
            StageCopy(
                state = "unlocking_or_starting",
                title = "Waiting on host...",
                stageLabel = "Host readiness",
                confidenceLabel = "Server starting or unlocking",
                confidenceDetail = "The host is starting the app or unlocking before video can continue.",
                progressFraction = 0.96f,
                aliases = setOf("unlocking or starting", "server is starting or computer is unlocking")
            ),
            StageCopy(
                state = "host_locked",
                title = "Host locked",
                stageLabel = "Unlock host",
                confidenceLabel = "Unlock host to continue",
                confidenceDetail = "Nova is connected; unlock the host to continue into the stream.",
                progressFraction = 0.96f,
                aliases = setOf("locked", "screen_locked", "host screen locked")
            ),
            StageCopy(
                state = "stream_active",
                title = "Stream active...",
                stageLabel = "Stream active",
                confidenceLabel = "Waiting for first frame",
                confidenceDetail = "The host reports streaming; Nova is waiting for the first painted frame before clearing the overlay.",
                progressFraction = 0.97f,
                aliases = setOf("streaming", "waiting_first_frame")
            ),
            StageCopy(
                state = "input_ready",
                title = "Ready",
                stageLabel = "Input ready",
                confidenceLabel = "Input ready",
                confidenceDetail = "Controller, audio, and video channels are established.",
                progressFraction = 1f,
                aliases = setOf("connected")
            )
        )

        /** Space startup follows the observed worker request and native stream handshake.
         * Do not infer Steam readiness or complete stages from desktop session events. */
        fun fromSpace(state: String): NovaSessionProgressUiState? {
            val normalized = state.trim().lowercase()
            val (title, detail, order) = when (normalized) {
                "initializing", "conn_establishing" -> Triple("Checking Space", "Connecting to Polaris and checking your selected Space.", .05f)
                "space_starting" -> Triple("Starting Space", "Polaris is preparing your gaming environment and opening the selected app.", .1f)
                "space_connecting", "platform initialization", "name resolution", "audio stream initialization", "rtsp handshake" ->
                    Triple("Connecting Stream", "Connecting this device to your Space.", .2f)
                "control stream initialization", "control stream establishment" -> Triple("Connecting Controls", "Connecting the control channel to your Space.", .3f)
                "video stream initialization", "video stream establishment" -> Triple("Connecting Video", "Preparing the picture on this device.", .4f)
                "audio stream establishment" -> Triple("Connecting Sound", "Connecting your Space’s audio.", .5f)
                "input stream initialization", "input stream establishment" -> Triple("Connecting Controller", "Preparing controller and keyboard input.", .6f)
                "input_ready" -> Triple("Waiting For Picture", "Stream channels are connected. Waiting for the first picture.", .7f)
                else -> return null
            }
            return NovaSessionProgressUiState(normalized, title, "Opening Space", emptyList(), title, detail, order, true)
        }

        fun from(state: String, message: String = ""): NovaSessionProgressUiState {
            val normalizedState = state.trim().lowercase().ifBlank { "initializing" }
            val index = stages.indexOfFirst { stage ->
                stage.state == normalizedState || normalizedState in stage.aliases
            }
            val stage = stages.getOrNull(index)
            // An unrecognized state must never surface its raw protocol token as the
            // headline; a non-empty host message still wins. Empty words are filled from
            // resources where the overlay draws them.
            val title = stage?.title ?: message
            val completed = if (index >= 0) {
                stages.take(index).map { it.title }
            } else {
                emptyList()
            }
            return NovaSessionProgressUiState(
                state = stage?.state ?: normalizedState,
                title = title,
                stageLabel = stage?.stageLabel.orEmpty(),
                completedStages = completed,
                confidenceLabel = stage?.confidenceLabel.orEmpty(),
                confidenceDetail = stage?.confidenceDetail ?: message,
                progressFraction = stage?.progressFraction ?: 0.5f
            )
        }
    }
}

/**
 * The stream screen's own loading state while a session starts. It stays in the stream's view
 * tree, because it is bounded and asks nothing, and it wears the state pages' look: the window
 * colour over the stream, a centred column clear of the insets, and the panel type.
 */
@Composable
fun NovaSessionProgressOverlayContent(
    state: NovaSessionProgressUiState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val tvSafe = LocalNovaFormFactor.current == NovaFormFactor.Television
    // A stage the table does not know has no English of its own; its words come from resources.
    val stageLabel = state.stageLabel.ifEmpty { stringResource(R.string.nova_stream_progress_update) }
    val title = state.title.ifEmpty { stringResource(R.string.nova_stream_progress_working) }
    val confidenceLabel = state.confidenceLabel.ifEmpty { stringResource(R.string.nova_stream_progress_working) }
    val confidenceDetail = state.confidenceDetail.ifEmpty { stringResource(R.string.nova_stream_progress_waiting) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.window.copy(alpha = NovaPanelMetrics.StatePageAlpha))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(
                if (tvSafe) {
                    Modifier.padding(horizontal = NovaPanelMetrics.TvSafeHorizontal, vertical = NovaPanelMetrics.TvSafeVertical)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = NovaPanelMetrics.StateColumnMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(NovaPanelMetrics.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd)
        ) {
            Text(
                text = stageLabel.uppercase(Locale.getDefault()),
                style = type.sectionLabel,
                color = colors.accent,
                textAlign = TextAlign.Center
            )
            Text(text = title, style = type.stateTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
            if (state.indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.divider
                )
            } else {
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.divider
                )
            }
            Text(text = confidenceLabel, style = type.value, color = colors.accent, textAlign = TextAlign.Center)
            Text(text = confidenceDetail, style = type.rowTitle, color = colors.textSecondary, textAlign = TextAlign.Center)
            if (state.completedStages.isNotEmpty()) {
                Text(
                    text = state.completedStages.takeLast(3).joinToString("\n") { "✓ $it" },
                    style = type.caption,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
