package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The game page's Launch, its Resume, and the Stage hero's Review & Launch rest as tiles, marked by
 * their label in the accent, and take the accent fill only under focus, as every other primary has
 * since 279fc1bb (M6). Launch had an accent gradient that stayed lit after focus moved on, a second
 * focus beside the real one, and changed so little under focus that the page seemed to open with
 * no focus at all (in-game smoke #2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailPrimaryLookComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var colors: NovaComposeColors

    private val game = PolarisGame(
        id = "game-1",
        name = "Control",
        source = "steam",
        launcherSource = "steam",
        category = "cinematic",
        launchMode = PolarisGame.LaunchModeContract(
            preferredMode = "headless",
            recommendedMode = "headless",
            allowedModes = listOf("headless", "virtual_display"),
        ),
    )

    private val session = NovaLibraryActiveSessionUiState(
        gameId = 1,
        gameUuid = "game-1",
        gameName = "Control",
        ownerDeviceName = "Retroid Pocket 6",
        ownedByClient = true,
        viewerCount = 1,
        virtualDisplay = false,
        displayModeExplicit = false,
        streamWidth = 1920,
        streamHeight = 1080,
        streamFps = 60f,
    )

    private fun labelColour(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    private fun page(activeSession: NovaLibraryActiveSessionUiState? = null) {
        val uiState = NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto",
        )
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            NovaGameDetailOverview(
                uiState = uiState,
                apiClient = PolarisApiClient(context, ""),
                playLabel = LAUNCH,
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
                shortcutPinState = GameShortcutPinState.UNSUPPORTED,
                shortcutPinRequestPending = false,
                onPinShortcut = {},
                onDestination = {},
                activeSession = activeSession,
                onResumeSession = {},
                onEndSession = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS * 2)
        rule.waitForIdle()
    }

    @Test
    fun theGamePageOpensWithLaunchFocusedAndFilledSoItsFocusIsSeen() {
        page()
        rule.onNodeWithTag("nova-game-detail-primary").assertIsFocused()
        assertEquals("under focus Launch wears the fill's label", colors.onAccent, labelColour(LAUNCH))
    }

    @Test
    fun launchRestsAsATileWithItsLabelInTheAccentOnceFocusMovesOn() {
        page()
        rule.onNodeWithContentDescription(context.getString(R.string.nova_play_setup_title)).requestFocus()
        rule.waitForIdle()
        assertEquals("at rest Launch's label is the accent, as a tile's", colors.accentText, labelColour(LAUNCH))
        assertNotEquals("and not the label of a fill: a lit Launch beside the focus read as a second focus", colors.onAccent, labelColour(LAUNCH))
    }

    @Test
    fun resumeRestsAsATileBesideEndAndFillsUnderFocus() {
        page(activeSession = session)
        val resume = context.getString(R.string.nova_game_detail_resume)
        rule.onNodeWithTag("nova-game-detail-primary").assertIsFocused()
        assertEquals(colors.onAccent, labelColour(resume))

        rule.onNodeWithContentDescription(context.getString(R.string.nova_game_detail_end_session)).requestFocus()
        rule.waitForIdle()
        assertEquals(colors.accentText, labelColour(resume))
    }

    private companion object {
        const val LAUNCH = "Launch Quality · 120 FPS"
    }
}
