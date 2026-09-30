package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End Session on the game page splits in its own slot of the action row (R3). One A arms it with
 * Stay focused while Resume and the rest of the row step aside, B puts it back and ends nothing, a
 * press inside the guard ends nothing, and A, Right, A after the guard ends the session once, with
 * no second confirm after it.
 */
@RunWith(RobolectricTestRunner::class)
// A handheld's landscape window, where the game page lays its action row under the status line.
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailEndSplitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var ends = 0
    private var resumes = 0

    private val game = PolarisGame(
        id = "game-1",
        name = "Portal",
        source = "steam",
        launcherSource = "steam",
        category = "fast_action",
        genres = listOf("Puzzle"),
        launchMode = PolarisGame.LaunchModeContract(
            preferredMode = "headless",
            recommendedMode = "virtual_display",
            allowedModes = listOf("headless", "virtual_display"),
        ),
    )

    private val session = NovaLibraryActiveSessionUiState(
        gameId = 1,
        gameUuid = "game-1",
        gameName = "Portal",
        ownerDeviceName = "Retroid Pocket 6",
        ownedByClient = true,
        viewerCount = 1,
        virtualDisplay = false,
        displayModeExplicit = false,
        streamWidth = 1920,
        streamHeight = 1080,
        streamFps = 60f,
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
                // No host is reachable under test; the backdrop simply draws nothing.
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
                playFocusRequester = remember { FocusRequester() },
                onPrimaryLaunch = {},
                onRetryHighFps = {},
                onResetProfile = {},
                shortcutPinState = GameShortcutPinState.UNSUPPORTED,
                shortcutPinRequestPending = false,
                onPinShortcut = {},
                onDestination = {},
                activeSession = session,
                onResumeSession = { resumes++ },
                onEndSession = { ends++ },
            )
        }
        // Let Resume take first focus as the page opens, then walk to End.
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS * 2)
        rule.waitForIdle()
        half(endLabel()).requestFocus()
        rule.mainClock.autoAdvance = false
        return keys
    }

    // A split half by its label: a Text measuring the pair's labels says it too, and only the half is clickable.
    private fun half(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun endLabel() = context.getString(R.string.nova_game_detail_end_session)
    private fun stay() = context.getString(R.string.nova_panel_stay)
    private fun resume() = context.getString(R.string.nova_game_detail_resume)

    private fun arm(keys: NovaTestKeys) {
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)
        half(stay()).assertIsFocused()
    }

    @Test
    fun oneAArmsWithStayFocusedAndTheRestOfTheRowStepsAside() {
        val keys = page()
        arm(keys)
        rule.onNodeWithText(resume()).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.nova_panel_end_session_message)).assertExists()
        assertEquals(0, ends)
    }

    @Test
    fun bPutsTheButtonBackAndEndsNothing() {
        val keys = page()
        arm(keys)

        keys.back()
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)

        assertEquals(0, ends)
        assertEquals(0, resumes)
        half(stay()).assertDoesNotExist()
        rule.onNodeWithText(resume()).assertExists()
        half(endLabel()).assertIsFocused()
    }

    @Test
    fun endIgnoresTheGuardThenEndsOnceWithoutAskingAgain() {
        val keys = page()
        arm(keys)

        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        assertEquals("a press inside the guard after arming ends nothing", 0, ends)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.A)
        rule.advance(ARM_SETTLE_MS)

        assertEquals(1, ends)
        assertEquals(0, resumes)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-television")
    fun onATelevisionStayHoldsFocusRightAfterArmingAndBothLabelsSitOnOneLine() {
        val keys = page()
        keys.press(NovaTestKeys.A)
        // The first frame after arming, before any key: the pair has just split.
        rule.advance(16)
        half(stay()).assertIsFocused()
        rule.advance(ARM_SETTLE_MS + NovaPanelMetrics.SplitMillis)
        half(stay()).assertIsFocused()
        val end = context.getString(R.string.game_dialog_action_end_session)
        half(end).assertIsNotFocused()
        val stayHalf = half(stay()).getUnclippedBoundsInRoot()
        val endHalf = half(end).getUnclippedBoundsInRoot()
        assertEquals("Stay first, End beside it on the same line", stayHalf.top.value, endHalf.top.value, 0.5f)
        assertTrue(endHalf.left > stayHalf.right)
        assertEquals(0, ends)
    }

    @Test
    fun aTapArmsItToo() {
        page()
        half(endLabel()).performClick()
        rule.advance(ARM_SETTLE_MS)
        half(stay()).assertExists()
        assertEquals(0, ends)
    }

    private companion object {
        const val ARM_SETTLE_MS = 50L
    }
}
