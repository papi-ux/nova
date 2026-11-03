package com.papi.nova.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaStepperRow
import com.papi.nova.ui.panel.LocalNovaStreamCover
import com.papi.nova.ui.panel.NovaChevron
import com.papi.nova.ui.panel.NovaFocusHint
import com.papi.nova.ui.panel.novaFocusHint
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaNestedRows
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaSectionLabel
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitConfirmState
import com.papi.nova.ui.panel.NovaSplitShape
import com.papi.nova.ui.panel.NovaSplitTone
import androidx.compose.ui.platform.testTag
import com.papi.nova.ui.panel.NovaTitleAndValueMeasurePolicy
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaRowRest
import com.papi.nova.ui.panel.novaScrollEdgeFade
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState
import kotlinx.coroutines.flow.StateFlow

data class NovaQuickMenuCallbacks(
    val onDismiss: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onEndStream: () -> Unit = {},
    val onStability: () -> Unit = {},
    val onStreamDetails: (focusBitrate: Boolean) -> Unit = {},
    val onDoctorDetails: () -> Unit = {},
    val onDoctorExplain: () -> Unit = {},
    val onTechnicalDetails: () -> Unit = {},
    /** Sets Live Tuning to the state its split offered when it armed, On for true. */
    val onLiveTuning: (enable: Boolean) -> Unit = {},
    val onSyncStatus: () -> Unit = {},
    val onToggleAdvanced: () -> Unit = {},
    val onClearGameProfile: () -> Unit = {},
    val onMangoHud: () -> Unit = {},
    val onProfilePreference: (String) -> Unit = {},
    val onBitrateStep: (com.papi.nova.manager.NovaLiveBitrateToken?, Int) -> Unit = { _, _ -> },
    val onBitrateExact: (com.papi.nova.manager.NovaLiveBitratePresentation) -> Unit = {},
    val onBitrateRecommended: (com.papi.nova.manager.NovaLiveBitrateToken?) -> Unit = {},
    val onBitrateDiagnostic: ((com.papi.nova.manager.NovaLiveBitratePresentation) -> Unit)? = null,
    val onQuickKey: (NovaQuickMenuActionId) -> Unit = {},
    val onOverlayAction: (NovaQuickMenuActionId) -> Unit = {},
    val onHudModeSelect: (NovaHudMode) -> Unit = {},
    /** True while a row that changes the HUD has focus, so the HUD shows at full strength. */
    val onHudPreview: (Boolean) -> Unit = {},
    val onHudPositionSelect: (NovaHudCorner) -> Unit = {},
    val onHudPositionReset: () -> Unit = {},
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
            // Live Tuning's split confirms the state it offered, through onLiveTuning; a bare
            // perform would have to guess, and a guess can turn it the wrong way.
            NovaQuickMenuActionId.LIVE_TUNING -> Unit
            NovaQuickMenuActionId.STREAM_DETAILS -> onStreamDetails(false)
            NovaQuickMenuActionId.BITRATE_DETAILS -> onStreamDetails(true)
            NovaQuickMenuActionId.DOCTOR_DETAILS -> onDoctorDetails()
            NovaQuickMenuActionId.TECHNICAL_DETAILS -> onTechnicalDetails()
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
            NovaQuickMenuActionId.STREAM_DETAILS,
    NovaQuickMenuActionId.BITRATE_DETAILS,
    NovaQuickMenuActionId.DOCTOR_DETAILS,
    NovaQuickMenuActionId.TECHNICAL_DETAILS,
    NovaQuickMenuActionId.MOUSE_MODE,
            NovaQuickMenuActionId.CONTROLLER,
            NovaQuickMenuActionId.KEYBOARD,
            NovaQuickMenuActionId.PLAYERS -> onControlAction(action.id)
            NovaQuickMenuActionId.PASTE_CLIPBOARD,
            NovaQuickMenuActionId.ROTATE_SCREEN,
            NovaQuickMenuActionId.MORE_KEYS,
            NovaQuickMenuActionId.MORE_CONTROLS,
            NovaQuickMenuActionId.REPORT_MOMENT,
            NovaQuickMenuActionId.WORKING_BASELINE -> onSessionAction(action.id)
        }
    }
}

/** Report This Moment's row, for tests and accessibility tooling. */
internal const val REPORT_MOMENT_TAG = "nova-cc-report-moment"

/** Working Baseline's row, for tests and accessibility tooling. */
internal const val WORKING_BASELINE_TAG = "nova-cc-working-baseline"

/** The sections' scrolling list, for tests to measure what it shows. */
internal const val NOVA_CC_SECTIONS_TAG = "nova-cc-sections"

/** Rows that push a page of their own, and so carry the page mark. */
private val OpensPage = setOf(
    NovaQuickMenuActionId.MOUSE_MODE,
    NovaQuickMenuActionId.MORE_KEYS,
    NovaQuickMenuActionId.MORE_CONTROLS,
    NovaQuickMenuActionId.WORKING_BASELINE,
)

/**
 * One part of the Command Center's state. The page collects the state once and never reads it
 * itself; each leaf reads the slice it shows, so a status refresh recomposes only what changed.
 */
@Composable
private fun <T> State<NovaQuickMenuUiState>.slice(select: (NovaQuickMenuUiState) -> T): State<T> =
    remember(this) { derivedStateOf { select(value) } }

/**
 * The Command Center's root page, drawn inside the panel the foundation owns: the frame, the
 * title, the hint bar, B and Start come from the panel; this is the header row and the sections.
 */
@Composable
fun NovaPageScope.NovaQuickMenuContent(
    state: StateFlow<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    place: NovaQuickMenuPlace? = null,
) {
    val ui = state.collectAsState()
    val endSplit = rememberNovaSplitConfirmState()
    val overlaysTitle = stringResource(R.string.nova_quick_menu_overlays)
    val controlsTitle = stringResource(R.string.nova_quick_menu_controls)
    val sessionTitle = stringResource(R.string.nova_quick_menu_session)
    val showPinnedKeys by ui.slice { it.pinnedQuickKeys.isNotEmpty() }
    val showDiagnosis by ui.slice { it.diagnosis.visible }
    val showReceipt by ui.slice { it.doctorReceiptAction.visible }

    // Reopened in the same stream, the page comes back to the row that had focus, whose focus brings
    // it into view (in-game #16). Otherwise the list opens at the top: it restored its old scroll
    // too, so touch, or focus going up to the header, left it reopening at Controls on Close (#420).
    val sections = rememberScrollState()
    place?.focusKey?.let { novaInitialFocusAt(it) }
    val hudPreview = remember(callbacks) { NovaHudPreviewFocus(callbacks.onHudPreview) }
    DisposableEffect(hudPreview) { onDispose { hudPreview.clear() } }
    CompositionLocalProvider(LocalNovaQuickMenuPlace provides place) {
        Column(modifier = modifier.fillMaxSize()) {
            // The header stays put. Close, Disconnect and End Session are under the thumb however
            // far the sections have been scrolled; they used to scroll away on a Retroid.
            NovaQuickMenuHeader(ui, callbacks, endSplit)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // A card cut by the edge under the header or above the hint bar dissolves there.
                    .testTag(NOVA_CC_SECTIONS_TAG)
                    .novaScrollEdgeFade(sections)
                    .verticalScroll(sections)
                    .padding(vertical = NovaPanelMetrics.SpaceSm),
                verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
            ) {
                // The page opens on Close, its safe action (R7); it opened on this strip, which acts
                // on nothing, so the first A did nothing and the ring sat on a status card.
                NovaQuickMenuSessionStrip(ui, Modifier)
                NovaQuickMenuRow(
                    ui.value.liveTuningAction.let { row -> row.copy(id = NovaQuickMenuActionId.STREAM_DETAILS, enabled = true,
                        caption = stringResource(R.string.nova_cc_current_stream, row.caption)) },
                    callbacks, novaPlaceFocus(NovaQuickMenuActionId.STREAM_DETAILS).testTag("nova-cc-live-tuning"), opens = true,
                )
                // The keys a handheld cannot press any other way stay one reach from the top; the
                // full grid is on More Keys.
                if (showPinnedKeys) NovaQuickKeys(ui, { it.pinnedQuickKeys }, callbacks)
                // The strip is a one-line verdict. What explains it, the Doctor's reading and what
                // Auto is running, comes next instead of three screens down. The card keeps this one
                // place whatever the reading says, the host's first answer included: a reading that
                // only informs reads quieter inside it (N28). Ranked last, it moved between two
                // places as the verdict flipped every second or two, and every row between them
                // jumped under the player. For a Polaris host it is here from the first frame to
                // close and the same size whatever it holds, checking, a reading, or the last
                // reading a few seconds old, so nothing below it moves (review finding 1).
                if (showDiagnosis) {
                    NovaQuickMenuDiagnosisCard(ui, callbacks)
                    NovaQuickMenuRow(NovaQuickMenuAction(NovaQuickMenuActionId.DOCTOR_DETAILS,
                        stringResource(R.string.nova_cc_doctor_details), caption = stringResource(R.string.nova_cc_supporting_details)),
                        callbacks, novaPlaceFocus(NovaQuickMenuActionId.DOCTOR_DETAILS), opens = true)
                }
                if (showReceipt) NovaQuickMenuInfoCard(ui, { it.doctorReceiptAction }, callbacks)
                NovaQuickMenuStabilityCard(ui, callbacks)

                // Overlays first because the HUD switch is the frequent tap.
                NovaSectionLabel(overlaysTitle)
                NovaQuickMenuOverlayRows(ui, callbacks, hudPreview)
                NovaQuickMenuHudOpacityControl(ui, callbacks, hudPreview)

                NovaSectionLabel(controlsTitle)
                NovaQuickMenuRows(ui, { it.controlRows }, callbacks)

                NovaSectionLabel(sessionTitle)
                NovaQuickMenuRows(ui, { rows -> rows.sessionRows.filter { it.visible } }, callbacks)

                NovaQuickMenuRow(NovaQuickMenuAction(NovaQuickMenuActionId.TECHNICAL_DETAILS,
                    stringResource(R.string.nova_cc_technical_details)), callbacks,
                    novaPlaceFocus(NovaQuickMenuActionId.TECHNICAL_DETAILS), opens = true)
            }
        }
    }
}

/**
 * Where the Command Center's focus and scroll were when it last closed, kept by its host for the
 * rest of the stream so the next opening comes back there (in-game #16).
 */
class NovaQuickMenuPlace {
    internal var focusKey: Any? = null
}

private val LocalNovaQuickMenuPlace = staticCompositionLocalOf<NovaQuickMenuPlace?> { null }

/**
 * [NovaPageScope.novaRestorableFocus], also recorded as where the Command Center next opens. A
 * button whose A acts at once (Disconnect) and every split (End Session, Alt + F4, Live Tuning and
 * Clear Game Profile) pass [reopenHere] false: the next opening starts on Close instead of one A
 * from ending something or rewriting the host.
 */
@Composable
private fun NovaPageScope.novaPlaceFocus(key: Any, reopenHere: Boolean = true): Modifier {
    val place = LocalNovaQuickMenuPlace.current
    return Modifier.novaRestorableFocus(key).onFocusChanged {
        if (it.hasFocus && place != null) place.focusKey = key.takeIf { reopenHere }
    }
}

/**
 * The fixed header: what kind of session this is, then Close, Disconnect and End Session sharing
 * one row by weight, so the panel's own width decides their size, whatever the screen says, and
 * a label that needs more room wraps rather than being cut. End Session confirms in its own slot:
 * A arms it into Stay and End Session, and while it is armed Close and Disconnect make room for
 * the pair, with what ending does written under the whole header.
 */
@Composable
private fun NovaPageScope.NovaQuickMenuHeader(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    endSplit: NovaSplitConfirmState,
) {
    val subtitle by ui.slice { it.subtitle }
    val disconnect by ui.slice { it.disconnectAction }
    val end by ui.slice { it.endAction }
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val armed = endSplit.armed
    val consequence = end.caption.takeIf { end.destructive && it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        Text(text = subtitle, style = type.caption, color = colors.textSecondary)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        ) {
            // Restorable, so focus comes back to the button it left when a state page such as
            // Connection Lost covers the panel and goes.
            if (!armed) {
                NovaQuickMenuCloseButton(callbacks, Modifier.weight(1f).novaInitialFocus().then(novaPlaceFocus("header-close")))
                if (disconnect.visible) {
                    NovaQuickMenuHeaderButton(disconnect, callbacks, Modifier.weight(1f).then(novaPlaceFocus("header-disconnect", reopenHere = false)))
                }
            }
            NovaQuickMenuEndButton(end, callbacks, endSplit, Modifier.weight(1f).then(novaPlaceFocus("header-end", reopenHere = false)))
        }
        AnimatedVisibility(
            visible = armed && consequence != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                text = consequence.orEmpty(),
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * End Session, or Leave Space in a Space: a split that confirms in its own slot. A viewer's Leave
 * ends nothing on the host, so it is a plain button.
 */
@Composable
private fun NovaQuickMenuEndButton(
    end: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    endSplit: NovaSplitConfirmState,
    modifier: Modifier,
) {
    if (!end.visible) return
    if (!end.destructive) {
        NovaQuickMenuHeaderButton(end, callbacks, modifier)
        return
    }
    NovaSplitConfirm(
        label = end.label,
        confirmLabel = end.label,
        onConfirm = { callbacks.perform(end) },
        modifier = modifier,
        icon = R.drawable.ic_close,
        // A button of the header's button row, as tall as Close and Disconnect with their corners.
        // It shares the row by weight, and armed its pair takes the whole row.
        shape = NovaSplitShape.Button,
        fillSlot = true,
        enabled = end.enabled,
        state = endSplit,
    )
}

// Close is the primary: this menu opens on every Back press, so the safe action wears the
// accent. Disconnect stays quiet.
@Composable
private fun NovaQuickMenuHeaderButton(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
) {
    if (!action.visible) return
    NovaPanelButton(
        text = action.label,
        onClick = { if (action.enabled) callbacks.perform(action) },
        modifier = modifier,
        primary = false,
        destructive = false,
    )
}

@Composable
private fun NovaQuickMenuCloseButton(
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
) {
    NovaPanelButton(
        text = stringResource(R.string.nova_quick_menu_close),
        onClick = callbacks.onDismiss,
        modifier = modifier,
        primary = true,
    )
}

@Composable
private fun NovaPageScope.NovaQuickMenuDiagnosisCard(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val diagnosis by ui.slice { it.diagnosis }
    // What pressing the card does, on its own line. The chip says only a state, Copied, as every
    // chip in the Command Center does; it had named the action, or the host's action label.
    val capabilityLabel = when (diagnosis.capability) {
        NovaQuickMenuDoctorCapability.AUTO_FIX -> stringResource(R.string.nova_quick_menu_doctor_capability_auto_fix)
        NovaQuickMenuDoctorCapability.RUN_TRIAL -> stringResource(R.string.nova_quick_menu_doctor_capability_run_trial)
        NovaQuickMenuDoctorCapability.RECHECK -> stringResource(R.string.nova_quick_menu_doctor_capability_recheck)
        NovaQuickMenuDoctorCapability.MANUAL -> stringResource(R.string.nova_quick_menu_doctor_capability_manual)
    }
    val copiedLabel = stringResource(R.string.nova_quick_menu_doctor_copied)
    val context = LocalContext.current
    val fasterThanLight by ui.slice { it.fasterThanLight }
    // What A does, on the line under the finding (N28). Before the first reading, why A does
    // nothing yet. A reading that only informs reads quieter, "Nothing to fix" ahead of what A
    // does; one the strip warns about says the strip's words there instead; the last reading kept
    // through a failed status read says how old it is.
    val quiet = diagnosis.informational
    val does = diagnosis.actionLabel.takeIf { diagnosis.actionExecutable && it.isNotBlank() } ?: capabilityLabel
    val doesLine = when {
        !diagnosis.available -> stringResource(R.string.nova_cc_doctor_checking_why)
        diagnosis.stale -> stringResource(R.string.nova_cc_doctor_stale, does)
        quiet -> stringResource(R.string.nova_cc_doctor_nothing_to_fix, does)
        diagnosis.stripVerdict.isNotBlank() -> stringResource(R.string.nova_cc_doctor_strip_says, diagnosis.stripVerdict, does)
        else -> does
    }
    val diagnoseTitle = stringResource(R.string.nova_quick_menu_diagnose_stream)
    val action = remember(diagnosis, copiedLabel, diagnoseTitle) {
        NovaQuickMenuAction(
            id = NovaQuickMenuActionId.DIAGNOSE_STREAM,
            label = diagnosis.likelyCause.trim().trimEnd('.').ifBlank { diagnoseTitle },
            caption = diagnosis.tryFirst,
            chip = if (diagnosis.copied) NovaQuickMenuChip(copiedLabel, NovaQuickMenuTone.INFO) else null,
            enabled = diagnosis.available
        )
    }
    NovaQuickMenuCard(
        action = action,
        modifier = novaPlaceFocus(NovaQuickMenuActionId.DIAGNOSE_STREAM).testTag("nova-cc-doctor"),
        supportingLine = doesLine,
        quiet = quiet,
        // The same size whatever it holds, and focusable in every state, the disabled-looking
        // one before the first reading included: a reading landing or a status read failing
        // never moves a row or takes focus from the card.
        fixedLines = true,
        focusableWhenDisabled = true,
        // The real callbacks. A fresh default instance renders enabled and does nothing when
        // pressed, and looks no different from one that works; the guard forbids the
        // constructor by name, so this comment deliberately does not spell it.
        callbacks = callbacks,
    )
}

/** The session, what kind of stream it is and how healthy, as one line that holds the first focus. */
@Composable
private fun NovaQuickMenuSessionStrip(
    ui: State<NovaQuickMenuUiState>,
    modifier: Modifier,
) {
    val sessionMode by ui.slice { it.sessionMode }
    val sessionDetail by ui.slice { it.sessionDetail }
    val healthSummary by ui.slice { it.healthSummary }
    val healthDetail by ui.slice { it.healthDetail }
    val overview by ui.slice { it.stream.overview }
    val healthTone by ui.slice { it.healthTone }
    val colors = LocalNovaComposeColors.current
    val haptics = rememberNovaHapticFeedback()
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    var focused by remember { mutableStateOf(false) }
    val description = listOf(sessionMode.label, sessionDetail, healthSummary, healthDetail, overview)
        .filter { it.isNotBlank() }
        .joinToString(". ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            // It acts on nothing: the hint bar offers B and no A while it has focus.
            .novaFocusHint(NovaFocusHint.Read)
            .semantics { contentDescription = description }
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            .focusable()
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        NovaQuickMenuChipView(sessionMode)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            Text(text = healthSummary, style = type.rowTitle, color = toneColor(healthTone), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The detail line keeps its place while the host has not answered, or a status read
            // failed, so the rows under the strip do not move as it comes and goes (review finding 1).
            Text(text = listOf(overview, sessionDetail, healthDetail).filter { it.isNotBlank() }.joinToString(" · "),
                style = type.caption, color = colors.textSecondary, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NovaQuickMenuPostSessionReportCard(ui: State<NovaQuickMenuUiState>) {
    val report by ui.slice { it.postSessionReport }
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    NovaQuickMenuStaticCard {
        Text(
            text = stringResource(R.string.nova_quick_menu_post_session_report),
            style = type.rowTitle,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.nova_quick_menu_post_session_report_caption),
            style = type.caption,
            color = colors.textSecondary,
        )
        listOf(report.qualityLine, report.issueLine, report.nextLaunchLine, report.recoveryLine).forEach { line ->
            Text(text = line, style = type.caption, color = colors.textSecondary)
        }
    }
}

/** The current stream's picture controls. No saved setup or next-launch preference writes. */
@Composable
private fun NovaPageScope.NovaQuickMenuStabilityCard(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val picture by ui.slice { it.liveBitrate }
    NovaQuickMenuRow(NovaQuickMenuAction(NovaQuickMenuActionId.BITRATE_DETAILS,
        stringResource(R.string.nova_cc_bitrate_details), caption = picture.result ?: picture.reason),
        callbacks, novaPlaceFocus(NovaQuickMenuActionId.BITRATE_DETAILS), opens = true)
}

@Composable
private fun NovaPageScope.NovaQuickMenuBitrateControls(ui: State<NovaQuickMenuUiState>, callbacks: NovaQuickMenuCallbacks) {
    val picture by ui.slice { it.liveBitrate }
    callbacks.onBitrateDiagnostic?.let { observe -> SideEffect { observe(picture) } }
    val rate = picture.rate
    val enabled = rate.canChange && !rate.busy
    NovaQuickMenuStaticCard(contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceMd)) {
        NovaStepperRow(
            title = stringResource(if (rate.units == com.papi.nova.manager.NovaBitrateUnits.VIDEO) R.string.nova_cc_video_target else R.string.nova_cc_request_budget),
            value = rate.requestedKbps ?: rate.minimumKbps,
            range = rate.minimumKbps..maxOf(rate.minimumKbps, rate.maximumKbps),
            step = 5000,
            format = { if (rate.requestedKbps == null) "Unavailable" else com.papi.nova.preferences.NovaBitrateAdvice.text(it, false) },
            onChange = {},
            onStep = { callbacks.onBitrateStep(picture.token, it) },
            onExact = { callbacks.onBitrateExact(picture) },
            enabled = enabled,
            caption = listOfNotNull(picture.numbers, picture.result ?: picture.reason).filter { it.isNotBlank() }.joinToString("\n"),
            modifier = novaPlaceFocus("stream-bitrate"),
        )
        val recommendation = rate.recommendedKbps
        val recommendationEnabled = enabled && rate.units == com.papi.nova.manager.NovaBitrateUnits.REQUEST && recommendation != null
        NovaRow(
            title = "Use recommended",
            caption = when {
                recommendation == null -> "Recommendation unavailable"
                rate.requestedKbps == recommendation -> "In use"
                else -> com.papi.nova.preferences.NovaBitrateAdvice.text(recommendation, true)
            },
            disabledReason = if (recommendationEnabled) null else
                if (recommendation == null) "Recommendation unavailable" else picture.reason,
            onClick = { callbacks.onBitrateRecommended(picture.token) },
            modifier = novaPlaceFocus("stream-bitrate-recommended"),
        )
    }
}

/**
 * A card that shows and does nothing itself; its rows are the focus stops. The card is the row
 * tile, so the rows inside it rest bare rather than as a tile inside a tile.
 */
@Composable
private fun NovaQuickMenuStaticCard(
    contentPadding: PaddingValues = PaddingValues(NovaPanelMetrics.SpaceMd),
    content: @Composable () -> Unit,
) {
    val rest = novaRowRest
    val shape = RoundedCornerShape(NovaRadius.row)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rest.fill, shape)
            .border(rest.borderWidth, rest.border, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
    ) { NovaNestedRows(content) }
}

/** A card that acts: the Doctor's receipt, Sync and Advanced. */
@Composable
private fun NovaPageScope.NovaQuickMenuInfoCard(
    ui: State<NovaQuickMenuUiState>,
    select: (NovaQuickMenuUiState) -> NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
) {
    val action by ui.slice(select)
    NovaQuickMenuCard(
        action = action, callbacks = callbacks, modifier = novaPlaceFocus(action.id),
        fixedLines = action.id == NovaQuickMenuActionId.DOCTOR_UNDO,
        focusableWhenDisabled = action.id == NovaQuickMenuActionId.DOCTOR_UNDO,
    )
}

@Composable
private fun NovaQuickMenuCard(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    supportingLine: String = "",
    /** Only informs: the title and the line under it in the secondary text, neither in bold. */
    quiet: Boolean = false,
    /**
     * The same size whatever it says: two lines for the title, one for the supporting line and two
     * for the caption, each cut at its end when longer, so a card whose words change never moves
     * the rows under it. TalkBack still hears every word.
     */
    fixedLines: Boolean = false,
    /** Keeps focus while disabled, so a card that turns disabled never drops it. */
    focusableWhenDisabled: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val titleWeight = if (quiet) FontWeight.Normal else FontWeight.SemiBold
    val titleLines = if (fixedLines) 2 else Int.MAX_VALUE
    var focused by remember { mutableStateOf(false) }
    NovaQuickMenuClickableSurface(
        enabled = action.enabled,
        focusableWhenDisabled = focusableWhenDisabled,
        onClick = { callbacks.perform(action) },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        contentPadding = PaddingValues(NovaPanelMetrics.SpaceMd),
        contentDescription = listOfNotNull(action.label, action.chip?.label, supportingLine, action.caption)
            .filter { it.isNotBlank() }
            .joinToString(". "),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            NovaQuickMenuTitleAndChip(
                title = {
                    Text(
                        text = action.label,
                        style = type.rowTitle,
                        fontWeight = titleWeight,
                        color = if (quiet) colors.textSecondary else colors.textPrimary,
                        minLines = if (fixedLines) titleLines else 1,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                chip = action.chip,
            )
            if (supportingLine.isNotBlank() || fixedLines) {
                Text(
                    text = supportingLine,
                    style = type.caption,
                    fontWeight = titleWeight,
                    // Under focus it sits on the focus fill, so it takes the accent for a control.
                    color = when {
                        quiet -> colors.textSecondary
                        focused -> colors.accentOnControl
                        else -> colors.accent
                    },
                    maxLines = if (fixedLines) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (action.caption.isNotBlank() || fixedLines) {
                Text(
                    text = action.caption,
                    style = type.caption,
                    color = colors.textSecondary,
                    minLines = if (fixedLines) 2 else 1,
                    maxLines = if (fixedLines) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A title with its chip at the end while the title keeps most of the row, and the chip under it
 * otherwise, so neither is squeezed or cut.
 */
@Composable
private fun NovaQuickMenuTitleAndChip(title: @Composable () -> Unit, chip: NovaQuickMenuChip?) {
    if (chip == null) {
        title()
        return
    }
    Layout(
        contents = listOf(title, { NovaQuickMenuChipView(chip) }),
        modifier = Modifier.fillMaxWidth(),
        measurePolicy = remember { NovaTitleAndValueMeasurePolicy(0.dp, 0.dp, NovaPanelMetrics.SpaceXs) },
    )
}

/**
 * Keys in rows of three, each one a button that sends it.
 *
 * Alt + F4 closes the focused window on the host, which is normally the game, so it is not one A
 * away: it splits in its own slot into Stay and Close App (R3). At rest it is a key like the keys
 * beside it, a third of the row. Armed, it takes its whole row, as a split does when its halves
 * would be narrower than 96dp, and its neighbours step aside.
 */
@Composable
private fun NovaPageScope.NovaQuickKeys(
    ui: State<NovaQuickMenuUiState>,
    select: (NovaQuickMenuUiState) -> List<NovaQuickMenuAction>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val actions by ui.slice(select)
    val closeApp = rememberNovaSplitConfirmState()
    Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)) {
        Text(
            text = stringResource(R.string.nova_quick_menu_quick_keys_caption),
            color = LocalNovaComposeColors.current.textMuted,
            style = novaPanelType.caption,
        )
        actions.chunked(3).forEach { row ->
            val splitTakesRow = closeApp.armed && row.any { it.id == NovaQuickMenuActionId.QUICK_ALT_F4 }
            Row(horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)) {
                row.forEach { action ->
                    // Keyed, so the split keeps its place in composition, and its armed state, when
                    // the keys beside it step aside.
                    key(action.id) {
                        when {
                            action.id == NovaQuickMenuActionId.QUICK_ALT_F4 -> NovaSplitConfirm(
                                label = action.label,
                                confirmLabel = stringResource(R.string.nova_cc_close_app),
                                onConfirm = { if (action.enabled) callbacks.perform(action) },
                                consequence = stringResource(R.string.nova_cc_alt_f4_consequence),
                                enabled = action.enabled,
                                state = closeApp,
                                fillSlot = true,
                                modifier = Modifier.weight(1f).then(novaPlaceFocus(action.id, reopenHere = false)),
                            )
                            !splitTakesRow -> NovaPanelButton(
                                text = action.label,
                                onClick = { if (action.enabled) callbacks.perform(action) },
                                modifier = Modifier.weight(1f).then(novaPlaceFocus(action.id)),
                            )
                        }
                    }
                }
                if (!splitTakesRow) {
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The Overlays rows, with the HUD layout picker right under the Nova HUD switch it configures. */
@Composable
private fun NovaPageScope.NovaQuickMenuOverlayRows(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    hudPreview: NovaHudPreviewFocus,
) {
    val rows by ui.slice { state -> state.overlayRows.filter { it.id == NovaQuickMenuActionId.NOVA_HUD } }
    rows.forEach { row ->
        NovaQuickMenuRow(row, callbacks, novaPlaceFocus(row.id))
        if (row.id == NovaQuickMenuActionId.NOVA_HUD) {
            NovaQuickMenuHudModePicker(
                ui,
                callbacks,
                novaPlaceFocus("hud-mode").onFocusChanged { hudPreview.update("hud-mode", it.hasFocus) },
            )
            NovaQuickMenuHudPositionControl(ui, callbacks, hudPreview)
        }
    }
}

/** Corner choices and Reset stay in the Command Center and work with a remote's arrows and OK. */
@Composable
private fun NovaPageScope.NovaQuickMenuHudPositionControl(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    hudPreview: NovaHudPreviewFocus,
) {
    val corner by ui.slice { it.hudPositionCorner }
    val enabled by ui.slice { it.hudMode.enabled }
    val options = listOf(
        NovaOption<NovaHudCorner?>(NovaHudCorner.TOP_LEFT, stringResource(R.string.nova_cc_hud_top_left)),
        NovaOption<NovaHudCorner?>(NovaHudCorner.TOP_RIGHT, stringResource(R.string.nova_cc_hud_top_right)),
        NovaOption<NovaHudCorner?>(NovaHudCorner.BOTTOM_LEFT, stringResource(R.string.nova_cc_hud_bottom_left)),
        NovaOption<NovaHudCorner?>(NovaHudCorner.BOTTOM_RIGHT, stringResource(R.string.nova_cc_hud_bottom_right)),
    ).let { corners ->
        if (corner == null) listOf(NovaOption<NovaHudCorner?>(null, stringResource(R.string.nova_cc_hud_dragged), disabledReason = "")) + corners
        else corners
    }
    NovaValueRow(
        title = stringResource(R.string.nova_cc_hud_position),
        options = options,
        current = corner,
        onChange = { it?.let(callbacks.onHudPositionSelect) },
        enabled = enabled,
        style = NovaValueStyle.Segmented,
        modifier = novaPlaceFocus("hud-position").testTag("nova-cc-hud-position")
            .onFocusChanged { hudPreview.update("hud-position", it.hasFocus) },
    )
    val reset = stringResource(R.string.nova_cc_hud_position_reset)
    NovaQuickMenuClickableSurface(
        enabled = enabled,
        onClick = callbacks.onHudPositionReset,
        modifier = novaPlaceFocus("hud-position-reset").fillMaxWidth().testTag("nova-cc-hud-position-reset")
            .onFocusChanged { hudPreview.update("hud-position-reset", it.hasFocus) },
        contentDescription = reset,
    ) {
        Text(reset, style = novaPanelType.rowTitle, color = LocalNovaComposeColors.current.textPrimary)
    }
}

/**
 * Which of the rows that change the HUD have focus. While one does, the HUD shows at full strength
 * instead of dimmed under the panel, so a new layout or opacity can be seen as it is picked
 * (in-game #4); it dims again when focus moves on.
 */
private class NovaHudPreviewFocus(private val report: (Boolean) -> Unit) {
    private val focused = mutableSetOf<String>()

    fun update(key: String, hasFocus: Boolean) {
        val before = focused.isNotEmpty()
        if (hasFocus) focused += key else focused -= key
        if (focused.isNotEmpty() != before) report(focused.isNotEmpty())
    }

    fun clear() {
        if (focused.isEmpty()) return
        focused.clear()
        report(false)
    }
}

/**
 * Whether the panel lies over the HUD, so its rows say so rather than change something out of
 * sight: an edge panel over the stream, on the display the HUD is on, whose width holds the HUD's
 * left edge (review finding 2). That is the HUD's own corner, the top start, and a HUD dragged
 * along the top or down the side within the panel's width. A HUD beyond the panel's edge, a
 * portrait sheet, which leaves the top clear, and the companion display, whose stream and HUD are
 * on the other screen, have nothing under the panel to name.
 */
@Composable
private fun novaHudUnderPanel(hudLeftPx: Float, enabled: Boolean): Boolean {
    val cover = LocalNovaStreamCover.current
    return enabled && cover != null && !hudLeftPx.isNaN() && hudLeftPx in cover
}

@Composable
private fun NovaPageScope.NovaQuickMenuRows(
    ui: State<NovaQuickMenuUiState>,
    select: (NovaQuickMenuUiState) -> List<NovaQuickMenuAction>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val rows by ui.slice(select)
    rows.forEach { row ->
        if (row.id == NovaQuickMenuActionId.CLEAR_GAME_PROFILE) {
            // The host's learned profile cannot be brought back, so clearing it splits in its own
            // row (R3) rather than going on one A. Its caption carries the reason it is locked,
            // or what the last clear did.
            NovaSplitConfirm(
                label = row.label,
                confirmLabel = stringResource(R.string.nova_game_detail_clear_profile_confirm),
                onConfirm = { if (row.enabled) callbacks.perform(row) },
                consequence = stringResource(R.string.nova_game_detail_clear_profile_consequence),
                icon = R.drawable.ic_update,
                shape = NovaSplitShape.Row,
                enabled = row.enabled,
                caption = row.caption,
                modifier = novaPlaceFocus(row.id, reopenHere = false).testTag("nova-cc-clear-game-profile"),
            )
        } else {
            // A row that pushed a page is where focus lands when that page pops.
            val place = novaPlaceFocus(row.id)
            NovaQuickMenuRow(
                row,
                callbacks,
                when (row.id) {
                    NovaQuickMenuActionId.REPORT_MOMENT -> place.testTag(REPORT_MOMENT_TAG)
                    NovaQuickMenuActionId.WORKING_BASELINE -> place.testTag(WORKING_BASELINE_TAG)
                    else -> place
                },
                opens = row.id in OpensPage,
            )
        }
    }
}

/**
 * Live Tuning is a host setting: switching it rewrites polaris.conf for every device that streams
 * from the host, so one A never changes it (M11). It splits in its row as End Session does, into
 * Stay and Turn Off (or Turn On), with what it changes written under the pair. At rest it is a row
 * among rows, its state on the chip at its end, in the rows' own look rather than End Session's
 * red: it changes a setting and ends nothing, so its confirm takes the accent.
 *
 * What the split offers is fixed when it arms, from the state the row showed then, and the confirm
 * asks for that state: a change from another device while it was armed turned Turn Off into Turn
 * On under the player, and the switch flipped whatever the host said at that moment.
 */
@Composable
private fun NovaPageScope.NovaQuickMenuLiveTuningRow(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val row by ui.slice { it.liveTuningAction }
    val on = row.chip?.tone == NovaQuickMenuTone.ACTIVE
    val split = rememberNovaSplitConfirmState()
    val turnOn = remember(split.arms) { !on }
    NovaSplitConfirm(
        label = row.label,
        confirmLabel = stringResource(if (turnOn) R.string.nova_cc_live_tuning_turn_on else R.string.nova_cc_live_tuning_turn_off),
        onConfirm = { if (row.enabled) callbacks.onLiveTuning(turnOn) },
        state = split,
        consequence = stringResource(R.string.nova_cc_live_tuning_consequence),
        icon = R.drawable.ic_settings,
        shape = NovaSplitShape.Row,
        enabled = row.enabled,
        caption = row.caption,
        // A result in the caption, such as a switch the host did not confirm, is said to TalkBack.
        announceCaption = row.announce,
        trailing = row.chip?.let { chip -> { NovaQuickMenuChipView(chip) } },
        stateDescription = row.chip?.label,
        tone = NovaSplitTone.Neutral,
        modifier = novaPlaceFocus(row.id, reopenHere = false).testTag("nova-cc-live-tuning-host"),
    )
}

@Composable
private fun NovaQuickMenuRow(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    opens: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    NovaQuickMenuClickableSurface(
        enabled = action.enabled,
        onClick = { callbacks.perform(action) },
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        contentDescription = listOfNotNull(action.label, action.caption, action.chip?.label)
            .filter { it.isNotBlank() }
            .joinToString(". "),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                NovaQuickMenuTitleAndChip(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
                            Text(text = action.label, style = type.rowTitle, color = colors.textPrimary)
                            val liveEntry = action.id == NovaQuickMenuActionId.STREAM_DETAILS || action.id == NovaQuickMenuActionId.BITRATE_DETAILS
                            if (action.caption.isNotBlank() || liveEntry) {
                                Text(text = action.caption, style = type.caption, color = colors.textSecondary,
                                    minLines = if (liveEntry) 2 else 1, maxLines = if (liveEntry) 2 else Int.MAX_VALUE,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    chip = action.chip,
                )
            }
            if (opens) NovaChevron(back = false, tint = colors.textSecondary)
        }
    }
}

/** Menu opacity steps through its presets in place: Left and Right, and the change applies at once. */
@Composable
private fun NovaPageScope.NovaQuickMenuMenuOpacityControl(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val menuOpacity by ui.slice { it.menuOpacity }
    val options = remember(menuOpacity.presets) { menuOpacity.presets.map { NovaOption(it, "$it%") } }
    NovaValueRow(
        title = stringResource(R.string.nova_quick_menu_menu_opacity),
        caption = stringResource(R.string.nova_quick_menu_menu_opacity_caption),
        options = options,
        current = menuOpacity.percent,
        onChange = callbacks.onMenuOpacityChange,
        // Every preset in the row with the current one checked, as HUD Mode shows its layouts. As a
        // cycler its value sat mid row, an arrow's width in from where every other row's value ends.
        style = NovaValueStyle.Segmented,
        ordered = true,
        modifier = novaPlaceFocus("menu-opacity"),
    )
}

/** HUD opacity, in place like menu opacity; it waits for the HUD to be on. */
@Composable
private fun NovaPageScope.NovaQuickMenuHudOpacityControl(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    hudPreview: NovaHudPreviewFocus,
) {
    val hudOpacity by ui.slice { it.hudOpacity }
    val options = remember(hudOpacity.presets) { hudOpacity.presets.map { NovaOption(it, "$it%") } }
    NovaValueRow(
        title = stringResource(R.string.nova_quick_menu_hud_opacity),
        caption = stringResource(
            when {
                !hudOpacity.enabled -> R.string.nova_quick_menu_hud_opacity_disabled_caption
                novaHudUnderPanel(hudOpacity.hudLeftPx, hudOpacity.enabled) -> R.string.nova_cc_hud_opacity_under_panel
                else -> R.string.nova_quick_menu_hud_opacity_caption
            }
        ),
        options = options,
        current = hudOpacity.percent,
        onChange = callbacks.onHudOpacityChange,
        style = NovaValueStyle.Segmented,
        ordered = true,
        enabled = hudOpacity.enabled,
        modifier = novaPlaceFocus("hud-opacity").onFocusChanged { hudPreview.update("hud-opacity", it.hasFocus) },
    )
}

// Four layouts in one row: Left and Right move between them in place, and the current one
// carries the check. This was a row that cycled blind, and later four filled buttons.
@Composable
private fun NovaQuickMenuHudModePicker(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier,
) {
    val hudMode by ui.slice { it.hudMode }
    val options = remember(hudMode.options) {
        hudMode.options.map { NovaOption(NovaHudMode.fromPreference(it.value), it.label) }
    }
    NovaValueRow(
        title = stringResource(R.string.nova_quick_menu_hud_mode),
        caption = stringResource(
            when {
                !hudMode.enabled -> R.string.nova_quick_menu_hud_mode_disabled_caption
                novaHudUnderPanel(hudMode.hudLeftPx, hudMode.enabled) -> R.string.nova_cc_hud_mode_under_panel
                !hudMode.guideShortcutsEnabled -> R.string.nova_quick_menu_hud_mode_caption_no_guide
                else -> R.string.nova_quick_menu_hud_mode_caption
            }
        ),
        options = options,
        current = hudMode.selected,
        onChange = callbacks.onHudModeSelect,
        enabled = hudMode.enabled,
        modifier = modifier,
    )
}

/**
 * The Command Center's own tappable surface: one focus stop with the one focus look, acting on
 * release through [novaClickable], at least a row tall. Row or card, it rests as the one row tile
 * every panel's rows do, so the rows and the cards between them read as one stack.
 */
@Composable
private fun NovaQuickMenuClickableSurface(
    enabled: Boolean,
    onClick: () -> Unit,
    focusableWhenDisabled: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(NovaPanelMetrics.SpaceMd),
    contentDescription: String,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val haptics = rememberNovaHapticFeedback()
    val shape = RoundedCornerShape(NovaRadius.row)

    Box(
        modifier = modifier
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .alpha(if (enabled) 1f else NovaPanelMetrics.DisabledAlpha)
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            .semantics {
                this.contentDescription = contentDescription
                if (!enabled && focusableWhenDisabled) disabled()
            }
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            // A surface that stays a focus stop while disabled keeps its clickable enabled and only
            // does nothing, saying so to TalkBack and the hint bar: a clickable that enables under
            // focus adds a focus target of its own, and the card read as unfocused while it had focus.
            .then(if (focusableWhenDisabled) Modifier.novaFocusHint(if (enabled) null else NovaFocusHint.Read) else Modifier)
            .novaClickable(enabled = enabled || focusableWhenDisabled, role = Role.Button) {
                if (!enabled) return@novaClickable
                haptics.novaConfirm()
                onClick()
            }
            .padding(contentPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
private fun NovaQuickMenuChipView(chip: NovaQuickMenuChip) {
    val tone = toneColor(chip.tone)
    val bg = tone.copy(
        alpha = if (chip.tone == NovaQuickMenuTone.INACTIVE) NovaPanelMetrics.QuietChipFillAlpha else NovaPanelMetrics.ToneChipFillAlpha,
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(bg)
            .padding(horizontal = NovaPanelMetrics.SpaceSm, vertical = NovaPanelMetrics.SpaceXs),
        contentAlignment = Alignment.Center
    ) {
        Text(text = chip.label, style = novaPanelType.caption, fontWeight = FontWeight.SemiBold, color = tone)
    }
}

@Composable
private fun toneColor(tone: NovaQuickMenuTone): Color {
    val colors = LocalNovaComposeColors.current
    return when (tone) {
        NovaQuickMenuTone.ACTIVE -> colors.positive
        NovaQuickMenuTone.INACTIVE -> colors.textSecondary
        NovaQuickMenuTone.MUTED -> colors.textMuted
        NovaQuickMenuTone.INFO -> colors.accent
        NovaQuickMenuTone.WARNING -> colors.warning
        NovaQuickMenuTone.DANGER -> colors.destructive
    }
}

/** Panel-native details reuse the opening's state and callbacks; opening a page never writes. */
@Composable
internal fun NovaPageScope.NovaCommandCenterDetails(
    page: CommandCenterPage,
    state: StateFlow<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
    place: NovaQuickMenuPlace? = null,
) {
    val ui = state.collectAsState()
    val endSplit = rememberNovaSplitConfirmState()
    val scroll = rememberScrollState()
    val hudPreview = remember(callbacks) { NovaHudPreviewFocus(callbacks.onHudPreview) }
    DisposableEffect(hudPreview) { onDispose { hudPreview.clear() } }
    if (page is CommandCenterPage.StreamDetails && page.focusBitrate) novaInitialFocusAt("bitrate-details")
    CompositionLocalProvider(LocalNovaQuickMenuPlace provides place) {
        Column(Modifier.fillMaxSize()) {
            NovaQuickMenuHeader(ui, callbacks, endSplit)
            Column(Modifier.weight(1f).fillMaxWidth().testTag(NOVA_CC_SECTIONS_TAG).novaScrollEdgeFade(scroll).verticalScroll(scroll)
                .padding(vertical = NovaPanelMetrics.SpaceSm), verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap)) {
                when (page) {
                    is CommandCenterPage.StreamDetails -> {
                        NovaQuickMenuSessionStrip(ui, Modifier)
                        NovaSectionLabel(stringResource(R.string.nova_cc_scope_host))
                        NovaDetailLine(stringResource(R.string.nova_cc_tuning_scope), "")
                        // This is the existing guarded host-wide switch, separate from navigation.
                        NovaQuickMenuLiveTuningRow(ui, callbacks)
                        Column(Modifier.fillMaxWidth().testTag("nova-cc-bitrate-details-heading").novaRestorableFocus("bitrate-details")
                            .novaFocusHint(NovaFocusHint.Read).novaFocusRing(RoundedCornerShape(NovaRadius.row), rest = novaRowRest).focusable().padding(NovaPanelMetrics.SpaceMd)) {
                            Text(stringResource(R.string.nova_cc_bitrate_details), style = novaPanelType.rowTitle,
                                color = LocalNovaComposeColors.current.textPrimary)
                            Text(stringResource(R.string.nova_cc_scope_stream), style = novaPanelType.caption,
                                color = LocalNovaComposeColors.current.textSecondary)
                        }
                        // The stream's manual controls lead; the read-only accounting follows them.
                        NovaQuickMenuBitrateControls(ui, callbacks)
                        ui.value.stream.rates.forEach { (label, value) ->
                            NovaDetailLine(label, value)
                        }
                        NovaSectionLabel(stringResource(R.string.nova_cc_recent_changes))
                        val stream = ui.value.stream
                        if (stream.recent.isEmpty()) {
                            NovaDetailLine(stringResource(if (stream.historyAvailable) R.string.nova_cc_recent_empty else R.string.nova_cc_recent_unavailable), "")
                        } else {
                            stream.recent.forEach { change ->
                                NovaDetailLine(stringResource(R.string.nova_cc_recent_host,
                                    commandCenterRate(change.beforeKbps), commandCenterRate(change.appliedKbps)), "")
                            }
                        }
                        NovaDetailLine(stringResource(R.string.nova_cc_recent_limits), "")
                        // Only the existing fresh receipt route offers Undo; observations create no authority.
                        if (ui.value.doctorReceiptAction.visible) NovaQuickMenuInfoCard(ui, { it.doctorReceiptAction }, callbacks)
                    }
                    CommandCenterPage.DoctorDetails -> {
                        val diagnosis = ui.value.diagnosis
                        NovaQuickMenuDiagnosisCard(ui, callbacks)
                        NovaDetailLine(stringResource(R.string.nova_cc_doctor_evidence, diagnosis.evidence.joinToString(" · ").ifBlank {
                            stringResource(R.string.nova_cc_no_evidence)
                        }), "")
                        if (diagnosis.confidence.isNotBlank()) NovaDetailLine(stringResource(R.string.nova_cc_doctor_confidence, diagnosis.confidence), "")
                        if (diagnosis.informationalSource.isNotBlank()) NovaDetailLine(stringResource(R.string.nova_cc_doctor_source, diagnosis.informationalSource), "")
                        // Previously supplied explanations remain separate from the bound request below.
                        if (diagnosis.aiExplanation.isNotBlank()) NovaDetailLine(stringResource(R.string.nova_quick_menu_doctor_ai_explanation, diagnosis.aiExplanation), "")
                        NovaDoctorAiDetails(ui.value.doctorAi, callbacks.onDoctorExplain)
                        if (ui.value.fasterThanLight) NovaDetailLine(stringResource(R.string.nova_cc_doctor_faster_than_light), "")
                        if (ui.value.doctorReceiptAction.visible) NovaQuickMenuInfoCard(ui, { it.doctorReceiptAction }, callbacks)
                    }
                    CommandCenterPage.TechnicalDetails -> {
                        NovaSectionLabel(stringResource(R.string.nova_cc_scope_device))
                        NovaQuickMenuMenuOpacityControl(ui, callbacks)
                        NovaQuickMenuRows(ui, { it.overlayRows.filter { row -> row.id != NovaQuickMenuActionId.NOVA_HUD } }, callbacks)
                        NovaQuickMenuInfoCard(ui, { it.sync }, callbacks)
                        NovaSectionLabel(stringResource(R.string.nova_cc_scope_host))
                        NovaQuickMenuRows(ui, { it.advancedRows }, callbacks)
                        if (ui.value.postSessionReport.visible) NovaQuickMenuPostSessionReportCard(ui)
                    }
                    else -> Unit
                }
            }
        }
}
}

@Composable
private fun NovaDetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm)) {
        Text(label, style = novaPanelType.caption, color = LocalNovaComposeColors.current.textSecondary)
        if (value.isNotBlank()) Text(value, style = novaPanelType.rowTitle, color = LocalNovaComposeColors.current.textPrimary)
    }
}

/** Plain optional prose, kept separate from the deterministic Doctor card and its controls. */
@Composable
internal fun NovaDoctorAiDetails(state: NovaDoctorAiState, onExplain: () -> Unit) {
    NovaSectionLabel(stringResource(R.string.nova_doctor_ai_title))
    NovaDetailLine(stringResource(R.string.nova_doctor_ai_disclosure), "")
    val caption = stringResource(when (state.message) {
        NovaDoctorAiMessage.DISABLED -> R.string.nova_doctor_ai_disabled
        NovaDoctorAiMessage.READY -> R.string.nova_doctor_ai_ready
        NovaDoctorAiMessage.PENDING -> R.string.nova_doctor_ai_pending
        NovaDoctorAiMessage.FALLBACK -> R.string.nova_doctor_ai_fallback
        NovaDoctorAiMessage.EXPLAINED -> R.string.nova_doctor_ai_informational
        NovaDoctorAiMessage.UNAVAILABLE -> R.string.nova_doctor_ai_unavailable
    })
    Column(Modifier.fillMaxWidth().padding(NovaPanelMetrics.SpaceMd)) {
        com.papi.nova.ui.compose.NovaActionSurface(
            onClick = { if (state.enabled) onExplain() }, enabled = state.enabled,
            modifier = Modifier.fillMaxWidth().testTag("nova-doctor-ai-explain"),
        ) { color, _ -> Text(stringResource(R.string.nova_doctor_ai_explain), style = novaPanelType.value, color = color) }
        Text(caption, style = novaPanelType.caption, color = LocalNovaComposeColors.current.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.text?.let { explanation ->
        NovaDetailLine(stringResource(R.string.nova_doctor_ai_as_of, state.ageSeconds), "")
        // Text, not annotated/HTML/Markdown content: no links, commands or setting-value parsing.
        listOf(explanation.likelyCause, *explanation.evidence.toTypedArray(),
            *explanation.tryFirst.toTypedArray(), explanation.advancedDetail)
            .filter { it.isNotBlank() }.forEach { NovaDetailLine(it, "") }
    }
}
