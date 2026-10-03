package com.papi.nova.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.GameShortcutPinState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaSpaceLaunchReasonComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blockedLauncherExplainsItselfInTheOverviewAndKeepsLaunchDisabled() {
        val game = PolarisGame(id = "space.a.big-picture-v1", name = "Steam Big Picture", source = "steam",
            space = PolarisGame.SpaceContext("a", "Living Room", "big-picture-v1"))
        val ready = NovaGameDetailUiState.from(game, false, null, "auto")
        val blocked = mutableStateOf(true)
        var launches = 0
        val reason = "No launch mode is available for this Space. Check its launch settings in Polaris."
        val api = PolarisApiClient(InstrumentationRegistry.getInstrumentation().targetContext, "")
        compose.setContent {
            NovaComposeTheme {
                NovaGameDetailOverview(
                    uiState = ready.copy(playEnabled = !blocked.value),
                    apiClient = api,
                    playLabel = "Open Steam Big Picture",
                    launchBlockedReason = if (blocked.value) reason else null,
                    lastPlayedText = null,
                    sourceLabel = "Steam",
                    optimizationState = NovaGameDetailOptimizationState(),
                    reviewExpanded = false,
                    showLaunchModeAction = false,
                    logoAvailable = false,
                    logoPresentationKey = "",
                    logoLoader = {},
                    logoContentDescription = "",
                    playFocusRequester = remember { FocusRequester() },
                    onPrimaryLaunch = { launches++ },
                    onRetryHighFps = {},
                    onResetProfile = {},
                    shortcutPinState = GameShortcutPinState.UNSUPPORTED,
                    shortcutPinRequestPending = false,
                    onPinShortcut = {},
                    onDestination = {},
                    activeSession = null,
                    onResumeSession = {},
                    onEndSession = {},
                    modifier = Modifier.requiredSize(720.dp, 480.dp),
                )
            }
        }
        compose.onNodeWithTag("nova-game-detail-primary").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("nova-space-launch-blocked-reason").assertIsDisplayed().assertTextEquals(reason)
        compose.runOnIdle { assertEquals(0, launches); blocked.value = false }
        compose.onNodeWithTag("nova-space-launch-blocked-reason").assertDoesNotExist()
        compose.onNodeWithTag("nova-game-detail-primary").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, launches) }
    }
}
