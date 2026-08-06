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
                .clip(RoundedCornerShape(4.dp))
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

data class NovaGameDetailOptimizationState(
    val ai: NovaGameDetailInsightCard? = null,
    val stability: NovaGameDetailInsightCard? = null,
    val profileSummary: NovaLaunchProfileSummary? = null,
    val rawOptimization: JSONObject? = null,
    val reviewRequired: Boolean = false,
    val reviewReason: String = ""
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
    launchOptionsState: NovaLaunchOptionsState?,
    profileOptionsState: NovaProfilePreferenceOptionsState?,
    playLabel: String,
    launchOptionsLabel: String,
    launchModeTitle: String,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    coverContentDescription: String,
    modifier: Modifier = Modifier,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
    onLaunchOptionSelected: (NovaLaunchOptionItem) -> Unit,
    onDismissLaunchOptions: () -> Unit,
    onProfilePreference: () -> Unit,
    onProfilePreferenceSelected: (NovaProfilePreferenceItem) -> Unit,
    onDismissProfileOptions: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    steamLaunchOptionsState: NovaSteamLaunchModeOptionsState? = null,
    onSteamLaunchMode: () -> Unit,
    onSteamLaunchModeSelected: (NovaSteamLaunchModeItem) -> Unit = {},
    onDismissSteamLaunchModeOptions: () -> Unit = {},
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
            // While a destination is open the Overview is scenery. Without this, a d-pad
            // press walks out of the panel onto a control dimmed behind the scrim.
            modifier = if (destination == NovaGameDetailDestination.OVERVIEW) {
                Modifier
            } else {
                Modifier.focusGroup().focusProperties { canFocus = false }
            },
        )

        when (destination) {
            NovaGameDetailDestination.OVERVIEW -> Unit

            NovaGameDetailDestination.LAUNCH_MODE -> NovaGameDetailPanel(
                eyebrow = stringResource(R.string.nova_library_launch_mode_title),
                headline = stringResource(R.string.nova_game_detail_where_it_runs),
                readout = if (steamDecision != null) {
                    stringResource(R.string.nova_desktop_steam_title)
                } else {
                    optimizationState.profileSummary?.selectedLine.orEmpty()
                },
                scrollState = verticalScroll,
                onDismiss = onDismissDestination,
            ) {
                val decision = steamDecision
                if (decision != null) {
                    NovaDesktopSteamLaunchDecisionRows(
                        decision = decision,
                        onChoice = onSteamChoice,
                    )
                } else {
                    LaunchControls(
                        uiState = uiState,
                        launchIntro = launchIntro,
                        launchModeTitle = launchModeTitle,
                        launchOptionsLabel = launchOptionsLabel,
                        profileSummary = optimizationState.profileSummary,
                        headlessModeLabel = headlessModeLabel,
                        virtualDisplayModeLabel = virtualDisplayModeLabel,
                        playFocusRequester = playFocusRequester,
                        detailsFocusRequester = detailsFocusRequester,
                        onLaunchOptions = onLaunchOptions,
                        onLaunchModeSelected = onLaunchModeSelected,
                    )
                    launchOptionsState?.let {
                        NovaLaunchOptionsSheet(
                            state = it,
                            onLaunch = onLaunchOptionSelected,
                            onDismiss = onDismissLaunchOptions
                        )
                    }
                }
            }

            NovaGameDetailDestination.TUNE -> NovaGameDetailPanel(
                eyebrow = stringResource(R.string.nova_game_detail_tune),
                headline = stringResource(
                    AutoQualityProfilePreferences.shortLabelRes(uiState.profilePreference),
                ),
                readout = listOf(
                    optimizationState.profileSummary?.selectedLine,
                    optimizationState.profileSummary?.freshnessLine,
                ).filter { !it.isNullOrBlank() }.joinToString("  ·  "),
                scrollState = verticalScroll,
                onDismiss = onDismissDestination,
            ) {
                // A picker is what you are doing with the destination while it is
                // open, not a row inside one of its groups, so it spans the body,
                // above the groups rather than below the fold they push it past.
                profileOptionsState?.let {
                    NovaProfilePreferenceSheet(
                        state = it,
                        onSelected = onProfilePreferenceSelected,
                        onDismiss = onDismissProfileOptions
                    )
                }
                steamLaunchOptionsState?.let { state ->
                    NovaSteamLaunchModeSheet(
                        state = state,
                        onSelected = onSteamLaunchModeSelected,
                        onDismiss = onDismissSteamLaunchModeOptions
                    )
                }

                NovaGameDetailGroupLabel(stringResource(R.string.nova_game_detail_group_state))
                // The concept leads State with the profile itself, and it is the only way
                // into the preference picker now that launch mode no longer carries a
                // second copy of Tune's controls.
                NovaSteamChoiceRow(
                    label = stringResource(R.string.nova_game_detail_profile_label),
                    caption = stringResource(R.string.nova_game_detail_profile_caption),
                    enabled = true,
                    onClick = onProfilePreference,
                    value = stringResource(
                        AutoQualityProfilePreferences.shortLabelRes(uiState.profilePreference),
                    ),
                )
                SteamLaunchModeCard(
                    visible = uiState.showSteamLaunchMode,
                    label = steamLaunchLabel,
                    modeLabel = steamLaunchModeLabel,
                    caption = steamLaunchCaption,
                    warning = uiState.steamLaunchWarning,
                    onClick = onSteamLaunchMode
                )
                if (mangoHudEnabled) {
                    MangoHudPassiveStatus(
                        label = mangoHudStatusLabel,
                        caption = mangoHudStatusCaption,
                        warning = mangoHudWarning
                    )
                }
                NovaGameDetailGroupLabel(stringResource(R.string.nova_game_detail_group_actions))
                LaunchProfileSummaryActions(
                    summary = optimizationState.profileSummary,
                    resetProfileLabel = resetProfileLabel,
                    resetProfileWorking = resetProfileWorking,
                    onRetryHighFps = onRetryHighFps,
                    onResetProfile = onResetProfile,
                )
                NovaGameDetailGroupLabel(stringResource(R.string.nova_game_detail_group_insight))
                optimizationState.ai?.let { InsightCard(card = it) }
                optimizationState.stability?.let { InsightCard(card = it) }
            }

            // The studio opens with a Row of weighted Columns, so it needs the window
            // rather than the 438dp panel the other two destinations use.
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
internal fun NovaGameDetailLaunchFooter(
    playLabel: String,
    enabled: Boolean,
    onPrimaryLaunch: () -> Unit,
    playFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    contentInsets: WindowInsets,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current

    LaunchedEffect(enabled) {
        if (enabled) {
            playFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.panel)
            .windowInsetsPadding(contentInsets)
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 1.dp, max = 1.dp)
                .background(colors.divider.copy(alpha = 0.55f))
        )
        NovaActionButton(
            text = playLabel,
            onClick = onPrimaryLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(playFocusRequester)
                .focusProperties { up = detailsFocusRequester }
                .padding(top = 6.dp),
            enabled = enabled,
            primary = true,
            contentDescription = playLabel,
            minHeight = 48.dp,
            cornerRadius = 12.dp,
            fontSize = 16.sp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

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
    val shape = RoundedCornerShape(14.dp)
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
internal fun NovaDesktopSteamLaunchDecisionContent(
    title: String,
    message: String,
    privateStreamLabel: String,
    privateStreamUnavailableReason: String,
    privateStreamEnabled: Boolean,
    mirrorDesktopLabel: String,
    mirrorDesktopEnabled: Boolean,
    mirrorDesktopCaption: String,
    forcePrivateLabel: String,
    forcePrivateEnabled: Boolean,
    forcePrivateCaption: String,
    cancelLabel: String,
    onPrivateStream: () -> Unit,
    onMirrorDesktop: () -> Unit,
    onForcePrivateAfterSteamClose: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp, topEnd = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp))
            .background(LocalNovaLibrarySurfaces.current.panel)
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 16.dp)
    ) {
        NovaSheetDragHandle(
            onDismiss = onCancel,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        NovaDetailPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = true,
            warning = true,
            contentPadding = PaddingValues(14.dp)
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 8.dp),
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp
            )
            if (privateStreamUnavailableReason.isNotBlank()) {
                Text(
                    text = privateStreamUnavailableReason,
                    modifier = Modifier.padding(top = 8.dp),
                    color = colors.warning,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
            Text(
                text = mirrorDesktopCaption,
                modifier = Modifier.padding(top = 8.dp),
                color = colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        NovaActionButton(
            text = privateStreamLabel,
            onClick = onPrivateStream,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            enabled = privateStreamEnabled,
            contentDescription = privateStreamLabel,
            minHeight = 46.dp,
            cornerRadius = 12.dp,
            fontSize = 14.sp
        )
        NovaActionButton(
            text = forcePrivateLabel,
            onClick = onForcePrivateAfterSteamClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = forcePrivateEnabled,
            primary = false,
            contentDescription = forcePrivateLabel,
            minHeight = 46.dp,
            cornerRadius = 12.dp,
            fontSize = 14.sp
        )
        Text(
            text = forcePrivateCaption,
            modifier = Modifier.padding(top = 5.dp),
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
        NovaActionButton(
            text = mirrorDesktopLabel,
            onClick = onMirrorDesktop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = mirrorDesktopEnabled,
            primary = true,
            contentDescription = mirrorDesktopLabel,
            minHeight = 48.dp,
            cornerRadius = 12.dp,
            fontSize = 15.sp
        )
        NovaActionButton(
            text = cancelLabel,
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentDescription = cancelLabel,
            minHeight = 42.dp,
            cornerRadius = 10.dp,
            fontSize = 13.sp
        )
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
            .clip(RoundedCornerShape(14.dp))
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.window)
                    .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
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
                        .clip(RoundedCornerShape(8.dp))
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
private fun LaunchControls(
    uiState: NovaGameDetailUiState,
    launchIntro: String,
    launchModeTitle: String,
    launchOptionsLabel: String,
    profileSummary: NovaLaunchProfileSummary?,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    playFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    onLaunchOptions: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
) {
    val colors = LocalNovaComposeColors.current

    Column {
        Text(
            text = launchIntro,
            color = if (uiState.virtualDisplayUnavailable) colors.warning else colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        if (uiState.showLaunchModeSummary) {
            val launchModeSummary = when {
                uiState.showVirtualUnavailableHint && uiState.virtualDisplayUnavailableReason.isNotBlank() ->
                    virtualDisplayModeLabel + " unavailable: " + uiState.virtualDisplayUnavailableReason
                uiState.showVirtualUnavailableHint ->
                    virtualDisplayModeLabel + " unavailable"
                uiState.playMode == "virtual_display" -> launchModeTitle + ": " + virtualDisplayModeLabel
                uiState.playMode == "headless" -> launchModeTitle + ": " + headlessModeLabel
                else -> launchModeTitle
            }
            Text(
                text = launchModeSummary,
                modifier = Modifier.padding(top = 6.dp),
                color = if (uiState.showVirtualUnavailableHint) colors.warning else colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        profileSummary?.let {
            LaunchProfilePrimaryNotice(
                summary = it,
                detailsFocusRequester = detailsFocusRequester,
                playFocusRequester = playFocusRequester
            )
        }

        if (uiState.showLaunchOptionsButton || uiState.showVirtualUnavailableHint) {
            // The two modes are a list of choices, so they read as rows like the rest of
            // the drawer, each carrying its standing as the value.
            NovaSteamChoiceRow(
                label = headlessModeLabel,
                caption = stringResource(R.string.nova_game_detail_headless_caption),
                enabled = uiState.headlessAllowed,
                onClick = { onLaunchModeSelected("headless") },
                value = when {
                    uiState.playMode == "headless" -> "Selected"
                    uiState.recommendedMode == "headless" && uiState.headlessAllowed -> "Recommended"
                    uiState.headlessAllowed -> "Available"
                    else -> "Unavailable"
                },
            )
            NovaSteamChoiceRow(
                label = virtualDisplayModeLabel,
                caption = if (uiState.showVirtualUnavailableHint) {
                    uiState.virtualDisplayUnavailableReason
                } else {
                    stringResource(R.string.nova_game_detail_virtual_caption)
                },
                enabled = uiState.virtualDisplayAllowed && !uiState.virtualDisplayUnavailable,
                onClick = { onLaunchModeSelected("virtual_display") },
                value = when {
                    uiState.virtualDisplayUnavailable -> "Unavailable"
                    uiState.playMode == "virtual_display" -> "Selected"
                    uiState.recommendedMode == "virtual_display" && uiState.virtualDisplayAllowed -> "Recommended"
                    uiState.virtualDisplayAllowed -> "Available"
                    else -> "Unavailable"
                },
            )
        }

        if (uiState.showLaunchOptionsButton) {
            NovaSteamChoiceRow(
                label = launchOptionsLabel,
                caption = stringResource(R.string.nova_game_detail_more_settings_caption),
                enabled = true,
                onClick = onLaunchOptions,
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
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(toneColor.copy(alpha = 0.14f))
            .border(1.dp, toneColor.copy(alpha = 0.52f), RoundedCornerShape(12.dp))
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
                    cornerRadius = 9.dp,
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
private fun NovaLaunchOptionsSheet(
    state: NovaLaunchOptionsState,
    onLaunch: (NovaLaunchOptionItem) -> Unit,
    onDismiss: () -> Unit
) {
    NovaOptionPanel(
        title = state.title,
        subtitle = state.gameName,
        closeLabel = state.closeLabel,
        onDismiss = onDismiss
    ) {
        state.options.forEach { option ->
            NovaFocusableCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                onClick = { onLaunch(option) },
                contentDescription = listOf(option.label, option.badge, option.caption).filter { it.isNotBlank() }.joinToString(". "),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            color = LocalNovaComposeColors.current.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (option.caption.isNotBlank()) {
                            Text(
                                text = option.caption,
                                modifier = Modifier.padding(top = 3.dp),
                                color = LocalNovaComposeColors.current.textMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (option.recommended || option.badge.isNotBlank()) {
                        NovaBadge(
                            text = option.badge.ifBlank { stringResource(R.string.nova_library_filter_selected) },
                            color = if (option.recommended) LocalNovaComposeColors.current.onAccent else LocalNovaComposeColors.current.textSecondary,
                            backgroundColor = if (option.recommended) LocalNovaComposeColors.current.accent else LocalNovaLibrarySurfaces.current.control,
                            borderColor = if (option.recommended) LocalNovaComposeColors.current.accent else LocalNovaLibrarySurfaces.current.tileBorder,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovaProfilePreferenceSheet(
    state: NovaProfilePreferenceOptionsState,
    onSelected: (NovaProfilePreferenceItem) -> Unit,
    onDismiss: () -> Unit
) {
    NovaOptionPanel(
        title = state.title,
        subtitle = "Auto Quality",
        closeLabel = state.closeLabel,
        onDismiss = onDismiss
    ) {
        state.options.forEach { option ->
            NovaActionButton(
                text = if (option.selected) option.label + " · Selected" else option.label,
                onClick = { onSelected(option) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                primary = option.selected,
                contentDescription = option.label,
                minHeight = 44.dp,
                cornerRadius = 10.dp,
                fontSize = 13.sp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
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
                cornerRadius = 10.dp,
                fontSize = 11.sp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
            )
        }
        content()
    }
}

@Composable
private fun NovaSteamLaunchModeSheet(
    state: NovaSteamLaunchModeOptionsState,
    onSelected: (NovaSteamLaunchModeItem) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = state.title,
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        Text(
            text = state.title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = state.subtitle,
            color = colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.options.forEach { item ->
                NovaFocusableCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelected(item) },
                    contentDescription = item.label,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.label,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.selected) {
                            NovaBadge(
                                text = stringResource(R.string.nova_library_filter_selected),
                                color = colors.onAccent,
                                backgroundColor = colors.accent,
                                borderColor = colors.accent,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
        NovaActionButton(
            text = state.closeLabel,
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            minHeight = 36.dp,
            fontSize = 12.sp
        )
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
