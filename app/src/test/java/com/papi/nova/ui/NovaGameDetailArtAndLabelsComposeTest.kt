package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A game with no hero art stands its card where the hero would be, rather than leaving the top of
 * the page bare (N23). Buttons wrap their labels between words instead of cutting them (C25), and
 * the return from Artwork Studio waits until Artwork's button holds focus (M5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaGameDetailArtAndLabelsComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aGameWithNoHeroArtStandsItsCardWhereTheHeroWouldBe() {
        val uiState = NovaGameDetailUiState.from(
            game = PolarisGame(id = "desktop", name = "Desktop", source = "manual"),
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
                sourceLabel = "Manual",
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
        rule.onNodeWithTag(NOVA_GAME_DETAIL_POSTER_STAND_IN_TAG).assertExists()
    }

    @Test
    fun theSharedActionButtonWrapsItsLabelInsteadOfCuttingIt() {
        // Robolectric measures text without real glyph widths, so the rule is read at the source.
        val components = File("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt").readText()
        val button = components.substringAfter("fun NovaActionButton(").substringBefore("fun NovaActionSurface(")
        assertTrue(button.contains("textAlign = TextAlign.Center,"))
        assertFalse("Artwork Studio's buttons cut their labels to one line (C25)", button.contains("maxLines = 1"))
        assertFalse(button.contains("TextOverflow.Ellipsis"))
    }

    @Test
    fun theGamePagesOwnActionsWrapToo() {
        val overview = File("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt").readText()
        val action = overview.substringAfter("private fun NovaGameDetailAction(").substringBefore("private fun LaunchProfileReviewNotice(")
        assertFalse("an action's label ended in an ellipsis at rest (C25)", action.contains("TextOverflow.Ellipsis"))
        assertFalse(action.contains("maxLines = 1"))
    }

    @Test
    fun theReturnFromArtworkWaitsUntilItsButtonHoldsFocus() {
        val content = File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText()
        val loop = content.substringAfter("if (from == NovaGameDetailDestination.ARTWORK").substringBefore("BoxWithConstraints(")
        assertFalse("a request that did not throw is not a button holding focus (M5)", loop.contains(".isSuccess"))
        assertTrue(loop.contains("if (artworkHoldsFocus) return@LaunchedEffect"))
        assertTrue(content.contains("onArtworkFocus = { artworkHoldsFocus = it }"))
    }

    @Test
    fun artworkStudioAnnouncesItsError() {
        val studio = File("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt").readText()
        val error = studio.substringAfter("state.error?.let {").substringBefore("// Pinned below both columns")
        assertTrue("the error was plain text a screen reader never said (C26)", error.contains("liveRegion = LiveRegionMode.Polite"))
    }
}
