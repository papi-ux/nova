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
import androidx.compose.material3.CircularProgressIndicator
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

data class NovaReconnectOverlayState(
    val attempt: Int,
    val maxAttempts: Int,
    val title: String = "Reconnecting...",
    val subtitle: String = "Stream will resume automatically"
) {
    val attemptLabel: String = "Attempt $attempt of $maxAttempts"
}

data class NovaSessionProgressUiState(
    val state: String,
    val title: String,
    val completedStages: List<String>
) {
    companion object {
        private val stages = listOf(
            "initializing" to "Preparing session...",
            "cage_starting" to "Starting compositor...",
            "game_launching" to "Launching game...",
            "streaming" to "Connected"
        )

        fun from(state: String, message: String = ""): NovaSessionProgressUiState {
            val index = stages.indexOfFirst { it.first == state }
            val title = stages.getOrNull(index)?.second ?: message.ifEmpty { state }
            val completed = if (index >= 0) {
                stages.take(index).map { it.second }
            } else {
                emptyList()
            }
            return NovaSessionProgressUiState(
                state = state,
                title = title,
                completedStages = completed
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
            text = state.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        CircularProgressIndicator(
            color = LocalNovaComposeColors.current.accent,
            trackColor = Color.White.copy(alpha = 0.18f),
            modifier = Modifier.padding(vertical = 28.dp)
        )
        if (state.completedStages.isNotEmpty()) {
            Text(
                text = state.completedStages.joinToString("\n") { "✓ $it" },
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
            .background(Color.Black.copy(alpha = scrimAlpha))
            .padding(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}
