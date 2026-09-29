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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import com.papi.nova.ui.panel.NovaOption
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
    val onLiveTuning: () -> Unit = {},
    val onSyncStatus: () -> Unit = {},
    val onToggleAdvanced: () -> Unit = {},
    val onClearGameProfile: () -> Unit = {},
    val onMangoHud: () -> Unit = {},
    val onProfilePreference: (String) -> Unit = {},
    val onQuickKey: (NovaQuickMenuActionId) -> Unit = {},
    val onOverlayAction: (NovaQuickMenuActionId) -> Unit = {},
    val onHudModeSelect: (NovaHudMode) -> Unit = {},
    /** True while a row that changes the HUD has focus, so the HUD shows at full strength. */
    val onHudPreview: (Boolean) -> Unit = {},
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
            NovaQuickMenuActionId.LIVE_TUNING -> onLiveTuning()
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
            NovaQuickMenuActionId.KEYBOARD,
            NovaQuickMenuActionId.PLAYERS -> onControlAction(action.id)
            NovaQuickMenuActionId.PASTE_CLIPBOARD,
            NovaQuickMenuActionId.ROTATE_SCREEN,
            NovaQuickMenuActionId.MORE_KEYS,
            NovaQuickMenuActionId.MORE_CONTROLS -> onSessionAction(action.id)
        }
    }
}

/** Rows that push a page of their own, and so carry the page mark. */
private val OpensPage = setOf(
    NovaQuickMenuActionId.MOUSE_MODE,
    NovaQuickMenuActionId.MORE_KEYS,
    NovaQuickMenuActionId.MORE_CONTROLS,
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
    doctorSlot: NovaQuickMenuDoctorSlot = remember { NovaQuickMenuDoctorSlot() },
) {
    val ui = state.collectAsState()
    val endSplit = rememberNovaSplitConfirmState()
    val overlaysTitle = stringResource(R.string.nova_quick_menu_overlays)
    val controlsTitle = stringResource(R.string.nova_quick_menu_controls)
    val sessionTitle = stringResource(R.string.nova_quick_menu_session)
    val quickKeysTitle = stringResource(R.string.nova_quick_menu_quick_keys)
    val showPinnedKeys by ui.slice { it.pinnedQuickKeys.isNotEmpty() }
    val showDiagnosis by ui.slice { it.diagnosis.visible }
    val informationalNow by ui.slice { it.diagnosis.informational }
    val diagnosisFromHost by ui.slice { it.diagnosis.fromHost }
    // The card keeps one slot for the whole opening, whatever the live verdict does next.
    val diagnosisInformational = doctorSlot.ranksLast(
        informational = informationalNow,
        reading = showDiagnosis && diagnosisFromHost,
    )
    val showReceipt by ui.slice { it.doctorReceiptAction.visible }
    val advancedExpanded by ui.slice { it.advancedExpanded }
    val showReport by ui.slice { it.advancedExpanded && it.postSessionReport.visible }

    // Reopened in the same stream, the page comes back where it was left: the row that had focus,
    // scrolled as it was (in-game #16). It opened on Close at the top every time.
    val sections = rememberScrollState(place?.scroll ?: 0)
    place?.focusKey?.let { novaInitialFocusAt(it) }
    LaunchedEffect(sections, place) {
        if (place != null) snapshotFlow { sections.value }.collect { place.scroll = it }
    }
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
                    .novaScrollEdgeFade(sections)
                    .verticalScroll(sections)
                    .padding(vertical = NovaPanelMetrics.SpaceSm),
                verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
            ) {
                // The page opens on Close, its safe action (R7); it opened on this strip, which acts
                // on nothing, so the first A did nothing and the ring sat on a status card.
                NovaQuickMenuSessionStrip(ui, Modifier)
                NovaQuickMenuLiveTuningRow(ui, callbacks)
                // The keys a handheld cannot press any other way stay one reach from the top; the
                // full grid lives further down with the rest of the sections.
                if (showPinnedKeys) NovaQuickKeys(ui, { it.pinnedQuickKeys }, callbacks)
                // The strip is a one-line verdict. What explains it, the Doctor's reading and what
                // Auto is running, comes next instead of three screens down. A reading that only
                // informs, with nothing to run and nothing the strip warns about, ranks last (N28).
                // Which of the two it is was decided once for this opening: see NovaQuickMenuDoctorSlot.
                if (showDiagnosis && !diagnosisInformational) NovaQuickMenuDiagnosisCard(ui, callbacks, doctorSlot)
                if (showReceipt) NovaQuickMenuInfoCard(ui, { it.doctorReceiptAction }, callbacks)
                NovaQuickMenuStabilityCard(ui, callbacks)

                // Overlays first because the HUD switch is the frequent tap.
                NovaSectionLabel(overlaysTitle)
                NovaQuickMenuOverlayRows(ui, callbacks, hudPreview)
                NovaQuickMenuMenuOpacityControl(ui, callbacks)
                NovaQuickMenuHudOpacityControl(ui, callbacks, hudPreview)

                NovaSectionLabel(controlsTitle)
                NovaQuickMenuRows(ui, { it.controlRows }, callbacks)

                NovaSectionLabel(sessionTitle)
                NovaQuickMenuRows(ui, { rows -> rows.sessionRows.filter { it.visible } }, callbacks)

                // The full grid last of the daily sections, since its top three are pinned above.
                NovaSectionLabel(quickKeysTitle)
                NovaQuickKeys(ui, { it.gridQuickKeys }, callbacks)

                if (showDiagnosis && diagnosisInformational) NovaQuickMenuDiagnosisCard(ui, callbacks, doctorSlot)

                NovaQuickMenuInfoCard(ui, { it.sync }, callbacks)
                NovaQuickMenuInfoCard(ui, { it.advancedToggle }, callbacks)
                if (advancedExpanded) {
                    NovaQuickMenuRows(ui, { it.advancedRows }, callbacks)
                    // Observational history from the host; it explains Auto's fallbacks but never
                    // changes a launch, so it lives with the other diagnostics.
                    if (showReport) NovaQuickMenuPostSessionReportCard(ui)
                }
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
    internal var scroll: Int = 0
}

private val LocalNovaQuickMenuPlace = staticCompositionLocalOf<NovaQuickMenuPlace?> { null }

/**
 * Where the Doctor card sits for one opening of the Command Center: under the session strip, or
 * after the sections a player adjusts when its reading only informs (N28).
 *
 * The live verdict can flip every second or two (a control channel observation, then PyroWave
 * advice, then sustained pressure), and each flip moved the card between the two slots: every
 * row between them shifted by a card's height under the player, and a card that had focus was
 * rebuilt in the other slot without it. The slot is taken from the first reading this opening
 * shows, or from where the card was when it first took focus, and kept until the panel closes.
 * The next opening picks again. The host holds one per opening; pages pushed on top of the root
 * and popped again find the card where they left it.
 */
class NovaQuickMenuDoctorSlot {
    private var pinned: Boolean? = null
    private var shown = false

    /**
     * Whether the card ranks last. The first call with a [reading] pins [informational]; before
     * that the card goes where the verdict puts it, since the placeholder has nothing to act on.
     */
    fun ranksLast(informational: Boolean, reading: Boolean): Boolean {
        if (pinned == null && reading) pinned = informational
        return (pinned ?: informational).also { shown = it }
    }

    /** The card took focus where it is: it stays there, even before a reading pins it. */
    fun hold() {
        if (pinned == null) pinned = shown
    }
}

/**
 * [NovaPageScope.novaRestorableFocus], also recorded as where the Command Center next opens. A
 * button whose A acts at once (Disconnect) or arms a split (End Session, Alt + F4) passes
 * [reopenHere] false: the next opening starts on Close instead of one A from ending something.
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
    slot: NovaQuickMenuDoctorSlot,
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
    // Built once per diagnosis, not once per recomposition of the page.
    val detail = remember(diagnosis, context) {
        val classification = diagnosis.classification.takeIf { it in setOf("HOST", "NET", "CLIENT") }
        buildList {
            classification?.let(::add)
            diagnosis.tryFirst.takeIf { it.isNotBlank() }?.let { add(context.getString(R.string.nova_cc_doctor_try_first, it)) }
            // Confidence only means something next to the evidence it grades.
            diagnosis.evidenceHighlight.takeIf { it.isNotBlank() }?.let { evidence ->
                add(context.getString(R.string.nova_cc_doctor_evidence, evidence))
                diagnosis.confidence.takeIf { it.isNotBlank() }?.let { add(context.getString(R.string.nova_cc_doctor_confidence, it)) }
            }
        }.joinToString(" · ")
    }
    val diagnoseTitle = stringResource(R.string.nova_quick_menu_diagnose_stream)
    val aiSupportingLine = diagnosis.aiExplanation.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.nova_quick_menu_doctor_ai_explanation, it)
    }
    val sourceSupportingLine = diagnosis.informationalSource
        .takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.nova_cc_doctor_source, it) }
    val doesLine = (diagnosis.actionLabel.takeIf { diagnosis.actionExecutable && it.isNotBlank() } ?: capabilityLabel)
        .takeIf { diagnosis.available }
    val supportingLine = listOfNotNull(doesLine, aiSupportingLine, sourceSupportingLine).joinToString("\n")
    // The finding is the title and what A does is the line under it, so "Recheck" no longer
    // shows up as title, chip, and button at once.
    val action = remember(diagnosis, detail, copiedLabel, diagnoseTitle) {
        NovaQuickMenuAction(
            id = NovaQuickMenuActionId.DIAGNOSE_STREAM,
            label = diagnosis.likelyCause.trim().trimEnd('.').ifBlank { diagnoseTitle },
            caption = detail,
            chip = if (diagnosis.copied) NovaQuickMenuChip(copiedLabel, NovaQuickMenuTone.INFO) else null,
            enabled = diagnosis.available
        )
    }
    NovaQuickMenuCard(
        action = action,
        modifier = novaPlaceFocus(NovaQuickMenuActionId.DIAGNOSE_STREAM).onFocusChanged { if (it.hasFocus) slot.hold() },
        supportingLine = supportingLine,
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
    val healthTone by ui.slice { it.healthTone }
    val colors = LocalNovaComposeColors.current
    val haptics = LocalHapticFeedback.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    var focused by remember { mutableStateOf(false) }
    val description = listOf(sessionMode.label, sessionDetail, healthSummary, healthDetail)
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
            Text(text = healthSummary, style = type.rowTitle, color = toneColor(healthTone))
            if (sessionDetail.isNotBlank()) {
                Text(text = sessionDetail, style = type.caption, color = colors.textSecondary)
            }
            if (healthDetail.isNotBlank()) {
                Text(text = healthDetail, style = type.caption, color = colors.textSecondary)
            }
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

/**
 * The Stream card: the profile Nova launches this game with next, as a value that changes in
 * its own row. The current profile carries the check, never a filled button.
 */
@Composable
private fun NovaPageScope.NovaQuickMenuStabilityCard(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val stability by ui.slice { it.stability }
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val options = remember(stability.profileOptions) {
        stability.profileOptions.map { NovaOption(it.value, it.label) }
    }
    val current = stability.profileOptions.firstOrNull { it.selected }?.value ?: options.firstOrNull()?.value.orEmpty()
    val enabled = stability.profileOptions.all { it.enabled }

    // The card's text is inset as a row's; the Launch Preset row brings its own inset, so it sits
    // at the card's edges rather than a second inset deeper than every other row.
    val inset = Modifier.padding(horizontal = NovaPanelMetrics.SpaceMd)
    NovaQuickMenuStaticCard(contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceMd)) {
        Box(inset) {
            NovaQuickMenuTitleAndChip(
                title = { Text(text = stability.title, style = type.rowTitle, fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                chip = stability.chip,
            )
        }
        if (stability.caption.isNotBlank()) {
            Text(text = stability.caption, style = type.caption, color = colors.textPrimary, modifier = inset)
        }
        Text(text = stability.targetSummary, style = type.caption, color = colors.textSecondary, modifier = inset)
        if (options.isNotEmpty()) {
            NovaValueRow(
                title = stability.profileTitle,
                caption = stability.profileCaption,
                options = options,
                current = current,
                onChange = callbacks.onProfilePreference,
                enabled = enabled,
                modifier = novaPlaceFocus("launch-preset"),
            )
        }
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
    NovaQuickMenuCard(action = action, callbacks = callbacks, modifier = novaPlaceFocus(action.id))
}

@Composable
private fun NovaQuickMenuCard(
    action: NovaQuickMenuAction,
    callbacks: NovaQuickMenuCallbacks,
    modifier: Modifier = Modifier,
    supportingLine: String = "",
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    NovaQuickMenuClickableSurface(
        enabled = action.enabled,
        onClick = { callbacks.perform(action) },
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(NovaPanelMetrics.SpaceMd),
        contentDescription = listOfNotNull(action.label, action.chip?.label, supportingLine, action.caption)
            .filter { it.isNotBlank() }
            .joinToString(". "),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            NovaQuickMenuTitleAndChip(
                title = { Text(text = action.label, style = type.rowTitle, fontWeight = FontWeight.SemiBold, color = colors.textPrimary) },
                chip = action.chip,
            )
            if (supportingLine.isNotBlank()) {
                Text(text = supportingLine, style = type.caption, fontWeight = FontWeight.SemiBold, color = colors.accent)
            }
            if (action.caption.isNotBlank()) {
                Text(text = action.caption, style = type.caption, color = colors.textSecondary)
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
    val rows by ui.slice { it.overlayRows }
    rows.forEach { row ->
        NovaQuickMenuRow(row, callbacks, novaPlaceFocus(row.id))
        if (row.id == NovaQuickMenuActionId.NOVA_HUD) {
            NovaQuickMenuHudModePicker(
                ui,
                callbacks,
                novaPlaceFocus("hud-mode").onFocusChanged { hudPreview.update("hud-mode", it.hasFocus) },
            )
        }
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
 * The HUD's own corner is the top start, where the Command Center's edge panel lies: a HUD that was
 * never dragged is under the panel, and its rows say so rather than change something out of sight.
 * A portrait sheet leaves the top clear. A dragged HUD is shown at full strength wherever it is.
 */
@Composable
private fun novaHudUnderPanel(atItsCorner: Boolean, enabled: Boolean): Boolean =
    enabled && atItsCorner && com.papi.nova.ui.panel.LocalNovaPanelFillsHeight.current

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
                modifier = novaPlaceFocus(row.id).testTag("nova-cc-clear-game-profile"),
            )
        } else {
            // A row that pushed a page is where focus lands when that page pops.
            NovaQuickMenuRow(row, callbacks, novaPlaceFocus(row.id), opens = row.id in OpensPage)
        }
    }
}

/**
 * Live Tuning is a host setting: switching it rewrites polaris.conf for every device that streams
 * from the host, so one A never changes it (M11). It splits in its row as End Session does, into
 * Stay and Turn Off (or Turn On), with what it changes written under the pair. At rest it is a row
 * among rows, its state on the chip at its end, in the rows' own look rather than End Session's
 * red: it changes a setting and ends nothing, so its confirm takes the accent.
 */
@Composable
private fun NovaPageScope.NovaQuickMenuLiveTuningRow(
    ui: State<NovaQuickMenuUiState>,
    callbacks: NovaQuickMenuCallbacks,
) {
    val row by ui.slice { it.liveTuningAction }
    val on = row.chip?.tone == NovaQuickMenuTone.ACTIVE
    NovaSplitConfirm(
        label = row.label,
        confirmLabel = stringResource(if (on) R.string.nova_cc_live_tuning_turn_off else R.string.nova_cc_live_tuning_turn_on),
        onConfirm = { if (row.enabled) callbacks.perform(row) },
        consequence = stringResource(R.string.nova_cc_live_tuning_consequence),
        icon = R.drawable.ic_settings,
        shape = NovaSplitShape.Row,
        enabled = row.enabled,
        caption = row.caption,
        trailing = row.chip?.let { chip -> { NovaQuickMenuChipView(chip) } },
        tone = NovaSplitTone.Neutral,
        modifier = novaPlaceFocus(row.id).testTag("nova-cc-live-tuning"),
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
                            if (action.caption.isNotBlank()) {
                                Text(text = action.caption, style = type.caption, color = colors.textSecondary)
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
                novaHudUnderPanel(hudOpacity.atItsCorner, hudOpacity.enabled) -> R.string.nova_cc_hud_opacity_under_panel
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
                novaHudUnderPanel(hudMode.atItsCorner, hudMode.enabled) -> R.string.nova_cc_hud_mode_under_panel
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(NovaPanelMetrics.SpaceMd),
    contentDescription: String,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(NovaRadius.row)

    Box(
        modifier = modifier
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .alpha(if (enabled) 1f else NovaPanelMetrics.DisabledAlpha)
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            .semantics { this.contentDescription = contentDescription }
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            .novaClickable(enabled = enabled, role = Role.Button) {
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
