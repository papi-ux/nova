package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launch and the Stage hero's Review & Launch rest as tiles and fill only under focus (M6). The
 * look tests read the label's colour, which stands in for the fill only while the fill and the
 * label follow one flag; a Stage hero resting in the accent again would have passed them. These
 * read the fill and the ring the surfaces draw with: the tile's fill at rest, the accent fill and
 * the label colour's ring under focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaPrimaryRestLookComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var colors: NovaComposeColors
    private lateinit var surfaces: NovaLibrarySurfaces

    @Before
    fun inspectModifiers() {
        // A background keeps its colour for an inspector only while this is on.
        isDebugInspectorInfoEnabled = true
    }

    @After
    fun stopInspecting() {
        isDebugInspectorInfoEnabled = false
    }

    // Colours compared as drawn, 8 bits a channel: a fill animated to its end is the colour it was
    // given after a round trip through the space it animates in.
    private fun argb(colour: Any?): Int = (colour as Color).toArgb()

    /** What the modifier named [name] on [node] was given, by property. */
    private fun modifier(node: SemanticsNodeInteraction, name: String): Map<String, Any?>? =
        node.fetchSemanticsNode().layoutInfo.getModifierInfo()
            .map { it.modifier }
            .filterIsInstance<InspectableValue>()
            .firstOrNull { it.nameFallback == name }
            ?.inspectableElements?.associate { it.name to it.value }

    @Test
    fun launchRestsAsTheTileAndTakesTheAccentFillAndItsRingUnderFocus() {
        val uiState = NovaGameDetailUiState.from(
            game = PolarisGame(id = "game-1", name = "Control", source = "steam", launcherSource = "steam"),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto",
        )
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            surfaces = LocalNovaLibrarySurfaces.current
            NovaGameDetailOverview(
                uiState = uiState,
                apiClient = PolarisApiClient(context, ""),
                playLabel = "Launch",
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
                activeSession = null,
                onResumeSession = {},
                onEndSession = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS * 2)
        rule.waitForIdle()
        val launch = rule.onNodeWithTag("nova-game-detail-primary")
        launch.assertIsFocused()
        val look = modifier(launch, "novaFocusRing")!!
        assertEquals("under focus the accent fill", colors.accent, look["focusedFill"])
        assertEquals("with the ring in its label's colour", colors.onAccent, look["ring"])

        rule.onNodeWithContentDescription(context.getString(R.string.nova_play_setup_title)).requestFocus()
        rule.waitForIdle()
        launch.assertIsNotFocused()
        val rest = modifier(launch, "novaFocusRing")!!
        assertEquals("at rest the tile's own fill", surfaces.control, rest["restFill"])
        assertNotEquals("never the accent, a second focus beside the real one", colors.accent, rest["restFill"])
        assertEquals("and the tile's hairline", surfaces.tileBorder, rest["restBorder"])
    }

    @Test
    fun theStageHerosReviewAndLaunchRestsOnTheScrimAndFillsWithItsRingUnderFocus() {
        val games = listOf(
            PolarisGame(id = "control", name = "Control", source = "steam"),
            PolarisGame(id = "portal", name = "Portal", source = "steam"),
        )
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            surfaces = LocalNovaLibrarySurfaces.current
            NovaLibraryStage(
                games = games,
                focusedGame = games.first(),
                restoreFocusGameId = null,
                primaryActionLabel = "Review & Launch",
                apiClient = PolarisApiClient(context, ""),
                showPosterTitles = false,
                onPrimaryAction = {},
                onGameFocused = {},
                onOpenDetail = {},
                artworkLoader = { _, _, _ -> },
                posterLoader = { _, _ -> },
            )
        }
        rule.waitForIdle()
        val surface = rule.onNodeWithTag("nova-stage-primary-action-surface", useUnmergedTree = true)
        rule.onNodeWithTag("nova-stage-primary-action").assertIsNotFocused()
        assertEquals("at rest the artwork scrim, not the accent", argb(surfaces.focusedArtworkScrim), argb(modifier(surface, "background")!!["color"]))
        assertNotEquals(argb(colors.accent), argb(modifier(surface, "background")!!["color"]))
        assertNull("and no ring", modifier(surface, "border"))

        rule.onNodeWithTag("nova-stage-primary-action").requestFocus()
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.FocusMillis.toLong() + 32)
        rule.waitForIdle()
        assertEquals("under focus the accent fill", argb(colors.accent), argb(modifier(surface, "background")!!["color"]))
        val ring = modifier(surface, "border")!!
        assertEquals("with the ring in its label's colour", argb(colors.onAccent), argb(ring["color"]))
        assertEquals(NovaPanelMetrics.FocusRingWidth, ring["width"] as Dp)
    }
}
