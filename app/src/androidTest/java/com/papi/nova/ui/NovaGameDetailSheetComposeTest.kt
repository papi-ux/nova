package com.papi.nova.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NovaGameDetailSheetComposeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun gameDetailSheetComposesInViewInteropHost() {
        val game = PolarisGame(
            id = "game-1",
            name = "Portal",
            source = "steam",
            launcherSource = "steam",
            category = "fast_action",
            genres = listOf("Puzzle"),
            launchMode = PolarisGame.LaunchModeContract(
                preferredMode = "headless",
                recommendedMode = "virtual_display",
                allowedModes = listOf("headless", "virtual_display")
            )
        )
        val uiState = NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "virtual_display")
            ),
            profilePreference = "auto"
        )
        val composeViewRef = AtomicReference<ComposeView>()

        activityRule.scenario.onActivity { activity ->
            val composeView = ComposeView(activity)
            activity.setContentView(
                composeView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            composeViewRef.set(composeView)
            composeView.setContent {
                NovaComposeTheme {
                    NovaGameDetailSheetContent(
                        uiState = uiState,
                        launchIntro = "Virtual Display is currently the default for this Polaris server.",
                        recommendedBadge = "Rec. Virtual",
                        lastPlayedText = null,
                        profilePreferenceLabel = "AI Preference: Auto",
                        resetProfileLabel = "Reset Game Profile",
                        resetProfileWorking = false,
                        mangoHudEnabled = false,
                        mangoHudStatusLabel = "MangoHud Overlay",
                        mangoHudStatusCaption = "Next launch only",
                        mangoHudWarning = false,
                        steamLaunchLabel = "Steam Launch",
                        steamLaunchModeLabel = "Direct",
                        steamLaunchCaption = "Direct launch without opening Big Picture",
                        optimizationState = NovaGameDetailOptimizationState(),
                        launchOptionsState = null,
                        profileOptionsState = null,
                        playLabel = "Play",
                        launchOptionsLabel = "Launch Options",
                        launchModeTitle = "Launch Mode",
                        headlessModeLabel = "Headless",
                        virtualDisplayModeLabel = "Virtual Display",
                        coverContentDescription = "Game cover art",
                        onSheetHandleDismiss = {},
                        onPrimaryLaunch = {},
                        onLaunchOptions = {},
                        onLaunchModeSelected = {},
                        onLaunchOptionSelected = { _ -> },
                        onDismissLaunchOptions = {},
                        onProfilePreference = {},
                        onProfilePreferenceSelected = { _ -> },
                        onDismissProfileOptions = {},
                        onRetryHighFps = {},
                        onResetProfile = {},
                        onSteamLaunchMode = {},
                        artworkState = NovaArtworkCorrectionState(),
                        onRefreshArtwork = {},
                        onSearchArtwork = {},
                        onApplyArtwork = { _, _ -> },
                        onClearArtwork = {},
                        onLogoTransform = { _, _, _ -> },
                        candidatePreviewLoader = { _, _ -> },
                        logoAvailable = false,
                        logoPresentationKey = "",
                        logoLoader = {},
                        iconAvailable = false,
                        iconPresentationKey = "",
                        iconLoader = {},
                        coverLoader = {}
                    )
                }
            }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity {
            val composeView = composeViewRef.get()
            assertTrue(composeView.isAttachedToWindow)
        }
    }
}
