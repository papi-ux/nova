package com.papi.nova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import kotlinx.coroutines.delay

/** The same Space identity stays visible through opening and stream settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovaSpaceContent(
    game: PolarisGame,
    hostName: String,
    activeSession: NovaLibraryActiveSessionUiState?,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showSettings: Boolean = false,
    settingsRows: List<NovaPlaySetupRowState> = emptyList(),
    primaryLabel: String? = null,
    primaryEnabled: Boolean = true,
    message: String? = null,
    focusEpoch: Int = 0,
    focusEnabled: Boolean = true,
    onSystem: (() -> Unit)? = null,
    onChoose: (() -> Unit)? = null,
    displayName: String? = null,
    spaceState: String? = null,
    /** Present while an open check is on the wire: the person can stop waiting for the host. */
    onCancel: (() -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    val inputModeManager = LocalInputModeManager.current
    val availability = NovaSpaceUiState.availability(game, activeSession)
    val inUse = spaceState == "in_use" || availability == NovaSpaceUiState.Availability.IN_USE
    val primaryFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val chooseFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val systemFocus = remember { FocusRequester() }
    var lastAction by rememberSaveable(game.id) { mutableStateOf("open") }
    LaunchedEffect(showSettings, focusEpoch, focusEnabled, primaryEnabled, inUse) {
        if (focusEnabled) {
            delay(NOVA_FIRST_FOCUS_SETTLE_MS)
            val target = when {
                showSettings -> backFocus
                lastAction == "choose" && onChoose != null -> chooseFocus
                lastAction == "system" && onSystem != null -> systemFocus
                lastAction == "back" -> backFocus
                lastAction == "settings" || inUse || !primaryEnabled -> settingsFocus
                else -> primaryFocus
            }
            inputModeManager.requestInputMode(InputMode.Keyboard)
            runCatching { target.requestFocus() }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(colors.window)
            .windowInsetsPadding(WindowInsets.safeDrawing).testTag("nova-space-screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(if (showSettings) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (showSettings) 12.dp else 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Canvas(Modifier.size(if (showSettings) 40.dp else 64.dp)) {
                    val ink = colors.accent
                    drawCircle(ink, radius = size.minDimension * 0.29f, style = Stroke(3.dp.toPx()))
                    rotate(-30f) {
                        drawOval(ink, topLeft = Offset(0f, size.height * .34f),
                            size = Size(size.width, size.height * .32f), style = Stroke(2.dp.toPx()))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nova_space_your_space), color = colors.accent, fontSize = 14.sp)
                    Text(displayName ?: game.name, color = colors.textPrimary, fontSize = if (showSettings) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.nova_space_on_host, hostName), color = colors.textSecondary, fontSize = 14.sp)
                }
                if (showSettings) {
                    NovaActionButton(
                        text = stringResource(R.string.nova_space_done), onClick = onBack, minHeight = 48.dp,
                        modifier = Modifier.focusRequester(backFocus).testTag("nova-space-settings-done"),
                    )
                }
            }
            Text(
                stringResource(if (showSettings) R.string.nova_space_settings_description else R.string.nova_space_description),
                color = colors.textSecondary, fontSize = 16.sp,
            )
            if (!showSettings && spaceState != null) {
                // The state is a chip, in the same word the chooser and the console use for it.
                // Its words sit on the chip's control, so a live state takes the accent for one.
                NovaBadge(
                    text = stringResource(NovaSpacesCopy.stateLabel(spaceState)),
                    color = if (spaceState == "ready" || spaceState == "running") colors.accentOnControl else colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("nova-space-status"),
                )
            }
            if (inUse) {
                Text(stringResource(R.string.nova_space_in_use), color = colors.textPrimary, fontSize = 16.sp)
            }
            message?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.testTag("nova-space-message"))
            }
            if (showSettings) {
                Text(stringResource(R.string.nova_space_stream_settings), color = colors.textPrimary,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                val rows = NovaSpaceUiState.streamingRows(settingsRows)
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.nova_space_settings_loading), color = colors.textSecondary)
                }
                BoxWithConstraints {
                    val rowContent: @Composable (NovaPlaySetupRowState, Modifier) -> Unit = { row, rowModifier ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = rowModifier) {
                        Text(row.label, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(row.caption, color = colors.textSecondary, fontSize = 14.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.options.forEach { option ->
                                NovaActionButton(
                                    text = option.label, onClick = { option.onSelect?.invoke() },
                                    enabled = row.enabled && option.enabled && option.onSelect != null,
                                    selected = option.current, minHeight = 48.dp,
                                    contentDescription = listOf(option.label, option.consequence).filter { it.isNotBlank() }.joinToString(". "),
                                )
                            }
                        }
                    }
                    }
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            rows.forEach { row -> rowContent(row, Modifier.weight(1f)) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            rows.forEach { row -> rowContent(row, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovaActionButton(
                        // A Space in use keeps its verb; the chip and the sentence above say why it waits.
                        text = when {
                            availability == NovaSpaceUiState.Availability.RESUMABLE -> stringResource(R.string.nova_space_resume)
                            else -> primaryLabel ?: stringResource(R.string.nova_space_open)
                        },
                        onClick = onOpen, enabled = primaryEnabled && !inUse, primary = true, minHeight = 52.dp,
                        modifier = Modifier.focusRequester(primaryFocus)
                            .onFocusChanged { if (it.isFocused) lastAction = "open" }.testTag("nova-space-primary"),
                    )
                    onCancel?.let { cancel ->
                        NovaActionButton(text = stringResource(R.string.nova_space_cancel_check), onClick = cancel, minHeight = 52.dp,
                            modifier = Modifier.testTag("nova-space-cancel"))
                    }
                    NovaActionButton(
                        text = stringResource(R.string.nova_space_stream_settings), onClick = { lastAction = "settings"; onSettings() }, minHeight = 52.dp,
                        modifier = Modifier.focusRequester(settingsFocus)
                            .onFocusChanged { if (it.isFocused && primaryEnabled) lastAction = "settings" }.testTag("nova-space-settings"),
                    )
                    onChoose?.let { action ->
                        NovaActionButton(text = stringResource(R.string.nova_space_change), onClick = action, minHeight = 52.dp,
                            modifier = Modifier.focusRequester(chooseFocus).onFocusChanged { if (it.isFocused) lastAction = "choose" }
                                .testTag("nova-space-choose"))
                    }
                    onSystem?.let { action ->
                        NovaActionButton(text = stringResource(R.string.nova_space_system), onClick = action, minHeight = 52.dp,
                            modifier = Modifier.focusRequester(systemFocus).onFocusChanged { if (it.isFocused) lastAction = "system" })
                    }
                    NovaActionButton(text = stringResource(R.string.nova_space_back), onClick = onBack, minHeight = 52.dp,
                        modifier = Modifier.focusRequester(backFocus).onFocusChanged { if (it.isFocused) lastAction = "back" })
                }
            }
            if (!showSettings) Text(stringResource(R.string.nova_space_controller_hint), color = colors.textSecondary, fontSize = 13.sp)
        }
    }
}
