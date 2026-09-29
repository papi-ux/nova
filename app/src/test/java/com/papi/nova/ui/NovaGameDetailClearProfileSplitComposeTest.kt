package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clear Game Profile wipes what the host learned about a game on this device, and one A did it on
 * the RP6. It splits like End Session (R3): one A arms it with Stay focused, B puts it back, and
 * only A, Right, A after the guard clears, once. While it is armed the floor says B Stay and
 * carries the line, so the bottom-anchored page does not grow and jump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailClearProfileSplitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var clears = 0

    private val game = PolarisGame(
        id = "game-1",
        name = "Control",
        source = "steam",
        launcherSource = "steam",
        category = "fast_action",
        genres = listOf("Action"),
        launchMode = PolarisGame.LaunchModeContract(
            preferredMode = "headless",
            recommendedMode = "virtual_display",
            allowedModes = listOf("headless", "virtual_display"),
        ),
    )

    private fun page(): NovaTestKeys {
        val uiState = NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(
                desired = PolarisClientSettings.Desired(streamDisplayMode = "virtual_display"),
            ),
            profilePreference = "auto",
        )
        val keys = rule.setPanelContent {
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
                onResetProfile = { clears++ },
                shortcutPinState = GameShortcutPinState.UNSUPPORTED,
                shortcutPinRequestPending = false,
                onPinShortcut = {},
                onDestination = {},
                activeSession = null,
                onResumeSession = {},
                onEndSession = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS * 2)
        rule.waitForIdle()
        half(label()).requestFocus()
        rule.mainClock.autoAdvance = false
        return keys
    }

    // A split half by its label: a Text measuring the pair's labels says it too, and only the half is clickable.
    private fun half(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun label() = context.getString(R.string.nova_library_reset_game_profile)
    private fun stay() = context.getString(R.string.nova_panel_stay)
    private fun confirm() = context.getString(R.string.nova_game_detail_clear_profile_confirm)

    @Test
    fun oneAArmsAndClearsNothing() {
        val keys = page()
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        half(stay()).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.nova_game_detail_clear_profile_consequence)).assertExists()
        assertEquals(0, clears)
    }

    @Test
    fun whileArmedTheFloorSaysBStay() {
        val keys = page()
        rule.onNodeWithText(context.getString(R.string.nova_controller_hint_close)).assertExists()
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        rule.onNodeWithText(context.getString(R.string.nova_controller_hint_close)).assertDoesNotExist()
        keys.back()
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)
        half(label()).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.nova_controller_hint_close)).assertExists()
        assertEquals(0, clears)
    }

    @Test
    fun aRightAAfterTheGuardClearsOnce() {
        val keys = page()
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        keys.press(NovaTestKeys.RIGHT)
        half(confirm()).assertIsFocused()
        keys.press(NovaTestKeys.A)
        assertEquals("a press inside the guard clears nothing", 0, clears)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        assertEquals(1, clears)
    }

    private companion object {
        const val ARM_SETTLE_MS = 50L
    }
}
