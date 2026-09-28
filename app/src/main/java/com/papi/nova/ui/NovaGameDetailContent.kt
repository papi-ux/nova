package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.utils.GameShortcutPinState
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun canPublishArtworkMutationUiForState(state: Lifecycle.State?): Boolean =
    state?.isAtLeast(Lifecycle.State.CREATED) == true
/**
 * @property preflightInFlight A preflight is on the wire and no answer has arrived yet.
 *
 * A null [rawOptimization] used to carry two meanings at once: the host answered and
 * asked for nothing, or nothing has been asked yet. The desktop-Steam guard is armed
 * from that blob, so the two readings are not interchangeable -- changing a launch mode
 * resets the state and reloads, and pressing Play inside that window launched with the
 * guard silently skipped. This separates them, so a launch can wait for an answer
 * instead of assuming one.
 */
data class NovaGameDetailOptimizationState(
    val ai: NovaGameDetailInsightCard? = null,
    val stability: NovaGameDetailInsightCard? = null,
    val profileSummary: NovaLaunchProfileSummary? = null,
    val rawOptimization: JSONObject? = null,
    val reviewRequired: Boolean = false,
    val reviewReason: String = "",
    val preflightInFlight: Boolean = false,
    /** The host capability preflight failed, so Play must retry it before launching. */
    val preflightFailed: Boolean = false,
    val aiRecommendedMode: String = ""
)

internal enum class NovaLaunchPreflightGate {
    READY,
    WAIT,
    RETRY,
}

internal fun NovaGameDetailOptimizationState.launchPreflightGate(): NovaLaunchPreflightGate = when {
    rawOptimization == null && preflightInFlight -> NovaLaunchPreflightGate.WAIT
    preflightFailed -> NovaLaunchPreflightGate.RETRY
    else -> NovaLaunchPreflightGate.READY
}

data class NovaGameDetailInsightCard(
    val label: String,
    val source: String,
    val settings: String,
    val reasoning: String,
    val isWarning: Boolean
)


@Composable
internal fun NovaGameDetailContent(
    uiState: NovaGameDetailUiState,
    launchIntro: String,
    recommendedBadge: String,
    lastPlayedText: String?,
    profilePreferenceLabel: String,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    mangoHudEnabled: Boolean,
    mangoHudStatusLabel: String,
    mangoHudStatusCaption: String,
    mangoHudWarning: Boolean,
    steamLaunchLabel: String,
    steamLaunchModeLabel: String,
    steamLaunchCaption: String,
    optimizationState: NovaGameDetailOptimizationState,
    /** The act-column rows already resolved from the current host catalog, in draw order. */
    playSetupRows: List<NovaPlaySetupRowState>,
    /** Which row the comparison strip is currently explaining. */
    explainedPlaySetupRow: NovaPlaySetupRow,
    /** Which subject Play Setup is showing; the header pill and Y both flip it. */
    playSetupScope: NovaPlaySetupScope,
    onPlaySetupScopeSelected: (NovaPlaySetupScope) -> Unit,
    /** Host-scope rows and plan; empty and null while This Game is showing. */
    hostPlaySetupRows: List<NovaPlaySetupRowState>,
    hostPlaySetupPlan: NovaPlaySetupPlan?,
    /** Play Setup's page stack; open while the panel is. Where It Runs is pushed onto it. */
    playSetupPanel: NovaPanelState,
    playLabel: String,
    launchModeTitle: String,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    coverContentDescription: String,
    modifier: Modifier = Modifier,
    onPrimaryLaunch: () -> Unit,
    onExplainPlaySetupRow: (NovaPlaySetupRow) -> Unit,
    onAdvancePlaySetupRow: (NovaPlaySetupRow) -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    shortcutPinState: GameShortcutPinState,
    shortcutPinRequestPending: Boolean,
    onPinShortcut: () -> Unit,
    artworkState: NovaArtworkStudioState,
    onRefreshArtwork: () -> Unit,
    onSearchArtwork: (String) -> Unit,
    onIdentitySelected: (PolarisArtworkMatchCandidate) -> Unit,
    onIdentityChange: () -> Unit,
    onKindSelected: (String) -> Unit,
    onChoiceSelected: (PolarisArtworkChoice) -> Unit,
    onStudioAction: (NovaArtworkStudioAction) -> Unit,
    onApplyArtwork: (PolarisArtworkMatchCandidate, Map<String, PolarisArtworkChoice>) -> Unit,
    onClearArtwork: () -> Unit,
    onLogoTransform: (Float, Float, Float) -> Unit,
    candidatePreviewLoader: (ImageView, PolarisArtworkMatchCandidate) -> Unit,
    choicePreviewLoader: (ImageView, PolarisArtworkChoice) -> Unit,
    currentArtworkPresentationKey: (String) -> String,
    currentArtworkLoader: (ImageView, String) -> Unit,

    heroAvailable: Boolean = false,
    heroPresentationKey: String = "",
    heroLoader: (ImageView) -> Unit = {},
    heroContentDescription: String = "",
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String = "",
    iconAvailable: Boolean,
    iconPresentationKey: String,
    iconLoader: (ImageView) -> Unit,
    iconContentDescription: String = "",
    coverLoader: (ImageView) -> Unit,
    destination: NovaGameDetailDestination,
    steamDecision: NovaDesktopSteamLaunchDecision?,
    reviewExpanded: Boolean,
    apiClient: PolarisApiClient,
    sourceLabel: String,
    onDestination: (NovaGameDetailDestination) -> Unit,
    onSteamChoice: (NovaSteamLaunchChoice) -> Unit,
    activeSession: NovaLibraryActiveSessionUiState?,
    onResumeSession: () -> Unit,
    onEndSession: () -> Unit,
    onDismissDestination: () -> Unit,
    /** Launch, where focus goes back when Play Setup opened on the launch path closes. */
    playFocusRequester: FocusRequester = remember { FocusRequester() },
    /** Play Setup's own button, where focus goes back when the panel it opened closes. */
    playSetupFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val verticalScroll = rememberScrollState()
    val detailsFocusRequester = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize()) {
        NovaGameDetailOverview(
            uiState = uiState,
            apiClient = apiClient,
            playLabel = playLabel,
            lastPlayedText = lastPlayedText,
            sourceLabel = sourceLabel,
            optimizationState = optimizationState,
            reviewExpanded = reviewExpanded,
            showLaunchModeAction = uiState.showLaunchOptionsButton,
            logoAvailable = logoAvailable,
            logoPresentationKey = logoPresentationKey,
            logoLoader = logoLoader,
            logoContentDescription = logoContentDescription,
            playFocusRequester = playFocusRequester,
            playSetupFocusRequester = playSetupFocusRequester,
            onPrimaryLaunch = onPrimaryLaunch,
            onRetryHighFps = onRetryHighFps,
            onResetProfile = onResetProfile,
            shortcutPinState = shortcutPinState,
            shortcutPinRequestPending = shortcutPinRequestPending,
            onPinShortcut = onPinShortcut,
            onDestination = onDestination,
            activeSession = activeSession,
            onResumeSession = onResumeSession,
            onEndSession = onEndSession,
            // While a destination is open the Overview is scenery: it cannot be walked
            // onto, and its chrome recedes to a texture so the translucent destination
            // reads against artwork rather than against ghosted text.
            chromeAlpha = if (destination == NovaGameDetailDestination.OVERVIEW) {
                1f
            } else {
                NOVA_DETAIL_SCENERY_CHROME_ALPHA
            },
            modifier = if (destination == NovaGameDetailDestination.OVERVIEW) {
                Modifier
            } else {
                Modifier.focusGroup().focusProperties { canFocus = false }
            },
        )

        when (destination) {
            NovaGameDetailDestination.OVERVIEW, NovaGameDetailDestination.PLAY_SETUP -> Unit

            // The studio opens with a Row of weighted Columns, so it needs the window
            // rather than the panel Play Setup uses.
            NovaGameDetailDestination.ARTWORK -> NovaGameDetailFullScreen(
                eyebrow = stringResource(R.string.nova_artwork_studio_title),
                headline = uiState.game.name,
                scrollState = verticalScroll,
                onDismiss = onDismissDestination,
            ) { bodyHeight ->
                NovaArtworkStudio(
                    initiallyExpanded = true,
                    fillsDestination = true,
                    fitHeight = bodyHeight,
                    state = artworkState,
                    initialQuery = uiState.game.name,
                    onRefresh = onRefreshArtwork,
                    onSearch = onSearchArtwork,
                    onIdentitySelected = onIdentitySelected,
                    onChangeIdentity = onIdentityChange,
                    onKindSelected = onKindSelected,
                    onChoiceSelected = onChoiceSelected,
                    onReset = onStudioAction,
                    onApply = onApplyArtwork,
                    onCancel = onStudioAction,
                    onClear = onClearArtwork,
                    onTransform = onLogoTransform,
                    candidatePreviewLoader = candidatePreviewLoader,
                    choicePreviewLoader = choicePreviewLoader,
                    currentArtworkPresentationKey = currentArtworkPresentationKey,
                    currentArtworkLoader = currentArtworkLoader,
                )
            }
        }

        // Play Setup is a wide panel at the end edge, drawn in this window: the game stays in
        // sight beside what is being changed, and its pages push and pop in place. No sheets:
        // the choices are made in the rows, the desktop Steam decision is a page, and so is
        // Where It Runs.
        NovaPlaySetupPanel(
            panel = playSetupPanel,
            onClose = onDismissDestination,
            hints = if (uiState.game.space == null) {
                listOf(
                    NovaControllerHint(
                        key = stringResource(R.string.nova_controller_hint_y),
                        label = stringResource(R.string.nova_play_setup_panel_scope_hint),
                    ),
                )
            } else {
                emptyList()
            },
        ) { page ->
            when (page) {
                is PlaySetupPage.PlayIn -> NovaPlayInPage(page)
                is PlaySetupPage.SteamDecision -> steamDecision?.let { decision ->
                    NovaSteamDecisionPage(decision = decision, onChoice = onSteamChoice)
                }
                is PlaySetupPage.Root -> {
                    // Flipping the scope swaps every row for the other scope's. When the row that held
                    // focus went with them, focus goes to the new scope's first choice, never nowhere
                    // (R7); a flip made on the scope row leaves focus on that row.
                    var rootHoldsFocus by remember { mutableStateOf(false) }
                    var shownScope by remember { mutableStateOf(playSetupScope) }
                    val bodyEntry = remember { FocusRequester() }
                    LaunchedEffect(playSetupScope) {
                        if (shownScope == playSetupScope) return@LaunchedEffect
                        shownScope = playSetupScope
                        withFrameNanos { }
                        if (!rootHoldsFocus && isTop) runCatching { bodyEntry.requestFocus(FocusDirection.Enter) }
                    }
                    Column(modifier = Modifier.fillMaxWidth().onFocusChanged { rootHoldsFocus = it.hasFocus }) {
                        if (uiState.game.space == null) {
                            NovaPlaySetupScopeRow(
                                scope = playSetupScope,
                                onSelected = onPlaySetupScopeSelected,
                                modifier = Modifier.padding(bottom = NovaPanelMetrics.SpaceSm).novaRestorableFocus("scope"),
                            )
                        }
                        // The scroll stays as the fallback for a font scale or an inset that makes
                        // the content genuinely taller than the panel. Measuring outside it is what
                        // lets the body lay itself out to fit in the ordinary case. The page opens on
                        // the body's first choice, where its legend has something to explain, rather
                        // than on the scope row above it: that choice carries novaInitialFocus.
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().focusRequester(bodyEntry).focusGroup()) {
                            val bodyHeight = maxHeight
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .novaFadeAtCut(verticalScroll.canScrollForward)
                                    .verticalScroll(verticalScroll),
                            ) {
                                if (playSetupScope == NovaPlaySetupScope.EVERY_GAME && hostPlaySetupPlan != null) {
                                    // The same four-row shape, absorbing the Polaris Sync sheet's three
                                    // sections. The subject changed; how to read the panel did not.
                                    val consequenceLines =
                                        novaPlaySetupConsequenceLines(bodyHeight, hostPlaySetupRows.size)
                                    NovaPlaySetupBody(
                                        plan = hostPlaySetupPlan,
                                        readTitle = stringResource(R.string.nova_play_setup_host_read_title),
                                        introMaxLines = novaPlaySetupIntroLines(
                                            bodyHeight,
                                            factCount = hostPlaySetupPlan.facts.size,
                                        ),
                                        fitHeight = bodyHeight,
                                        rows = {
                                            NovaHostSetupRowList(
                                                rows = hostPlaySetupRows,
                                                onExplain = onExplainPlaySetupRow,
                                                onAdvance = onAdvancePlaySetupRow,
                                                rowModifier = { row, first ->
                                                    (if (first) Modifier.novaInitialFocus() else Modifier)
                                                        .novaRestorableFocus(row.name)
                                                },
                                            )
                                        },
                                        comparison = {
                                            NovaHostSetupComparison(
                                                rows = hostPlaySetupRows,
                                                explainedRow = explainedPlaySetupRow,
                                                consequenceMaxLines = consequenceLines,
                                            )
                                        },
                                    )
                                } else {
                                    val summary = optimizationState.profileSummary
                                    // Where this game opens is drawn as its own control above the rows it used to
                                    // be one of, so the legend under the rows explains the rows, or the one place
                                    // whose card holds focus, never the places restated as choices.
                                    val destinationsRow = playSetupRows.firstOrNull { it.row == NovaPlaySetupRow.PLAY_IN }
                                    val settingRows = playSetupRows.filter { it.row != NovaPlaySetupRow.PLAY_IN }
                                    // Which destination card the cursor is on, for the legend to describe. By name,
                                    // because the list behind it can change while the cursor stays where it is.
                                    var focusedDestination by remember { mutableStateOf("") }
                                    // Spend the room that is there rather than a number picked in advance:
                                    // each advertised launch control leaves less room for the legend.
                                    // The legend is pinned under the rows, so it is budgeted against the few
                                    // rows kept in view above it rather than against every row the host added.
                                    val consequenceLines = novaPlaySetupPinnedLegendLines(bodyHeight, settingRows.size)
                                    NovaPlaySetupBody(
                                        plan = novaPlaySetupPlan(
                                            // The resolved mode, not the name of the control that sets
                                            // it: this is the one line the column exists to state.
                                            modeLabel = when (uiState.playMode) {
                                                PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> virtualDisplayModeLabel
                                                PolarisGame.MODE_HEADLESS_STREAM -> headlessModeLabel
                                                // A stale host default can be replaced for this launch.
                                                // Name the mode Nova will actually send, not the rejected
                                                // host-default label.
                                                else -> uiState.playModeLabel.ifBlank { headlessModeLabel }
                                            },
                                            lines = listOfNotNull(
                                                summary?.selectedLine
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let(::novaPlaySetupValue),
                                                launchIntro.takeIf { it.isNotBlank() },
                                            ),
                                            summary = summary,
                                            lastSessionKey = stringResource(R.string.nova_play_setup_fact_last_session),
                                            limitedByKey = stringResource(R.string.nova_play_setup_fact_limited_by),
                                            askedKey = stringResource(R.string.nova_play_setup_fact_asked),
                                            profileKey = stringResource(R.string.nova_play_setup_fact_profile),
                                            grantedFormat = stringResource(R.string.nova_play_setup_granted_format),
                                            // A Space game runs in its Space's own session, so neither the host's
                                            // default mode nor its profile is what this launch uses. Stating them
                                            // beside it read as a fallback that was not happening.
                                            hostFacts = if (uiState.runsInSpace) emptyList() else buildList {
                                                if (uiState.hostStreamDisplayModeLabel.isNotBlank()) {
                                                    val safeFallbackDetail = if (uiState.usesSafeHostFallback) {
                                                        buildList {
                                                            add(
                                                                stringResource(
                                                                    R.string.nova_play_setup_host_safe_fallback,
                                                                    uiState.playModeLabel,
                                                                ),
                                                            )
                                                            uiState.hostStreamDisplayModeUnavailableReason
                                                                .takeIf { it.isNotBlank() }
                                                                ?.let(::add)
                                                        }.joinToString(" ")
                                                    } else {
                                                        ""
                                                    }
                                                    add(
                                                        NovaPlaySetupFact(
                                                            key = stringResource(R.string.nova_play_setup_fact_host_default),
                                                            value = uiState.hostStreamDisplayModeLabel,
                                                            detail = when {
                                                                uiState.usesSafeHostFallback -> safeFallbackDetail
                                                                uiState.overridesHostMode -> stringResource(
                                                                    R.string.nova_play_setup_host_overridden,
                                                                )
                                                                // The desktop does not take the host's default, and
                                                                // saying it follows one it ignores is the sentence
                                                                // that sent papi looking for a bug that was not there.
                                                                !uiState.followsHostDefault -> stringResource(
                                                                    R.string.nova_play_setup_host_not_followed,
                                                                    com.papi.nova.api.PolarisStreamDisplayMode.labelForMode(uiState.recommendedMode),
                                                                )
                                                                else -> stringResource(
                                                                    R.string.nova_play_setup_host_followed,
                                                                )
                                                            },
                                                            tone = if (
                                                                uiState.overridesHostMode || uiState.usesSafeHostFallback
                                                            ) {
                                                                NovaPlaySetupTone.WARN
                                                            } else {
                                                                NovaPlaySetupTone.PLAIN
                                                            },
                                                        ),
                                                    )
                                                }
                                                if (uiState.hostProfileLabel.isNotBlank()) {
                                                    add(
                                                        NovaPlaySetupFact(
                                                            key = stringResource(R.string.nova_play_setup_fact_host_profile),
                                                            value = uiState.hostProfileLabel,
                                                        ),
                                                    )
                                                }
                                            },
                                        ),
                                        introMaxLines = novaPlaySetupIntroLines(
                                            bodyHeight,
                                            factCount = summary?.let { 4 } ?: 2,
                                        ),
                                        fitHeight = bodyHeight,
                                        rows = {
                                            // Where this game opens, as the one control that sets it. When a place
                                            // can be chosen, the cards take first focus rather than the first row.
                                            val destinationsFocus = destinationsRow?.options
                                                ?.any { it.enabled && it.onSelect != null } == true
                                            if (destinationsRow != null) {
                                                NovaPlaySetupDestinations(
                                                    title = destinationsRow.stripTitle,
                                                    status = destinationsRow.caption,
                                                    options = destinationsRow.options,
                                                    focusModifier = { option, initial ->
                                                        (if (initial && destinationsFocus) Modifier.novaInitialFocus() else Modifier)
                                                            .novaRestorableFocus("place:${option.label}")
                                                    },
                                                    onFocused = { index ->
                                                        focusedDestination = destinationsRow.options.getOrNull(index)?.label.orEmpty()
                                                        onExplainPlaySetupRow(NovaPlaySetupRow.PLAY_IN)
                                                    },
                                                )
                                            }
                                            // Host-backed rows, drawn in a fixed order. Each changes its own value
                                            // in place, and points the legend at itself on focus or a change, so
                                            // the explanation follows the cursor without being a stop on it.
                                            settingRows.forEachIndexed { index, rowState ->
                                                NovaPlaySetupSettingRow(
                                                    state = rowState,
                                                    onExplain = onExplainPlaySetupRow,
                                                    onAdvance = onAdvancePlaySetupRow,
                                                    modifier = (if (index == 0 && !destinationsFocus) Modifier.novaInitialFocus() else Modifier)
                                                        .novaRestorableFocus(rowState.row.name),
                                                )
                                            }
                                            // After the choices and inside their scroll, so it cannot stand
                                            // between the rows and the legend that explains them.
                                            if (mangoHudEnabled) {
                                                MangoHudPassiveStatus(
                                                    label = mangoHudStatusLabel,
                                                    caption = mangoHudStatusCaption,
                                                    warning = mangoHudWarning
                                                )
                                            }
                                        },
                                        comparison = {
                                            // A legend for whichever row holds focus, not a picker with a
                                            // state of its own. A row that has nothing to compare -- one
                                            // launch mode, or no display planner on this host -- draws
                                            // nothing rather than a strip that repeats the row above it.
                                            // While a destination card holds focus, the legend says what that
                                            // one place means, in full where the card had to cut it. Falling
                                            // back to the first row opened Play Setup on "If you changed where
                                            // it runs" with the cursor on Desktop, and no legend at all opened
                                            // it with the drawer empty.
                                            val place = novaPlaySetupPlaceUnderCursor(
                                                explainedPlaySetupRow,
                                                destinationsRow?.options.orEmpty(),
                                                focusedDestination,
                                            )
                                            val explained = settingRows.firstOrNull { it.row == explainedPlaySetupRow }
                                            if (place != null) {
                                                NovaPlaySetupPlaceLegend(
                                                    title = stringResource(R.string.nova_play_setup_place_legend),
                                                    place = place,
                                                    consequenceMaxLines = consequenceLines,
                                                )
                                            } else if (explained != null && explained.options.size > 1) {
                                                NovaPlaySetupComparison(
                                                    title = explained.stripTitle,
                                                    options = explained.options,
                                                    consequenceMaxLines = if (explained.options.size > explained.optionsPerRow) 1 else consequenceLines,
                                                    perRow = explained.optionsPerRow,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

/** Enough to read as texture behind a translucent destination, not as text. */
private const val NOVA_DETAIL_SCENERY_CHROME_ALPHA = 0.16f

/**
 * @brief What choosing this tuning preference would mean.
 *
 * The picker these came from listed four names with nothing to choose between them. A
 * name is only a choice if you already know what it does.
 */
/**
 * Resource ids rather than resolved strings, so the row builder in the activity can reach
 * them. These were @Composable, which put them out of reach of the only caller left.
 */
internal fun novaProfilePreferenceConsequenceRes(value: String): Int =
    when (value.trim().lowercase()) {
        "quality" -> R.string.nova_play_setup_pref_quality
        "stability" -> R.string.nova_play_setup_pref_stability
        "high_fps" -> R.string.nova_play_setup_pref_high_fps
        else -> R.string.nova_play_setup_pref_auto
    }

/**
 * What the host actually did with a saved tuning ask. Auto asks for nothing and
 * High FPS is binding client-side, so only quality/stability have an outcome the
 * host owns -- and a host that predates preference_applied gets Default, never a
 * fabricated decline read off a missing field.
 */
internal sealed class NovaTuningOutcome {
    object Default : NovaTuningOutcome()
    object Applied : NovaTuningOutcome()
    data class Declined(val reason: String) : NovaTuningOutcome()
}

internal fun novaTuningOutcome(optimization: JSONObject?, preference: String): NovaTuningOutcome {
    if (optimization == null) return NovaTuningOutcome.Default
    val normalized = preference.trim().lowercase()
    if (normalized == "auto" || normalized == "high_fps") return NovaTuningOutcome.Default
    val profileState = optimization.optJSONObject("profile_state")
    val appliedKnown = optimization.has("preference_applied") ||
        profileState?.has("preference_applied") == true
    if (!appliedKnown) return NovaTuningOutcome.Default
    val applied = optimization.optBoolean(
        "preference_applied",
        profileState?.optBoolean("preference_applied", false) ?: false
    )
    if (applied) return NovaTuningOutcome.Applied
    val reason = optimization.optString(
        "preference_blocked_reason",
        profileState?.optString("preference_blocked_reason", "") ?: ""
    )
    return NovaTuningOutcome.Declined(if (reason.isBlank()) "" else novaLaunchIssueLabel(reason))
}

/** The same, for the two ways Steam can be handed the game. */
internal fun novaSteamLaunchConsequenceRes(value: String): Int =
    // "big-picture" with the hyphen is what SteamLaunchContract.normalizeMode returns, and
    // it was the one spelling missing here -- so Big Picture described itself as Direct.
    // Invisible while the two were never on screen together, which they now always are.
    when (value.trim().lowercase()) {
        "big-picture", "big_picture", "bigpicture", "gamepadui" ->
            R.string.nova_play_setup_steam_big_picture
        else -> R.string.nova_play_setup_steam_direct
    }

@Composable
internal fun LaunchProfilePrimaryNotice(
    summary: NovaLaunchProfileSummary,
    detailsFocusRequester: FocusRequester,
    playFocusRequester: FocusRequester
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val notice = summary.limitingLine.takeIf { it.isNotBlank() }
        ?: summary.profileLabel.takeIf { it.isNotBlank() }?.let { "Profile: $it" }
        ?: summary.reasonLine.takeIf { it.isNotBlank() }
        ?: summary.freshnessLine.takeIf { it.isNotBlank() }
        ?: ""
    val isHealthy = summary.noticeTone == NovaLaunchProfileNoticeTone.HEALTHY
    val toneColor = if (isHealthy) colorResource(R.color.nova_success) else colors.warning
    val badgeLabel = if (isHealthy) summary.noticeLabel else "Heads up"
    val hasNoticeContent = listOf(
        notice,
        summary.noticeDetail,
        summary.noticeRecommendation
    ).any { it.isNotBlank() }
    val hasExpandableDetails = summary.noticeDetail.isNotBlank() || summary.noticeRecommendation.isNotBlank()
    var noticeExpanded by remember(summary.noticeDetail, summary.noticeRecommendation) { mutableStateOf(false) }
    if (!hasNoticeContent) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaGameDetailInset)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(toneColor.copy(alpha = 0.14f))
            .border(1.dp, toneColor.copy(alpha = 0.52f), RoundedCornerShape(NovaRadius.hero))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NovaBadge(
                text = badgeLabel,
                // A badge is a surface, not media: translucent control over a ground
                // already tinted with this tone put amber on amber. Opaque tone, with
                // ink picked from the tone itself, reads in every theme.
                color = if (toneColor.luminance() > 0.5f) Color.Black else Color.White,
                backgroundColor = toneColor,
                borderColor = Color.Transparent,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = notice.ifBlank { "Launch profile adjusted" },
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasExpandableDetails) {
                NovaActionButton(
                    text = if (noticeExpanded) "Hide details" else "More details",
                    onClick = { noticeExpanded = !noticeExpanded },
                    modifier = Modifier
                        .width(104.dp)
                        .focusRequester(detailsFocusRequester)
                        .focusProperties { down = playFocusRequester },
                    contentDescription = if (noticeExpanded) {
                        "Hide launch profile details"
                    } else {
                        "Show launch profile details"
                    },
                    stateDescription = if (noticeExpanded) "Expanded" else "Collapsed",
                    minHeight = 32.dp,
                    cornerRadius = NovaRadius.hero,
                    fontSize = 10.sp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        if (noticeExpanded && summary.noticeDetail.isNotBlank()) {
            Text(
                text = summary.noticeDetail,
                color = colors.textPrimary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
        if (noticeExpanded && summary.noticeRecommendation.isNotBlank()) {
            Text(
                text = summary.noticeRecommendation,
                color = toneColor,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun MangoHudPassiveStatus(
    label: String,
    caption: String,
    warning: Boolean
) {
    // A readout, not an action: same row, no chevron to imply otherwise.
    NovaSteamChoiceRow(
        label = label,
        caption = caption,
        enabled = !warning,
    )
}
