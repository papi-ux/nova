package com.papi.nova.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.librarySurfaces
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stream's launch progress with and without the artwork its game's page carried. The stage
 * the session reports is named the same either way, and nothing carried outlasts its motion
 * unless it is the faint hero the overlay leaves with.
 *
 * UNRUN: written for Nova #385 and not executed in the authoring session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaLaunchTransitionComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val rtsp = NovaSessionProgressUiState.from("rtsp")

    private fun carried(kind: NovaLaunchArtwork.Kind) = NovaLaunchArtwork(
        kind,
        Bitmap.createBitmap(54, 76, Bitmap.Config.ARGB_8888),
        0xFF000096.toInt(),
    )

    @Test
    fun withNothingCarriedTheOverlayIsTheOneItWas() {
        rule.setPanelContent { NovaSessionProgressOverlayContent(state = rtsp) }
        rule.onNodeWithText("RTSP SESSION").assertExists()
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_POSTER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_HERO_TAG).assertDoesNotExist()
    }

    @Test
    fun aCarriedPosterExpandsAwayWithinItsTimeAndTheStageStaysNamed() {
        rule.mainClock.autoAdvance = false
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(state = rtsp, artwork = carried(NovaLaunchArtwork.Kind.POSTER))
        }
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_POSTER_TAG).assertExists()
        rule.mainClock.advanceTimeBy(NOVA_LAUNCH_POSTER_EXPAND_MS + 100L)
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_POSTER_TAG).assertDoesNotExist()
        rule.onNodeWithText("RTSP SESSION").assertExists()
    }

    @Test
    fun withAnimationOffNoCardExpandsAndTheStageIsThereAtOnce() {
        rule.mainClock.autoAdvance = false
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(
                state = rtsp,
                artwork = carried(NovaLaunchArtwork.Kind.POSTER),
                animationsOff = true,
            )
        }
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_POSTER_TAG).assertDoesNotExist()
        rule.onNodeWithText("RTSP SESSION").assertExists()
    }

    @Test
    fun aCarriedHeroStaysFaintlyBehindTheStage() {
        rule.mainClock.autoAdvance = false
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(state = rtsp, artwork = carried(NovaLaunchArtwork.Kind.HERO))
        }
        rule.mainClock.advanceTimeBy(NOVA_LAUNCH_HERO_SETTLE_MS + 100L)
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_HERO_TAG).assertExists()
        rule.onNodeWithText("RTSP SESSION").assertExists()
    }

    @Test
    fun highContrastCarriesNoArtworkBehindItsText() {
        rule.setPanelContent {
            CompositionLocalProvider(
                LocalNovaLibrarySurfaces provides LocalNovaComposeColors.current.librarySurfaces(NovaThemeManager.THEME_HIGH_CONTRAST),
            ) {
                NovaSessionProgressOverlayContent(state = rtsp, artwork = carried(NovaLaunchArtwork.Kind.HERO))
            }
        }
        rule.onNodeWithTag(NOVA_LAUNCH_ARTWORK_HERO_TAG).assertDoesNotExist()
        rule.onNodeWithText("RTSP SESSION").assertExists()
    }
}
