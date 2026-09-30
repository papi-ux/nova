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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import com.papi.nova.ui.panel.NovaPanelWidth
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
    /** Why it failed, in the host's words where it gave some, for the status line to say. */
    val preflightMessage: String? = null,
    val aiRecommendedMode: String = "",
    /**
     * The plan on screen when a recheck began, shown dimmed until the host answers: with no plan
     * at all the page said "Profile / 120 FPS" and Launch lost its preset (in-game smoke #18).
     */
    val lastPlan: NovaLaunchProfileSummary? = null,
    /** Typed provenance; rendered copy is never launch or recovery authority. */
    val streamSource: com.papi.nova.manager.NovaStreamSourceLine? = null,
)

/** A recheck is in flight and the plan on screen is the last one, kept until the host answers. */
internal val NovaGameDetailOptimizationState.showsLastPlan: Boolean
    get() = preflightInFlight && rawOptimization == null && lastPlan != null && profileSummary === lastPlan

/** The state to show while a recheck is in flight: the settled plan, else the last one kept. */
internal fun NovaGameDetailOptimizationState.withLastPlanWhileChecking(): NovaGameDetailOptimizationState =
    if (profileSummary == null && preflightInFlight && rawOptimization == null && lastPlan != null) {
        copy(profileSummary = lastPlan)
    } else {
        this
    }

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
    /** This game's rows, resolved from the current host catalog, in draw order. */
    playSetupRows: List<NovaPlaySetupRowState>,
    /** Which subject Play Setup is showing; the header pill and Y both flip it. */
    playSetupScope: NovaPlaySetupScope,
    onPlaySetupScopeSelected: (NovaPlaySetupScope) -> Unit,
    /** Host-scope rows and plan; empty and null while This Game is showing. */
    hostPlaySetupRows: List<NovaPlaySetupRowState>,
    hostPlaySetupPlan: NovaPlaySetupPlan?,
    /** The host scope's last result, said under its plan until the next one; null says nothing. */
    hostPlaySetupNotice: NovaPolarisSyncNotice? = null,
    hostCopyRecovery: NovaHostCopyRecovery? = null,
    /**
     * What PyroWave asks for past the bitrate setting for this launch's plan, in Mbps, or 0: the
     * verdict the codec preview reads, so What Will Happen and the status line say it too (#10).
     */
    playSetupBitrateShortfallMbps: Int = 0,
    /** Play Setup's page stack; open while the panel is. Where It Runs is pushed onto it. */
    playSetupPanel: NovaPanelState,
    playLabel: String,
    launchModeTitle: String,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    coverContentDescription: String,
    modifier: Modifier = Modifier,
    onPrimaryLaunch: () -> Unit,
    onAdvancePlaySetupRow: (NovaPlaySetupRow) -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    shortcutPinState: GameShortcutPinState,
    shortcutPinRequestPending: Boolean,
    onPinShortcut: () -> Unit,
    /** What pinning just came to, said in the pin button's label. */
    shortcutPinResult: String? = null,
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
    launchBlockedReason: String? = null,
) {
    val verticalScroll = rememberScrollState()
    val detailsFocusRequester = remember { FocusRequester() }
    // B from Artwork Studio put focus on nothing, and the first Right then found the How Long To
    // Beat chip. Closing the studio hands focus back to the button that opened it (R7).
    val artworkFocusRequester = remember { FocusRequester() }
    var artworkHoldsFocus by remember { mutableStateOf(false) }
    var shownDestination by remember { mutableStateOf(destination) }
    LaunchedEffect(destination) {
        val from = shownDestination
        shownDestination = destination
        if (from == NovaGameDetailDestination.ARTWORK && destination == NovaGameDetailDestination.OVERVIEW) {
            // The Overview turns focusable again a frame or more after this, so ask a frame at a
            // time until the button holds focus, as the first focus does for Launch. A request that
            // did not throw ended the loop before, even while the Overview could not take it (M5).
            repeat(ARTWORK_RETURN_FOCUS_FRAMES) {
                withFrameNanos { }
                runCatching { artworkFocusRequester.requestFocus() }
                withFrameNanos { }
                if (artworkHoldsFocus) return@LaunchedEffect
            }
        }
    }

    // This launch's plan held back by the bitrate setting, said where the plan is read (#10).
    val bitrateLimit = if (playSetupBitrateShortfallMbps > 0) stringResource(R.string.nova_play_setup_limited_by_bitrate) else ""

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Where Play Setup's panel covers the page, the page's chrome is not drawn at all: at 16% it
        // still read through the panel's tiles and the room under them. Beside the panel it stays
        // a texture.
        val panelCoversChrome = destination == NovaGameDetailDestination.PLAY_SETUP &&
            !NovaPanelMetrics.usesSheet(maxWidth, maxHeight)
        NovaGameDetailOverview(
            uiState = uiState,
            apiClient = apiClient,
            planLimit = bitrateLimit,
            playLabel = playLabel,
            launchBlockedReason = launchBlockedReason,
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
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            artworkFocusRequester = artworkFocusRequester,
            onArtworkFocus = { artworkHoldsFocus = it },
            shortcutPinState = shortcutPinState,
            shortcutPinRequestPending = shortcutPinRequestPending,
            onPinShortcut = onPinShortcut,
            shortcutPinResult = shortcutPinResult,
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
            chromeClipEnd = if (panelCoversChrome) {
                NovaPanelMetrics.panelWidth(NovaPanelWidth.Wide, maxWidth)
            } else {
                0.dp
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
        // sight beside what is being changed, and its pages push and pop in place. No sheets and
        // no legend: the choices are made in the rows and on their pages, and the plan card above
        // them says what the launch will do.
        val everyGame = playSetupScope == NovaPlaySetupScope.EVERY_GAME && hostPlaySetupPlan != null
        val setHereNote = stringResource(R.string.nova_play_setup_set_for_game)
        val summary = optimizationState.profileSummary
        val gamePlan = novaPlaySetupPlan(
            // The resolved mode, not the name of the control that sets it: this is the one line
            // the plan exists to state.
            modeLabel = when (uiState.playMode) {
                PolarisGame.MODE_HOST_VIRTUAL_DISPLAY -> virtualDisplayModeLabel
                PolarisGame.MODE_HEADLESS_STREAM -> headlessModeLabel
                // A stale host default can be replaced for this launch. Name the mode Nova will
                // actually send, not the rejected host-default label.
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
            // A Space game runs in its Space's own session, so neither the host's default mode
            // nor its profile is what this launch uses. Stating them beside it read as a fallback
            // that was not happening.
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
                                uiState.overridesHostMode -> stringResource(R.string.nova_play_setup_host_overridden)
                                // The desktop does not take the host's default, and saying it
                                // follows one it ignores is the sentence that sent papi looking
                                // for a bug that was not there.
                                !uiState.followsHostDefault -> stringResource(
                                    R.string.nova_play_setup_host_not_followed,
                                    com.papi.nova.api.PolarisStreamDisplayMode.labelForMode(uiState.recommendedMode),
                                )
                                else -> stringResource(R.string.nova_play_setup_host_followed)
                            },
                            tone = if (uiState.overridesHostMode || uiState.usesSafeHostFallback) {
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
        ).let { plan ->
            // MangoHUD, when it is on, is a readout of what this launch carries, so it is a fact of
            // the plan on its What Will Happen page rather than a row among the choices.
            if (mangoHudEnabled) {
                plan.copy(
                    facts = plan.facts + NovaPlaySetupFact(
                        key = stringResource(R.string.nova_play_setup_fact_overlay),
                        value = mangoHudStatusLabel,
                        detail = mangoHudStatusCaption,
                        tone = if (mangoHudWarning) NovaPlaySetupTone.WARN else NovaPlaySetupTone.PLAIN,
                    ),
                )
            } else {
                plan
            }
        }.let { plan ->
            // The verdict the codec preview gave, stated first among the facts behind the plan.
            if (bitrateLimit.isBlank()) {
                plan
            } else {
                plan.copy(
                    facts = listOf(
                        NovaPlaySetupFact(
                            key = stringResource(R.string.nova_play_setup_fact_limited_by),
                            value = stringResource(R.string.nova_play_setup_limit_bitrate),
                            detail = stringResource(R.string.nova_play_setup_resolution_pyrowave_need, playSetupBitrateShortfallMbps),
                            tone = NovaPlaySetupTone.WARN,
                        ),
                    ) + plan.facts,
                )
            }
        }
        val shownPlan = hostPlaySetupPlan?.takeIf { everyGame } ?: gamePlan
        val planLimit = if (everyGame) "" else bitrateLimit
        val planTitle = stringResource(
            if (everyGame) R.string.nova_play_setup_host_read_title else R.string.nova_play_setup_what_will_happen,
        )
        // Where the game opens joins the mode when the host has Spaces to open it in.
        val place = playSetupRows.firstOrNull { it.row == NovaPlaySetupRow.PLAY_IN }?.value
        val planValue = if (everyGame || place.isNullOrBlank()) {
            shownPlan.mode
        } else {
            listOf(place, shownPlan.mode).joinToString(" · ")
        }
        val planLine = novaPlaySetupPlanSummary(shownPlan).orEmpty()
        val baseLine = shownPlan.lines.firstOrNull().orEmpty()
        // This game's last plan, kept while the host rechecks it, reads dimmed (#18).
        val checking = !everyGame && optimizationState.showsLastPlan
        // The card a page pins where the root had it: the plan, or, while an option other than the
        // current one has focus, what choosing it would do.
        val pinnedCard: @Composable (NovaPlaySetupOption?) -> Unit = { focused ->
            val preview = focused?.takeIf { !it.current && it.enabled }?.preview
            if (preview != null) {
                NovaPlaySetupPlanCard(
                    title = stringResource(R.string.nova_play_setup_if_you_choose, focused.label),
                    value = planValue,
                    line = novaPlaySetupPreviewLine(baseLine, preview),
                    accentPart = preview.changed,
                    limit = preview.limit,
                )
            } else {
                NovaPlaySetupPlanCard(title = planTitle, value = planValue, line = planLine, limit = planLimit, checking = checking)
            }
        }
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
            // The scope pill, in the header on every page so it never moves; a Space game has one
            // subject only.
            headerEnd = if (uiState.game.space == null) {
                { NovaPlaySetupScopePill(scope = playSetupScope, onSelected = onPlaySetupScopeSelected) }
            } else {
                null
            },
        ) { page ->
            when (page) {
                is PlaySetupPage.PlayIn -> NovaPlayInPage(
                    page,
                    card = { choice ->
                        // A place or mode other than the current one, under the cursor: the card
                        // says what choosing it would run, in that choice's own line, as an option
                        // page's card builds its line for the option. The plan's numbers are the
                        // current mode's, which the focused one may not run.
                        if (choice != null) {
                            NovaPlaySetupPlanCard(
                                title = stringResource(R.string.nova_play_setup_if_you_choose, choice.label),
                                value = choice.label,
                                line = choice.line,
                            )
                        } else {
                            pinnedCard(null)
                        }
                    },
                )
                is PlaySetupPage.Options -> NovaPlaySetupOptionsPage(page, card = pinnedCard)
                is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page, hostCopyRecovery.takeUnless { everyGame })
                is PlaySetupPage.SteamDecision -> steamDecision?.let { decision ->
                    NovaSteamDecisionPage(decision = decision, onChoice = onSteamChoice)
                }
                is PlaySetupPage.Root -> {
                    // The rows of the scope on screen: the host's for Every Game once its plan has
                    // come, and the root's in a fixed order for This Game. A row that opens a page
                    // carries ›; a row that changes in place steps on Left, Right and A. Y swaps them,
                    // and focus stays with the rows (N19).
                    NovaPlaySetupRootPage(
                        scope = if (everyGame) NovaPlaySetupScope.EVERY_GAME else NovaPlaySetupScope.THIS_GAME,
                        rows = if (everyGame) hostPlaySetupRows else novaPlaySetupRootRows(playSetupRows),
                        onAdvance = onAdvancePlaySetupRow,
                        setHereNote = setHereNote.takeUnless { everyGame },
                        // The pill's switch as a last row a remote can reach (C01); a Space game
                        // has one subject only.
                        onSwitchScope = if (uiState.game.space == null) {
                            {
                                onPlaySetupScopeSelected(
                                    if (playSetupScope == NovaPlaySetupScope.EVERY_GAME) {
                                        NovaPlaySetupScope.THIS_GAME
                                    } else {
                                        NovaPlaySetupScope.EVERY_GAME
                                    },
                                )
                            }
                        } else {
                            null
                        },
                        card = {
                            NovaPlaySetupPlanCard(
                                title = planTitle,
                                value = planValue,
                                line = planLine,
                                // The plan opens whole on its own page, and focus comes back here (R7).
                                onOpen = { if (isTop) playSetupPanel.push(PlaySetupPage.Plan(planTitle, shownPlan)) },
                                limit = planLimit,
                                checking = checking,
                                modifier = Modifier.novaRestorableFocus("plan"),
                            )
                            hostCopyRecovery?.takeUnless { everyGame }?.let { NovaHostCopyRecoveryRow(it) }
                            // Every Game's last result, in place under its plan and announced, as
                            // Polaris Sync says it in the library: it floated in a snackbar (X2).
                            hostPlaySetupNotice?.takeIf { everyGame }?.let { notice ->
                                NovaPanelStatusText(
                                    caption = notice.message,
                                    captionColor = if (notice.isError) LocalNovaComposeColors.current.warning else null,
                                    announce = true,
                                )
                            }
                        },
                    )
                }
                else -> Unit
            }
        }
    }
}

/** Enough to read as texture behind a translucent destination, not as text. */
private const val NOVA_DETAIL_SCENERY_CHROME_ALPHA = 0.16f

/** Frames the return to Artwork's button is asked for, while the Overview turns focusable again. */
private const val ARTWORK_RETURN_FOCUS_FRAMES = 10

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

internal fun novaTuningOutcome(optimization: JSONObject?, preference: String, text: NovaLaunchProfileText): NovaTuningOutcome {
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
    return NovaTuningOutcome.Declined(if (reason.isBlank()) "" else novaLaunchIssueLabel(reason, text))
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

