package com.papi.nova.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisDesktopSteam
import com.papi.nova.ui.panel.*
import com.papi.nova.ui.compose.LocalNovaComposeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NovaSteamPage(val client: PolarisApiClient?, val hostName: String) : NovaPage {
    override val key = "desktop-steam"
    override val title = "Steam"
}

@Composable
internal fun NovaPageScope.NovaSteamControls(page: NovaSteamPage) {
    val scope = rememberCoroutineScope()
    var state by remember(page) { mutableStateOf<PolarisDesktopSteam?>(null) }
    var busy by remember(page) { mutableStateOf(false) }
    var message by remember(page) { mutableStateOf("Checking Steam…") }
    var confirmQuit by remember(page) { mutableStateOf(false) }
    val top = rememberUpdatedState(isTop)
    suspend fun read() {
        state = withContext(Dispatchers.IO) { page.client?.getDesktopSteam() }
        message = state?.let { if (it.available) "Steam is ${it.state}." else it.message }
            ?: "Steam controls are unavailable. Refresh after checking this PC's connection."
    }
    fun act(action: String?, confirmed: Boolean = false) {
        if (busy || !top.value) return
        val reviewed = state
        confirmQuit = false
        busy = true
        scope.launch {
            try {
                if (action == null) read() else {
                    val receipt = withContext(Dispatchers.IO) {
                        page.client?.controlDesktopSteam(action, reviewed?.revision.orEmpty(), confirmed)
                    }
                    state = null // A receipt never substitutes for observed status.
                    message = receipt?.message?.takeIf { it.isNotBlank() }
                        ?: if (receipt?.accepted == true) "Command accepted. Checking Steam…" else "Couldn't confirm the command. Refresh before retrying."
                    if (receipt?.accepted == true) {
                        repeat(10) {
                            delay(500)
                            read()
                            if (state?.available == true && (action != "quit" || state?.state == "stopped")) return@launch
                        }
                        message = "The command was accepted, but its outcome could not be confirmed. Refresh before retrying."
                    } else if (receipt?.message.isNullOrBlank()) {
                        message = "Couldn't confirm the command. Refresh before retrying."
                    }
                }
            } finally { busy = false }
        }
    }
    LaunchedEffect(page) { busy = true; try { read() } finally { busy = false } }
    val colors = LocalNovaComposeColors.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap)) {
        Text(page.hostName, color = colors.textPrimary)
        Text(message, color = colors.textPrimary)
        state?.takeIf { it.available }?.let {
            Text(if (it.effectiveKeepRunning) "After End Session: keep desktop Steam running." else "After End Session: close Steam started for this stream.", color = colors.textPrimary)
        }
        SteamButton("Refresh", onClick = { act(null) }, enabled = !busy,
            modifier = Modifier.novaInitialFocus())
        SteamButton("Open Steam", onClick = { act("open") }, enabled = !busy && state?.available == true)
        SteamButton("Open Big Picture", onClick = { act("big_picture") }, enabled = !busy && state?.available == true)
        SteamButton("Quit Steam", onClick = {
            if (state?.gameActive == true) confirmQuit = true else act("quit")
        }, enabled = !busy && state?.state == "running")
        if (confirmQuit) {
            Text("A game is running. Quitting Steam may close it.", color = colors.textPrimary)
            SteamButton("Keep Steam running", onClick = { confirmQuit = false })
            SteamButton("Confirm Quit Steam", onClick = { act("quit", true) })
        }
    }
}

@Composable
private fun SteamButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    NovaRow(title = text, onClick = onClick, modifier = modifier,
        disabledReason = if (enabled) null else "Check Steam status before continuing.")
}
