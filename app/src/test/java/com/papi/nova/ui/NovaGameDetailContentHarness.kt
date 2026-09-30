package com.papi.nova.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.utils.GameShortcutPinState

/**
 * The game page's content as the activity composes it, with the inputs a test turns and the rest
 * quiet: no host, no artwork and nothing running. A test that reads what the page does with an
 * input drives it here, through the real call sites, rather than one component with a lambda of
 * its own.
 */
@Composable
internal fun NovaGameDetailContentUnderTest(
    uiState: NovaGameDetailUiState,
    optimizationState: NovaGameDetailOptimizationState = NovaGameDetailOptimizationState(),
    playSetupBitrateShortfallMbps: Int = 0,
    playSetupPanel: NovaPanelState = remember { NovaPanelState() },
    playSetupRows: List<NovaPlaySetupRowState> = emptyList(),
    playSetupScope: NovaPlaySetupScope = NovaPlaySetupScope.THIS_GAME,
    onPlaySetupScopeSelected: (NovaPlaySetupScope) -> Unit = {},
    hostPlaySetupPlan: NovaPlaySetupPlan? = null,
    destination: NovaGameDetailDestination = NovaGameDetailDestination.OVERVIEW,
    playLabel: String = "Launch",
) {
    val context = LocalContext.current
    NovaGameDetailContent(
        uiState = uiState,
        launchIntro = "",
        recommendedBadge = "",
        lastPlayedText = null,
        profilePreferenceLabel = "Auto",
        resetProfileLabel = "Reset Game Profile",
        resetProfileWorking = false,
        mangoHudEnabled = false,
        mangoHudStatusLabel = "",
        mangoHudStatusCaption = "",
        mangoHudWarning = false,
        steamLaunchLabel = "",
        steamLaunchModeLabel = "",
        steamLaunchCaption = "",
        optimizationState = optimizationState,
        playSetupRows = playSetupRows,
        playSetupScope = playSetupScope,
        onPlaySetupScopeSelected = onPlaySetupScopeSelected,
        hostPlaySetupRows = emptyList(),
        hostPlaySetupPlan = hostPlaySetupPlan,
        playSetupBitrateShortfallMbps = playSetupBitrateShortfallMbps,
        playSetupPanel = playSetupPanel,
        playLabel = playLabel,
        launchModeTitle = "Launch Mode",
        headlessModeLabel = "Headless",
        virtualDisplayModeLabel = "Virtual Display",
        coverContentDescription = "Cover",
        onPrimaryLaunch = {},
        onAdvancePlaySetupRow = {},
        onRetryHighFps = {},
        onResetProfile = {},
        shortcutPinState = GameShortcutPinState.UNSUPPORTED,
        shortcutPinRequestPending = false,
        onPinShortcut = {},
        artworkState = NovaArtworkStudioState(),
        onRefreshArtwork = {},
        onSearchArtwork = {},
        onIdentitySelected = {},
        onIdentityChange = {},
        onKindSelected = {},
        onChoiceSelected = {},
        onStudioAction = {},
        onApplyArtwork = { _, _ -> },
        onClearArtwork = {},
        onLogoTransform = { _, _, _ -> },
        candidatePreviewLoader = { _, _ -> },
        choicePreviewLoader = { _, _ -> },
        currentArtworkPresentationKey = { "" },
        currentArtworkLoader = { _, _ -> },
        logoAvailable = false,
        logoPresentationKey = "",
        logoLoader = {},
        iconAvailable = false,
        iconPresentationKey = "",
        iconLoader = {},
        coverLoader = {},
        destination = destination,
        steamDecision = null,
        reviewExpanded = false,
        apiClient = remember(context) { PolarisApiClient(context, "") },
        sourceLabel = "Steam",
        onDestination = {},
        onSteamChoice = {},
        activeSession = null,
        onResumeSession = {},
        onEndSession = {},
        onDismissDestination = {},
    )
}
