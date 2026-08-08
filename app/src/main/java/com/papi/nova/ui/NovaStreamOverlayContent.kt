package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale

data class NovaReconnectOverlayState(
    val attempt: Int,
    val maxAttempts: Int,
    val title: String = "Reconnecting stream…",
    val subtitle: String = "Game is still running; Nova will resume automatically."
) {
    val attemptLabel: String = "Attempt $attempt of $maxAttempts"
}

data class NovaSessionProgressUiState(
    val state: String,
    val title: String,
    val stageLabel: String,
    val completedStages: List<String>,
    val confidenceLabel: String,
    val confidenceDetail: String,
    val progressFraction: Float
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

        fun from(state: String, message: String = ""): NovaSessionProgressUiState {
            val normalizedState = state.trim().lowercase().ifBlank { "initializing" }
            val index = stages.indexOfFirst { stage ->
                stage.state == normalizedState || normalizedState in stage.aliases
            }
            val stage = stages.getOrNull(index)
            // An unrecognized state must never surface its raw protocol token as the
            // headline; a non-empty host message still wins.
            val title = stage?.title ?: message.ifEmpty { "Working on it" }
            val completed = if (index >= 0) {
                stages.take(index).map { it.title }
            } else {
                emptyList()
            }
            return NovaSessionProgressUiState(
                state = stage?.state ?: normalizedState,
                title = title,
                stageLabel = stage?.stageLabel ?: "Startup update",
                completedStages = completed,
                confidenceLabel = stage?.confidenceLabel ?: "Working on it",
                confidenceDetail = stage?.confidenceDetail ?: message.ifEmpty { "Nova is waiting for the next stream setup signal." },
                progressFraction = stage?.progressFraction ?: 0.5f
            )
        }
    }
}

@Composable
fun NovaReconnectOverlayContent(
    state: NovaReconnectOverlayState,
    modifier: Modifier = Modifier
) {
    StreamOverlayScaffold(modifier = modifier, scrimAlpha = 0.86f) {
        Text(
            text = state.title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        LinearProgressIndicator(
            modifier = Modifier
                .padding(top = 34.dp, bottom = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            color = LocalNovaComposeColors.current.accent,
            trackColor = Color.White.copy(alpha = 0.18f)
        )
        Text(
            text = state.subtitle,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = state.attemptLabel,
            color = Color.White.copy(alpha = 0.56f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
fun NovaSessionProgressOverlayContent(
    state: NovaSessionProgressUiState,
    modifier: Modifier = Modifier
) {
    StreamOverlayScaffold(modifier = modifier, scrimAlpha = 0.80f) {
        Text(
            text = state.stageLabel,
            color = LocalNovaComposeColors.current.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Text(
            text = state.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier
                .padding(top = 28.dp, bottom = 18.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            color = LocalNovaComposeColors.current.accent,
            trackColor = Color.White.copy(alpha = 0.18f)
        )
        Text(
            text = state.confidenceLabel,
            color = LocalNovaComposeColors.current.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = state.confidenceDetail,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 560.dp)
        )
        if (state.completedStages.isNotEmpty()) {
            Text(
                text = state.completedStages.takeLast(3).joinToString("\n") { "✓ $it" },
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StreamOverlayScaffold(
    modifier: Modifier,
    scrimAlpha: Float,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = NovaMenuPreferences.readabilityScrimAlpha(
                        scrimAlpha,
                        LocalNovaMenuOpacityScale.current
                    )
                )
            )
            .padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}
