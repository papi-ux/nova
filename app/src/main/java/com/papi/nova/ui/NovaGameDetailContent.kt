package com.papi.nova.ui

import android.widget.ImageView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaFocusableCard
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun canPublishArtworkMutationUiForState(state: Lifecycle.State?): Boolean =
    state?.isAtLeast(Lifecycle.State.CREATED) == true
@Composable
private fun NovaSheetDragHandle(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .size(height = 28.dp, width = 1.dp)
            .novaSheetHandleDrag(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 4.dp)
                .clip(RoundedCornerShape(NovaRadius.chip))
                .background(colors.divider)
        )
    }
}

private fun Modifier.novaSheetHandleDrag(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    val dismissThreshold = 42.dp.toPx()
    var draggedDown = 0f
    detectVerticalDragGestures(
        onDragStart = { draggedDown = 0f },
        onDragCancel = { draggedDown = 0f },
        onDragEnd = {
            if (draggedDown >= dismissThreshold) {
                onDismiss()
            }
            draggedDown = 0f
        },
        onVerticalDrag = { change, dragAmount ->
            if (dragAmount > 0f) {
                draggedDown += dragAmount
                change.consume()
            }
        }
    )
}

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
    val preflightInFlight: Boolean = false
)

data class NovaLaunchOptionsState(
    val title: String,
    val closeLabel: String,
    val gameName: String,
    val options: List<NovaLaunchOptionItem>
)

data class NovaLaunchOptionItem(
    val label: String,
    val usesVirtualDisplay: Boolean,
    val recommended: Boolean,
    val caption: String = "",
    val badge: String = "",
    val launchOptimization: JSONObject? = null
)

data class NovaProfilePreferenceOptionsState(
    val title: String,
    val closeLabel: String,
    val options: List<NovaProfilePreferenceItem>
)

data class NovaProfilePreferenceItem(
    val label: String,
    val value: String,
    val selected: Boolean
)

data class NovaGameDetailInsightCard(
    val label: String,
    val source: String,
    val settings: String,
    val reasoning: String,
    val isWarning: Boolean
)

data class NovaSteamLaunchModeItem(
    val label: String,
    val value: String,
    val selected: Boolean
)

data class NovaSteamLaunchModeOptionsState(
    val title: String,
    val subtitle: String,
    val closeLabel: String,
    val options: List<NovaSteamLaunchModeItem>
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
    /** The four rows of the act column, already resolved, in draw order. */
    playSetupRows: List<NovaPlaySetupRowState>,
    /** Which row the comparison strip is currently explaining. */
    explainedPlaySetupRow: NovaPlaySetupRow,
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
    /** Opens the host scope. Null where no host settings surface is reachable. */
    onOpenHostSettings: (() -> Unit)? = null,
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
) {
    val verticalScroll = rememberScrollState()
    val playFocusRequester = remember { FocusRequester() }
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
            onPrimaryLaunch = onPrimaryLaunch,
            onRetryHighFps = onRetryHighFps,
            onResetProfile = onResetProfile,
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
            NovaGameDetailDestination.OVERVIEW -> Unit

            NovaGameDetailDestination.PLAY_SETUP -> NovaGameDetailWidePanel(
                eyebrow = stringResource(R.string.nova_play_setup_title),
                headline = uiState.game.name,
                scrollState = verticalScroll,
                onDismiss = onDismissDestination,
            ) { bodyHeight ->
                val decision = steamDecision
                if (decision != null) {
                    // A blocked three-way choice is the one moment nothing else on the
                    // screen matters, so it keeps the body to itself.
                    NovaDesktopSteamLaunchDecisionRows(
                        decision = decision,
                        onChoice = onSteamChoice,
                    )
                } else {
                    val summary = optimizationState.profileSummary
                    // Spend the room that is there rather than a number picked in advance:
                    // a host with a display planner has a fourth row and so less of it.
                    val consequenceLines =
                        novaPlaySetupConsequenceLines(bodyHeight, playSetupRows.size)
                    NovaPlaySetupBody(
                        plan = novaPlaySetupPlan(
                            // The resolved mode, not the name of the control that sets
                            // it: this is the one line the column exists to state.
                            modeLabel = when (uiState.playMode) {
                                "virtual_display" -> virtualDisplayModeLabel
                                else -> headlessModeLabel
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
                            hostFacts = buildList {
                                if (uiState.hostStreamDisplayModeLabel.isNotBlank()) {
                                    add(
                                        NovaPlaySetupFact(
                                            key = stringResource(R.string.nova_play_setup_fact_host_default),
                                            value = uiState.hostStreamDisplayModeLabel,
                                            detail = stringResource(
                                                if (uiState.overridesHostMode) {
                                                    R.string.nova_play_setup_host_overridden
                                                } else {
                                                    R.string.nova_play_setup_host_followed
                                                },
                                            ),
                                            tone = if (uiState.overridesHostMode) {
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
                        rows = {
                            // Four rows, drawn in a fixed order. Each advances its own value
                            // on A or a tap, and points the strip at itself on focus, so the
                            // explanation follows the cursor without being a stop on it.
                            playSetupRows.forEachIndexed { index, rowState ->
                                NovaSteamChoiceRow(
                                    autoFocus = index == 0,
                                    label = rowState.label,
                                    caption = rowState.caption,
                                    enabled = rowState.enabled,
                                    value = rowState.value,
                                    selected = rowState.overridden,
                                    onClick = { onAdvancePlaySetupRow(rowState.row) },
                                    onFocused = { onExplainPlaySetupRow(rowState.row) },
                                )
                            }
                        },
                        comparison = {
                            // A legend for whichever row holds focus, not a picker with a
                            // state of its own. A row that has nothing to compare -- one
                            // launch mode, or no display planner on this host -- draws
                            // nothing rather than a strip that repeats the row above it.
                            val explained = playSetupRows.firstOrNull { it.row == explainedPlaySetupRow }
                                ?: playSetupRows.firstOrNull()
                            if (explained != null && explained.options.size > 1) {
                                NovaPlaySetupComparison(
                                    title = explained.stripTitle,
                                    options = explained.options,
                                    consequenceMaxLines = consequenceLines,
                                )
                            }
                        },
                    )
                    if (mangoHudEnabled) {
                        MangoHudPassiveStatus(
                            label = mangoHudStatusLabel,
                            caption = mangoHudStatusCaption,
                            warning = mangoHudWarning
                        )
                    }
                }

                // No sheets. Every one of these choices is made in the strip above,
                // which is the rule this window already had for the preflight review:
                // expand the lane rather than raise a dialog.
            }

            // The studio opens with a Row of weighted Columns, so it needs the window
            // rather than the panel Play Setup uses.
            NovaGameDetailDestination.ARTWORK -> NovaGameDetailFullScreen(
                eyebrow = stringResource(R.string.nova_artwork_studio_title),
                headline = uiState.game.name,
                scrollState = verticalScroll,
                onDismiss = onDismissDestination,
            ) {
                NovaArtworkStudio(
                    initiallyExpanded = true,
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
        "balanced" -> R.string.nova_play_setup_pref_balanced
        "high_fps" -> R.string.nova_play_setup_pref_high_fps
        else -> R.string.nova_play_setup_pref_auto
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
private fun NovaGameDetailScrollableContent(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(bottom = 16.dp),
        content = content
    )
}

@Composable
private fun novaGameDetailControllerHints(): List<NovaControllerHint> = listOf(
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_a),
        label = stringResource(R.string.nova_controller_hint_launch)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_b),
        label = stringResource(R.string.nova_controller_hint_close)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_lb_rb),
        label = stringResource(R.string.nova_controller_hint_launch_mode)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_y),
        label = stringResource(R.string.nova_controller_hint_profile)
    )
)

@Composable
private fun NovaDetailPanel(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    accent: Boolean = false,
    warning: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.hero)
    val backgroundColor = when {
        warning -> colors.warning.copy(alpha = 0.12f)
        accent -> colors.accentSurface
        else -> surfaces.tile
    }
    val borderColor = when {
        warning -> colors.warning.copy(alpha = 0.55f)
        else -> surfaces.tileBorder
    }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .then(semanticsModifier)
            .padding(contentPadding)
    ) {
        content()
    }
}



@Composable
private fun GameDetailsPanel(
    uiState: NovaGameDetailUiState,
    lastPlayedText: String?,
    coverContentDescription: String,
    coverLoader: (ImageView) -> Unit,
    artworkState: NovaArtworkStudioState,
    heroAvailable: Boolean,
    heroPresentationKey: String,
    heroLoader: (ImageView) -> Unit,
    heroContentDescription: String,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
    iconAvailable: Boolean,
    iconPresentationKey: String,
    iconLoader: (ImageView) -> Unit,
    iconContentDescription: String,
) {
    val game = uiState.game

    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .heightIn(min = 136.dp),
        contentDescription = "Game details",
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        if (heroAvailable) {
            NovaGameDetailHero(
                game = game,
                artworkState = artworkState,
                heroPresentationKey = heroPresentationKey,
                heroLoader = heroLoader,
                heroContentDescription = heroContentDescription,
                logoAvailable = logoAvailable,
                logoPresentationKey = logoPresentationKey,
                logoLoader = logoLoader,
                logoContentDescription = logoContentDescription,
                iconAvailable = iconAvailable,
                iconPresentationKey = iconPresentationKey,
                iconLoader = iconLoader,
                iconContentDescription = iconContentDescription,
            )
        } else {
            NovaGameDetailPosterFallback(
                uiState = uiState,
                lastPlayedText = lastPlayedText,
                coverContentDescription = coverContentDescription,
                coverLoader = coverLoader,
                iconAvailable = iconAvailable,
                iconPresentationKey = iconPresentationKey,
                iconLoader = iconLoader,
                iconContentDescription = iconContentDescription,
            )
        }
    }
}

@Composable
private fun NovaGameDetailHero(
    game: PolarisGame,
    artworkState: NovaArtworkStudioState,
    heroPresentationKey: String,
    heroLoader: (ImageView) -> Unit,
    heroContentDescription: String,
    logoAvailable: Boolean,
    logoPresentationKey: String,
    logoLoader: (ImageView) -> Unit,
    logoContentDescription: String,
    iconAvailable: Boolean,
    iconPresentationKey: String,
    iconLoader: (ImageView) -> Unit,
    iconContentDescription: String,
) {
    val colors = LocalNovaComposeColors.current
    val menuOpacityScale = LocalNovaMenuOpacityScale.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(colors.window)
    ) {
        key(heroPresentationKey) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(NovaThemeManager.getCardBackgroundColor(context))
                        contentDescription = heroContentDescription
                        heroLoader(this)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(136.dp)
                    .semantics { contentDescription = heroContentDescription }
            )
        }

        if (logoAvailable) {
            val logoWidth = maxWidth * 0.56f
            val logoHeight = maxHeight * 0.46f
            val logoOffsetX = (maxWidth - logoWidth) * artworkState.logoX
            val logoOffsetY = (maxHeight - logoHeight) * artworkState.logoY
            key(logoPresentationKey) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            contentDescription = logoContentDescription
                            logoLoader(this)
                        }
                    },
                    modifier = Modifier
                        .offset(x = logoOffsetX, y = logoOffsetY)
                        .size(logoWidth, logoHeight)
                        .graphicsLayer {
                            scaleX = artworkState.logoScale
                            scaleY = artworkState.logoScale
                        }
                        .semantics { contentDescription = logoContentDescription }
                )
            }
        }

        NovaGameDetailIdentity(
            game = game,
            iconAvailable = iconAvailable,
            iconPresentationKey = iconPresentationKey,
            iconLoader = iconLoader,
            iconContentDescription = iconContentDescription,
            compact = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(colors.window.copy(alpha = 0.84f * menuOpacityScale))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun NovaGameDetailPosterFallback(
    uiState: NovaGameDetailUiState,
    lastPlayedText: String?,
    coverContentDescription: String,
    coverLoader: (ImageView) -> Unit,
    iconAvailable: Boolean,
    iconPresentationKey: String,
    iconLoader: (ImageView) -> Unit,
    iconContentDescription: String,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val game = uiState.game

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        key(PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_POSTER)) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(NovaThemeManager.getCardBackgroundColor(context))
                        contentDescription = coverContentDescription
                        coverLoader(this)
                    }
                },
                modifier = Modifier
                    .width(108.dp)
                    .aspectRatio(88f / 118f)
                    .clip(RoundedCornerShape(NovaRadius.row))
                    .background(colors.window)
                    .border(1.dp, colors.divider, RoundedCornerShape(NovaRadius.row))
                    .semantics { contentDescription = coverContentDescription }
            )
        }

        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            NovaGameDetailIdentity(
                game = game,
                iconAvailable = iconAvailable,
                iconPresentationKey = iconPresentationKey,
                iconLoader = iconLoader,
                iconContentDescription = iconContentDescription,
                compact = false,
            )

            MetadataBadges(game)
            GenresRow(game.genres)

            if (lastPlayedText != null) {
                NovaBadge(
                    text = lastPlayedText,
                    modifier = Modifier.padding(top = 7.dp),
                    color = colors.textSecondary,
                    backgroundColor = surfaces.control.copy(alpha = 0.78f * LocalNovaMenuOpacityScale.current),
                    borderColor = surfaces.tileBorder,
                    fontSize = 11.sp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun NovaGameDetailIdentity(
    game: PolarisGame,
    iconAvailable: Boolean,
    iconPresentationKey: String,
    iconLoader: (ImageView) -> Unit,
    iconContentDescription: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconAvailable) {
            key(iconPresentationKey) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            contentDescription = iconContentDescription
                            iconLoader(this)
                        }
                    },
                    modifier = Modifier
                        .size(if (compact) 34.dp else 38.dp)
                        .clip(RoundedCornerShape(NovaRadius.row))
                        .semantics { contentDescription = iconContentDescription }
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(start = if (iconAvailable) 9.dp else 0.dp)
                .weight(1f)
        ) {
            Text(
                text = game.name,
                color = colors.textPrimary,
                fontSize = if (compact) 17.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = if (compact) 19.sp else 22.sp,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (game.sourceRuntimeLabel.isNotBlank()) {
                Text(
                    text = game.sourceRuntimeLabel,
                    modifier = Modifier.padding(top = if (compact) 1.dp else 5.dp),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetadataBadges(game: PolarisGame) {
    val horizontalScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .horizontalScroll(horizontalScroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (game.sourceLabel.isNotEmpty()) {
            NovaBadge(text = game.sourceLabel)
        }
        if (game.categoryLabel.isNotEmpty()) {
            NovaBadge(text = game.categoryLabel)
        }
    }
}

@Composable
private fun GenresRow(genres: List<String>) {
    if (genres.isEmpty()) return
    val horizontalScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .padding(top = 5.dp)
            .horizontalScroll(horizontalScroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        genres.forEach { genre ->
            NovaBadge(
                text = genre,
                color = LocalNovaComposeColors.current.textMuted
            )
        }
    }
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
private fun ProfileSummaryText(text: String, topPadding: Int = 3) {
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = Modifier.padding(top = topPadding.dp),
        color = LocalNovaComposeColors.current.textMuted,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}


@Composable
private fun NovaOptionPanel(
    title: String,
    subtitle: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = title,
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(top = 2.dp),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            NovaActionButton(
                text = closeLabel,
                onClick = onDismiss,
                modifier = Modifier.width(104.dp),
                contentDescription = closeLabel,
                minHeight = 36.dp,
                cornerRadius = NovaRadius.hero,
                fontSize = 11.sp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
            )
        }
        content()
    }
}

@Composable
private fun SteamLaunchModeCard(
    visible: Boolean,
    label: String,
    modeLabel: String,
    caption: String,
    warning: Boolean,
    onClick: () -> Unit
) {
    if (!visible) return

    NovaSteamChoiceRow(
        label = label,
        caption = caption,
        enabled = true,
        onClick = onClick,
        value = modeLabel,
    )
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

@Composable
private fun InsightCard(card: NovaGameDetailInsightCard) {
    val colors = LocalNovaComposeColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NovaGameDetailInset)
            .padding(top = 12.dp, bottom = 2.dp),
    ) {
        Column {
            Text(
                text = card.label,
                color = if (card.isWarning) colors.warning else colors.accent,
                fontSize = if (card.isWarning) 13.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (card.source.isNotBlank()) {
                // Six facts joined by separators is a metadata line, not a tag; in a chip
                // it could only ellipsise, so it wraps under the title instead.
                Text(
                    text = card.source,
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = card.settings,
                modifier = Modifier.padding(top = 5.dp),
                color = colors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (card.reasoning.isNotBlank()) {
                Text(
                    text = card.reasoning,
                    modifier = Modifier.padding(top = 3.dp),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
