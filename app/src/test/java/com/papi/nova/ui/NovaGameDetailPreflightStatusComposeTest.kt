package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A host check that failed said why in a snackbar that floated over the game page and was gone
 * before it could be read, the host's own words included (audit X2). The status line, where what
 * Launch will do is read, says it now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailPreflightStatusComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val game = PolarisGame(id = "game-1", name = "Control", source = "steam", launcherSource = "steam")

    private fun page(optimizationState: NovaGameDetailOptimizationState) {
        val uiState = NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto",
        )
        rule.setPanelContent {
            NovaGameDetailOverview(
                uiState = uiState,
                apiClient = PolarisApiClient(context, ""),
                playLabel = "Retry Host Check",
                lastPlayedText = null,
                sourceLabel = "Steam",
                optimizationState = optimizationState,
                reviewExpanded = false,
                showLaunchModeAction = false,
                logoAvailable = false,
                logoPresentationKey = "",
                logoLoader = {},
                logoContentDescription = "",
                playFocusRequester = remember { FocusRequester() },
                onPrimaryLaunch = {},
                onRetryHighFps = {},
                onResetProfile = {},
                shortcutPinState = GameShortcutPinState.AVAILABLE,
                shortcutPinRequestPending = false,
                onPinShortcut = {},
                onDestination = {},
                activeSession = null,
                onResumeSession = {},
                onEndSession = {},
            )
        }
    }

    @Test
    fun aFailedHostCheckSaysTheHostsWordsOnTheStatusLine() {
        page(NovaGameDetailOptimizationState(preflightFailed = true, preflightMessage = "Polaris refused this profile"))

        rule.onNodeWithTag("nova-game-detail-status").assertExists()
        rule.onNodeWithText("POLARIS REFUSED THIS PROFILE", substring = true).assertExists()
    }

    @Test
    fun aHostCheckThatWorkedLeavesTheLineToThePlan() {
        page(NovaGameDetailOptimizationState(preflightFailed = false, preflightMessage = "Polaris refused this profile"))

        rule.onNodeWithText("POLARIS REFUSED THIS PROFILE", substring = true).assertDoesNotExist()
    }
}
