package com.papi.nova.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaMenuBackdropBlur
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import com.papi.nova.ui.compose.NovaInGameOverlayAlpha
import androidx.compose.ui.res.stringResource
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class NovaQuickMenuCallbacks(
    val onDismiss: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onEndStream: () -> Unit = {},
    val onStability: () -> Unit = {},
    val onSyncStatus: () -> Unit = {},
    val onToggleAdvanced: () -> Unit = {},
    val onClearGameProfile: () -> Unit = {},
    val onMangoHud: () -> Unit = {},
    val onProfilePreference: (String) -> Unit = {},
    val onQuickKey: (NovaQuickMenuActionId) -> Unit = {},
    val onOverlayAction: (NovaQuickMenuActionId) -> Unit = {},
    val onHudModeSelect: (NovaHudMode) -> Unit = {},
    val onDoctorUndo: () -> Unit = {},
    val onHudOpacityChange: (Int) -> Unit = {},
    val onMenuOpacityChange: (Int) -> Unit = {},
    val onControlAction: (NovaQuickMenuActionId) -> Unit = {},
    val onSessionAction: (NovaQuickMenuActionId) -> Unit = {}
) {
    fun perform(action: NovaQuickMenuAction) {
        when (action.id) {
            NovaQuickMenuActionId.DISCONNECT -> onDisconnect()
            NovaQuickMenuActionId.END_STREAM -> onEndStream()
            NovaQuickMenuActionId.STABILITY -> onStability()
            NovaQuickMenuActionId.SYNC_STATUS -> onSyncStatus()
            NovaQuickMenuActionId.ADVANCED_TUNING -> onToggleAdvanced()
            NovaQuickMenuActionId.CLEAR_GAME_PROFILE -> onClearGameProfile()
            NovaQuickMenuActionId.MANGOHUD -> onMangoHud()
            NovaQuickMenuActionId.QUICK_ESC,
            NovaQuickMenuActionId.QUICK_ALT_ENTER,
            NovaQuickMenuActionId.QUICK_ALT_F4,
            NovaQuickMenuActionId.QUICK_F11,
            NovaQuickMenuActionId.QUICK_INSERT,
            NovaQuickMenuActionId.QUICK_META,
            NovaQuickMenuActionId.QUICK_CTRL_V,
            NovaQuickMenuActionId.QUICK_CTRL_1,
            NovaQuickMenuActionId.QUICK_CTRL_2 -> onQuickKey(action.id)
            NovaQuickMenuActionId.NOVA_HUD,
            NovaQuickMenuActionId.PERF_STATS,
            NovaQuickMenuActionId.DIAGNOSE_STREAM,
            NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS -> onOverlayAction(action.id)
            NovaQuickMenuActionId.DOCTOR_UNDO -> onDoctorUndo()
            NovaQuickMenuActionId.MOUSE_MODE,
            NovaQuickMenuActionId.CONTROLLER,
            NovaQuickMenuActionId.KEYBOARD -> onControlAction(action.id)
            NovaQuickMenuActionId.PASTE_CLIPBOARD,
            NovaQuickMenuActionId.ROTATE_SCREEN,
            NovaQuickMenuActionId.MORE_KEYS -> onSessionAction(action.id)
        }
    }
}

private const val NovaQuickMenuDrawerDismissProgress = 0.58f

@Composable
fun NovaQuickMenuDrawer(
    state: NovaQuickMenuUiState,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    dismissRequests: Int = 0
) {
    NovaMenuBackdropBlur()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val scope = rememberCoroutineScope()
    val drawerProgress = remember { Animatable(0f) }
    val compactDrawerWidth = (configuration.screenWidthDp * 0.92f).dp
    val drawerWidth = if (configuration.screenWidthDp < 560) {
        compactDrawerWidth
    } else {
        460.dp
    }
    val drawerWidthPx = with(density) { drawerWidth.toPx().coerceAtLeast(1f) }

    // One collector follows the finger, conflated to a frame's pace. The drag used to
    // launch a coroutine per pointer-move event — sixty to a hundred and twenty a
    // second, each stopping whatever the one before it had started — to do what a
    // single snapTo per frame does.
    val dragInProgress = remember { mutableStateOf(false) }
    val dragProgress = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { if (dragInProgress.value) dragProgress.floatValue else Float.NaN }
            .collect { target ->
                if (!target.isNaN()) {
                    drawerProgress.snapTo(target)
                }
            }
    }

    suspend fun animateDrawerTo(target: Float) {
        drawerProgress.animateTo(
            targetValue = target.coerceIn(0f, 1f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    fun dismissDrawerWithMotion() {
        scope.launch {
            animateDrawerTo(0f)
            callbacks.onDismiss()
        }
    }

    fun settleDrawerAfterDrag() {
        if (drawerProgress.value < NovaQuickMenuDrawerDismissProgress) {
            dismissDrawerWithMotion()
        } else {
            scope.launch { animateDrawerTo(1f) }
        }
    }

    // Close, B, and Back used to drop the dialog on the spot while scrim-tap and drag slid
    // it out. Every exit takes the motion now: the header's Close goes through this copy,
    // and the host bumps dismissRequests for the controller and Back paths.
    val contentCallbacks = remember(callbacks) {
        callbacks.copy(onDismiss = { dismissDrawerWithMotion() })
    }
    LaunchedEffect(dismissRequests) {
        if (dismissRequests > 0) dismissDrawerWithMotion()
    }

    // Keyed on Unit: the entrance plays once, when the drawer opens. It used to be
    // keyed on the measured width, so any configuration change -- a rotation, a font
    // scale change, an external display attaching -- snapped the open drawer shut and
    // replayed the animation.
    LaunchedEffect(Unit) {
        drawerProgress.snapTo(0f)
        animateDrawerTo(1f)
    }

    val drawerOffsetPx = ((drawerProgress.value - 1f) * drawerWidthPx).roundToInt()

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    surfaces.backgroundScrim.copy(
                        alpha = NovaMenuPreferences.readabilityScrimAlpha(
                            NovaInGameOverlayAlpha.CommandCenterScrim,
                            LocalNovaMenuOpacityScale.current
                        ) * drawerProgress.value
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { dismissDrawerWithMotion() }
                )
                .semantics { contentDescription = "Dismiss Command Center" }
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(x = drawerOffsetPx, y = 0) }
                .fillMaxHeight()
                .width(drawerWidth)
                .widthIn(max = 460.dp)
                .pointerInput(drawerWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragProgress.floatValue = drawerProgress.value
                            dragInProgress.value = true
                            scope.launch { drawerProgress.stop() }
                        },
                        onDragCancel = {
                            dragInProgress.value = false
                            scope.launch { animateDrawerTo(1f) }
                        },
                        onDragEnd = {
                            dragInProgress.value = false
                            settleDrawerAfterDrag()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragProgress.floatValue =
                                (dragProgress.floatValue + dragAmount / drawerWidthPx).coerceIn(0f, 1f)
                        }
                    )
                }
        ) {
            NovaQuickMenuContent(
                state = state,
                callbacks = contentCallbacks,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun NovaQuickMenuContent(
    state: NovaQuickMenuUiState,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val drawerShape = RoundedCornerShape(topEnd = NovaRadius.drawer, bottomEnd = NovaRadius.drawer)
    val quickKeysTitle = stringResource(R.string.nova_quick_menu_quick_keys)
    val overlaysTitle = stringResource(R.string.nova_quick_menu_overlays)
    val controlsTitle = stringResource(R.string.nova_quick_menu_controls)
    val sessionTitle = stringResource(R.string.nova_quick_menu_session)
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Wait one Compose frame so the always-present session strip is attached. It is a safe,
        // non-destructive focus anchor while live Doctor/session data is still loading, so TV
        // remotes and controllers can navigate immediately instead of leaking input to the stream.
        withFrameNanos { }
        runCatching { initialFocusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(drawerShape)
            .background(surfaces.panel)
            .border(
                width = 1.dp,
                color = surfaces.panelBorder.copy(alpha = NovaInGameOverlayAlpha.Border * LocalNovaMenuOpacityScale.current),
                shape = drawerShape
            )
    ) {
        // The header stays put. Close, Disconnect, and End Session are under the thumb
        // however far the sections have been scrolled; they used to scroll away with the
        // first panel on a Retroid.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(NovaRadius.pill))
                    .background(colors.accent.copy(alpha = NovaInGameOverlayAlpha.AccentHandle))
                    .align(Alignment.Start)
            )
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuHeader(state, callbacks)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp)
        ) {
            NovaQuickMenuSessionStrip(state, initialFocusRequester)
            // The keys a handheld cannot press any other way stay one reach from the top;
            // the full keyboard grid lives further down with the rest of the panels.
            if (state.pinnedQuickKeys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                NovaQuickKeys(state.pinnedQuickKeys, callbacks)
            }
            // The strip above is a one-line verdict. What explains it, the Doctor's reading
            // and what Auto is running, comes next instead of three screens down. The panels
            // a player adjusts follow, Overlays first because the HUD switch is the frequent
            // tap, and the Quick Keys grid last of them since its top three are pinned above.
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuDiagnosisCard(state.diagnosis, callbacks)
            if (state.doctorReceiptAction.visible) {
                Spacer(Modifier.height(10.dp))
                NovaQuickMenuInfoCard(
                    action = state.doctorReceiptAction,
                    callbacks = callbacks
                )
            }
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuStabilityCard(state.stability, callbacks)
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuPanel(title = overlaysTitle) {
                state.overlayRows.forEach { row ->
                    NovaQuickMenuRow(action = row, callbacks = callbacks)
                    // The layout picker sits under the switch it configures.
                    if (row.id == NovaQuickMenuActionId.NOVA_HUD) {
                        NovaQuickMenuHudModePicker(state.hudMode, callbacks)
                    }
                }
                NovaQuickMenuMenuOpacityControl(
                    state = state,
                    callbacks = callbacks
                )
                NovaQuickMenuHudOpacityControl(
                    state = state,
                    callbacks = callbacks
                )
            }
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuPanel(title = controlsTitle) {
                state.controlRows.forEach { row ->
                    NovaQuickMenuRow(action = row, callbacks = callbacks)
                }
            }
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuPanel(title = sessionTitle) {
                state.sessionRows.filter { it.visible }.forEach { row ->
                    NovaQuickMenuRow(action = row, callbacks = callbacks)
                }
            }
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuPanel(title = quickKeysTitle) {
                NovaQuickKeys(state.quickKeys, callbacks)
            }
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuInfoCard(
                action = state.sync,
                callbacks = callbacks
            )
            Spacer(Modifier.height(10.dp))
            NovaQuickMenuInfoCard(
                action = state.advancedToggle,
                callbacks = callbacks
            )
            if (state.advancedExpanded) {
                Spacer(Modifier.height(10.dp))
                NovaQuickMenuPanel(title = null) {
                    state.advancedRows.forEach { row ->
                        NovaQuickMenuRow(action = row, callbacks = callbacks)
                    }
                }
                // Observational history from the host; it explains Auto's fallbacks but never
                // changes a launch, so it lives with the other diagnostics.
                if (state.postSessionReport.visible) {
                    Spacer(Modifier.height(10.dp))
                    NovaQuickMenuPostSessionReportCard(state.postSessionReport)
                }
            }
        }
    }
}

@Composable
private fun NovaQuickMenuHeader(
    state: NovaQuickMenuUiState,
    callbacks: NovaQuickMenuCallbacks
) {
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 430

    NovaQuickMenuPanel(title = null, contentPadding = PaddingValues(12.dp)) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NovaQuickMenuTitleBlock(state, Modifier.weight(1f))
                    NovaQuickMenuCloseButton(callbacks)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaQuickMenuHeaderButton(state.disconnectAction, callbacks, Modifier.weight(1f))
                    NovaQuickMenuHeaderButton(state.endAction, callbacks, Modifier.weight(1f))
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NovaQuickMenuTitleBlock(state, Modifier.weight(1f))
                NovaQuickMenuCloseButton(callbacks)
                NovaQuickMenuHeaderButton(state.disconnectAction, callbacks)
                NovaQuickMenuHeaderButton(state.endAction, callbacks)
            }
        }
    }
}

@Composable
private fun NovaQuickMenuTitleBlock(state: NovaQuickMenuUiState, modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    Column(modifier = modifier) {
        Text(
            text = state.title,
            color = colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = state.subtitle,
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Close is the primary: this menu opens on every Back press, so the safe action wears the
// accent. Disconnect stays quiet and End Session reads as destructive.
@Composable
private fun NovaQuickMenuHeaderButton(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier
) {
    NovaActionButton(
        text = action.label,
        onClick = { callbacks.perform(action) },
        modifier = modifier.widthIn(min = 84.dp),
        enabled = action.enabled,
        primary = false,
        destructive = action.destructive,
        cornerRadius = NovaRadius.hero,
        minHeight = 34.dp,
        fontSize = 12.sp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun NovaQuickMenuCloseButton(
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier
) {
    val closeCommandCenter = stringResource(R.string.nova_quick_menu_close_command_center)
    NovaActionButton(
        text = stringResource(R.string.nova_quick_menu_close),
        onClick = callbacks.onDismiss,
        modifier = modifier
            .widthIn(min = 72.dp)
            .semantics { contentDescription = closeCommandCenter },
        primary = true,
        cornerRadius = NovaRadius.hero,
        minHeight = 34.dp,
        fontSize = 12.sp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun NovaQuickMenuDiagnosisCard(
    diagnosis: NovaQuickMenuDiagnosisState,
    callbacks: NovaQuickMenuCallbacks,
) {
    val capabilityLabel = when (diagnosis.capability) {
        NovaQuickMenuDoctorCapability.AUTO_FIX -> stringResource(R.string.nova_quick_menu_doctor_capability_auto_fix)
        NovaQuickMenuDoctorCapability.RUN_TRIAL -> stringResource(R.string.nova_quick_menu_doctor_capability_run_trial)
        NovaQuickMenuDoctorCapability.RECHECK -> stringResource(R.string.nova_quick_menu_doctor_capability_recheck)
        NovaQuickMenuDoctorCapability.MANUAL -> stringResource(R.string.nova_quick_menu_doctor_capability_manual)
    }
    // Built once per diagnosis, not once per recomposition of the drawer.
    val detail = remember(diagnosis) {
        val classification = diagnosis.classification.takeIf { it in setOf("HOST", "NET", "CLIENT") }
        buildList {
            classification?.let(::add)
            diagnosis.tryFirst.takeIf { it.isNotBlank() }?.let { add("Try first: $it") }
            // Confidence only means something next to the evidence it grades.
            diagnosis.evidenceHighlight.takeIf { it.isNotBlank() }?.let { evidence ->
                add("Evidence: $evidence")
                diagnosis.confidence.takeIf { it.isNotBlank() }?.let { add("Confidence: $it") }
            }
        }.joinToString(" · ")
    }
    val diagnoseTitle = stringResource(R.string.nova_quick_menu_diagnose_stream)
    val aiSupportingLine = diagnosis.aiExplanation.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.nova_quick_menu_doctor_ai_explanation, it)
    }
    val sourceSupportingLine = diagnosis.informationalSource
        .takeIf { it.isNotBlank() }
        ?.let { "Source: $it" }
    val supportingLine = listOfNotNull(aiSupportingLine, sourceSupportingLine).joinToString("\n")
    // The finding is the title and the action lives in the chip, so "Recheck" no longer
    // shows up as title, chip, and button at once.
    val action = remember(diagnosis, detail, capabilityLabel, diagnoseTitle) {
        NovaQuickMenuAction(
            id = NovaQuickMenuActionId.DIAGNOSE_STREAM,
            label = diagnosis.likelyCause.trim().trimEnd('.').ifBlank { diagnoseTitle },
            caption = detail,
            chip = NovaQuickMenuChip(
                label = diagnosis.actionLabel.takeIf { diagnosis.actionExecutable && it.isNotBlank() }
                    ?: capabilityLabel,
                tone = if (diagnosis.available) NovaQuickMenuTone.INFO else NovaQuickMenuTone.MUTED
            ),
            enabled = diagnosis.available
        )
    }
    NovaQuickMenuInfoCard(
        action = action,
        supportingLine = supportingLine,
        // Doctor findings are whole sentences; give them a second line before ellipsis.
        labelMaxLines = 2,
        // The real callbacks. This used to construct a fresh default instance, whose
        // every member is a no-op, so the card rendered enabled and did nothing when
        // pressed -- and looked no different from one that worked. The guard forbids the
        // constructor by name, so this comment deliberately does not spell it.
        callbacks = callbacks,
    )
}

@Composable
private fun NovaQuickMenuSessionStrip(
    state: NovaQuickMenuUiState,
    initialFocusRequester: FocusRequester,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NovaRadius.row)
    val background = if (focused) {
        surfaces.selectedControl
    } else {
        surfaces.control.copy(alpha = NovaInGameOverlayAlpha.NestedControl * LocalNovaMenuOpacityScale.current)
    }
    val borderColor = if (focused) {
        surfaces.focusRing
    } else {
        surfaces.tileBorder.copy(alpha = NovaInGameOverlayAlpha.Border * LocalNovaMenuOpacityScale.current)
    }

    Row(
        modifier = Modifier
            .focusRequester(initialFocusRequester)
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(
                if (focused) 2.dp else 1.dp,
                borderColor,
                shape
            )
            .semantics {
                contentDescription = listOf(
                    state.sessionMode.label,
                    state.sessionDetail,
                    state.healthSummary,
                    state.healthDetail,
                ).filter { it.isNotBlank() }.joinToString(". ")
            }
            .onFocusChanged {
                val nowFocused = it.isFocused || it.hasFocus
                if (nowFocused && !focused) haptics.novaFocusTick()
                focused = nowFocused
            }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        NovaQuickMenuChipView(state.sessionMode)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.healthSummary,
                color = toneColor(state.healthTone),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (state.sessionDetail.isNotBlank()) {
                Text(
                    text = state.sessionDetail,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (state.healthDetail.isNotBlank()) {
                Text(
                    text = state.healthDetail,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun NovaQuickMenuPostSessionReportCard(report: NovaPostSessionReportUiState) {
    val colors = LocalNovaComposeColors.current
    NovaQuickMenuClickableSurface(
        enabled = false,
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        contentDescription = listOf(
            stringResource(R.string.nova_quick_menu_post_session_report),
            report.qualityLine,
            report.issueLine,
            report.nextLaunchLine,
            report.recoveryLine
        ).joinToString(". ")
    ) {
        Column {
            Text(
                text = stringResource(R.string.nova_quick_menu_post_session_report),
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.nova_quick_menu_post_session_report_caption),
                color = colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            listOf(report.qualityLine, report.issueLine, report.nextLaunchLine, report.recoveryLine).forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier.padding(top = 4.dp),
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NovaQuickMenuStabilityCard(
    stability: NovaQuickMenuStabilityState,
    callbacks: NovaQuickMenuCallbacks
) {
    val colors = LocalNovaComposeColors.current

    NovaQuickMenuClickableSurface(
        enabled = stability.enabled,
        onClick = { callbacks.perform(stability.action) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        contentDescription = stability.title
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stability.title,
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                NovaQuickMenuChipView(stability.chip)
            }
            if (stability.caption.isNotBlank()) {
                Text(
                    text = stability.caption,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Text(
                text = stability.targetSummary,
                color = colors.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    text = stability.profileTitle,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stability.profileCaption,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                stability.profileOptions.forEach { option ->
                    NovaQuickMenuPreferenceButton(
                        option = option,
                        modifier = Modifier.weight(1f),
                        onClick = { callbacks.onProfilePreference(option.value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaQuickMenuPreferenceButton(
    option: NovaQuickMenuPreferenceOption,
    modifier: Modifier,
    onClick: () -> Unit
) {
    NovaActionButton(
        text = option.label,
        onClick = onClick,
        modifier = modifier,
        enabled = option.enabled,
        primary = option.selected,
        cornerRadius = NovaRadius.hero,
        minHeight = 34.dp,
        fontSize = 10.sp,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
    )
}

@Composable
private fun NovaQuickMenuInfoCard(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    supportingLine: String = "",
    labelMaxLines: Int = 1
) {
    NovaQuickMenuClickableSurface(
        enabled = action.enabled,
        onClick = { callbacks.perform(action) },
        modifier = modifier,
        contentPadding = PaddingValues(11.dp),
        contentDescription = listOfNotNull(action.label, action.chip?.label, supportingLine)
            .filter { it.isNotBlank() }
            .joinToString(". ")
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = action.label,
                    color = LocalNovaComposeColors.current.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = labelMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                action.chip?.let { NovaQuickMenuChipView(it) }
            }
            if (supportingLine.isNotBlank()) {
                Text(
                    text = supportingLine,
                    color = LocalNovaComposeColors.current.accent,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (action.caption.isNotBlank()) {
                Text(
                    text = action.caption,
                    color = LocalNovaComposeColors.current.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun NovaQuickKeys(actions: List<NovaQuickMenuAction>, callbacks: NovaQuickMenuCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        actions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { action ->
                    NovaActionButton(
                        text = action.label,
                        onClick = { callbacks.perform(action) },
                        modifier = Modifier.weight(1f),
                        enabled = action.enabled,
                        cornerRadius = NovaRadius.hero,
                        minHeight = 36.dp,
                        fontSize = 11.sp,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NovaQuickMenuPanel(
    title: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(11.dp),
    content: @Composable () -> Unit
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val colors = LocalNovaComposeColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(surfaces.tile.copy(alpha = NovaInGameOverlayAlpha.NestedTile * LocalNovaMenuOpacityScale.current))
            .border(
                1.dp,
                surfaces.tileBorder.copy(alpha = NovaInGameOverlayAlpha.Border * LocalNovaMenuOpacityScale.current),
                RoundedCornerShape(NovaRadius.hero)
            )
            .padding(contentPadding)
    ) {
        if (!title.isNullOrBlank()) {
            NovaQuickMenuSectionHeader(title)
            Spacer(Modifier.height(7.dp))
        }
        content()
    }
}


@Composable
private fun NovaQuickMenuSectionHeader(title: String) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(colors.accent.copy(alpha = 0.14f))
            .border(1.dp, surfaces.focusRing.copy(alpha = 0.52f), RoundedCornerShape(NovaRadius.pill))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(colors.accent)
        )
        Text(
            text = title.uppercase(),
            color = colors.textSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NovaQuickMenuRow(action: NovaQuickMenuAction, callbacks: NovaQuickMenuCallbacks) {
    NovaQuickMenuClickableSurface(
        enabled = action.enabled,
        onClick = { callbacks.perform(action) },
        modifier = Modifier.fillMaxWidth(),
        flat = true,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
        contentDescription = action.label
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.label,
                    color = LocalNovaComposeColors.current.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (action.caption.isNotBlank()) {
                    Text(
                        text = action.caption,
                        color = LocalNovaComposeColors.current.textMuted,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            action.chip?.let {
                Spacer(Modifier.width(8.dp))
                NovaQuickMenuChipView(it)
            }
        }
    }
}

@Composable
private fun NovaQuickMenuMenuOpacityControl(
    state: NovaQuickMenuUiState,
    callbacks: NovaQuickMenuCallbacks
) {
    // Collapsed until asked for: the row is the setting, the preset strip is the editor.
    // Two strips open at once were about 120dp of a 312dp Retroid body.
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaQuickMenuOpacityHeader(
            title = stringResource(R.string.nova_quick_menu_menu_opacity),
            caption = stringResource(R.string.nova_quick_menu_menu_opacity_caption),
            chip = NovaQuickMenuChip(
                label = state.menuOpacity.percentLabel,
                tone = NovaQuickMenuTone.INFO
            ),
            expanded = expanded,
            enabled = true,
            onToggle = { expanded = !expanded }
        )
        if (expanded) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)
            ) {
                state.menuOpacity.presets.forEach { percent ->
                    val selected = percent == state.menuOpacity.percent
                    NovaActionButton(
                        text = percent.toString() + "%",
                        onClick = { callbacks.onMenuOpacityChange(percent) },
                        modifier = Modifier.weight(1f),
                        primary = selected,
                        contentDescription = stringResource(
                            R.string.nova_quick_menu_menu_opacity_preset_cd,
                            percent
                        ),
                        selected = selected,
                        stateDescription = stringResource(
                            if (selected) {
                                R.string.nova_quick_menu_hud_opacity_selected
                            } else {
                                R.string.nova_quick_menu_hud_opacity_not_selected
                            }
                        ),
                        cornerRadius = NovaRadius.hero,
                        minHeight = 44.dp,
                        fontSize = 10.sp,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaQuickMenuHudOpacityControl(
    state: NovaQuickMenuUiState,
    callbacks: NovaQuickMenuCallbacks
) {
    var expanded by remember { mutableStateOf(false) }
    // The strip closes with the HUD: a disabled row should not leave five dead buttons open.
    val presetsOpen = expanded && state.hudOpacity.enabled
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaQuickMenuOpacityHeader(
            title = stringResource(R.string.nova_quick_menu_hud_opacity),
            caption = stringResource(
                if (state.hudOpacity.enabled) {
                    R.string.nova_quick_menu_hud_opacity_caption
                } else {
                    R.string.nova_quick_menu_hud_opacity_disabled_caption
                }
            ),
            chip = NovaQuickMenuChip(
                label = state.hudOpacity.percentLabel,
                tone = if (state.hudOpacity.enabled) NovaQuickMenuTone.INFO else NovaQuickMenuTone.INACTIVE
            ),
            expanded = presetsOpen,
            enabled = state.hudOpacity.enabled,
            onToggle = { expanded = !expanded }
        )
        if (presetsOpen) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)
            ) {
                state.hudOpacity.presets.forEach { percent ->
                    val selected = percent == state.hudOpacity.percent
                    NovaActionButton(
                        text = percent.toString() + "%",
                        onClick = { callbacks.onHudOpacityChange(percent) },
                        modifier = Modifier.weight(1f),
                        enabled = state.hudOpacity.enabled,
                        primary = selected,
                        contentDescription = stringResource(
                            R.string.nova_quick_menu_hud_opacity_preset_cd,
                            percent
                        ),
                        selected = selected,
                        stateDescription = stringResource(
                            if (selected) {
                                R.string.nova_quick_menu_hud_opacity_selected
                            } else {
                                R.string.nova_quick_menu_hud_opacity_not_selected
                            }
                        ),
                        cornerRadius = NovaRadius.hero,
                        minHeight = 44.dp,
                        fontSize = 10.sp,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

// The collapsed face of an opacity control: reads like every other Overlays row, and the
// tap opens the presets beneath it.
@Composable
private fun NovaQuickMenuOpacityHeader(
    title: String,
    caption: String,
    chip: NovaQuickMenuChip,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val toggleHint = stringResource(
        if (expanded) R.string.nova_quick_menu_opacity_hide_presets else R.string.nova_quick_menu_opacity_show_presets
    )
    NovaQuickMenuClickableSurface(
        enabled = enabled,
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        flat = true,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
        contentDescription = listOf(title, chip.label, toggleHint).joinToString(". ")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            NovaQuickMenuChipView(chip)
        }
    }
}

// Four layouts, one tap each. This was a row that cycled blind: Debug was two presses away
// and nothing said where the next press would land.
@Composable
private fun NovaQuickMenuHudModePicker(
    hudMode: NovaQuickMenuHudModeState,
    callbacks: NovaQuickMenuCallbacks
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 7.dp)
    ) {
        Text(
            text = stringResource(R.string.nova_quick_menu_hud_mode),
            color = colors.textPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                if (hudMode.enabled) {
                    R.string.nova_quick_menu_hud_mode_caption
                } else {
                    R.string.nova_quick_menu_hud_mode_disabled_caption
                }
            ),
            color = colors.textMuted,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            hudMode.options.forEach { option ->
                NovaQuickMenuPreferenceButton(
                    option = option,
                    modifier = Modifier.weight(1f),
                    onClick = { callbacks.onHudModeSelect(NovaHudMode.fromPreference(option.value)) }
                )
            }
        }
    }
}

@Composable
private fun NovaQuickMenuClickableSurface(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    contentDescription: String,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(if (flat) NovaRadius.row else NovaRadius.hero)
    val base = if (flat) Color.Transparent else surfaces.tile.copy(alpha = NovaInGameOverlayAlpha.NestedTile * LocalNovaMenuOpacityScale.current)
    val focusedBackground = if (focused) surfaces.selectedControl else base
    val borderColor = when {
        focused -> surfaces.focusRing
        flat -> Color.Transparent
        else -> surfaces.tileBorder.copy(alpha = NovaInGameOverlayAlpha.Border * LocalNovaMenuOpacityScale.current)
    }
    val borderWidth = if (focused) 2.dp else if (flat) 0.dp else 1.dp

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(focusedBackground.copy(alpha = if (flat && !focused) 0f else focusedBackground.alpha))
            .border(borderWidth, borderColor, shape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .onFocusChanged {
                val nowFocused = it.isFocused || it.hasFocus
                if (nowFocused && !focused) haptics.novaFocusTick()
                focused = nowFocused
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.novaConfirm()
                    onClick()
                }
            )
            .focusable(enabled = enabled)
            .padding(contentPadding)
    ) {
        content()
    }
}

@Composable
private fun NovaQuickMenuChipView(chip: NovaQuickMenuChip) {
    val bg = toneColor(chip.tone).copy(alpha = if (chip.tone == NovaQuickMenuTone.INACTIVE) 0.16f else 0.20f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = chip.label,
            color = toneColor(chip.tone),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun toneColor(tone: NovaQuickMenuTone): Color {
    val colors = LocalNovaComposeColors.current
    return when (tone) {
        NovaQuickMenuTone.ACTIVE -> Color(0xFF4ADE80)
        NovaQuickMenuTone.INACTIVE -> colors.textSecondary
        NovaQuickMenuTone.MUTED -> colors.textMuted
        NovaQuickMenuTone.INFO -> colors.accent
        NovaQuickMenuTone.WARNING -> colors.warning
        NovaQuickMenuTone.DANGER -> Color(0xFFF87171)
    }
}
