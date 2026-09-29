package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
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
 * What pinning a game to the home screen came to is said in the pin button's own words for a
 * moment, in place: it was a Toast that floated over the page. At rest the pin is an icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailPinResultComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var result by mutableStateOf<String?>(null)

    private val game = PolarisGame(id = "game-1", name = "Control", source = "steam", launcherSource = "steam")

    private fun page() {
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
                playLabel = "Play",
                lastPlayedText = null,
                sourceLabel = "Steam",
                optimizationState = NovaGameDetailOptimizationState(),
                reviewExpanded = false,
                showLaunchModeAction = false,
                logoAvailable = false,
                logoPresentationKey = "",
                logoLoader = {},
                logoContentDescription = "",
                playFocusRequester = FocusRequester(),
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
                shortcutPinResult = result,
            )
        }
    }

    @Test
    fun atRestThePinIsAnIconAndAResultIsSaidInItsOwnWords() {
        page()
        val pin = context.getString(R.string.nova_library_pin_shortcut)
        rule.onNodeWithContentDescription(pin).assertExists()
        rule.onNodeWithText(pin).assertDoesNotExist()

        result = context.getString(R.string.nova_library_pin_shortcut_unsupported)
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.nova_library_pin_shortcut_unsupported)).assertExists()

        result = null
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.nova_library_pin_shortcut_unsupported)).assertDoesNotExist()
    }
}
