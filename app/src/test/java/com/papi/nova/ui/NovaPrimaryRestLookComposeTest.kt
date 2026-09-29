package com.papi.nova.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shadows.ShadowComposeImageBitmap
import com.papi.nova.shadows.ShadowDrawRecordingCanvas
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaComposeColors
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.GameShortcutPinState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Launch and the Stage hero's Review & Launch rest as tiles and fill only under focus, with the one
 * focus ring (M6). The look tests had read the label's colour, and then what the focus look was
 * given, which is the same with focus and without; a surface resting in the accent, or a focus look
 * that never drew its focus, passed them. These read what is drawn: the screen is drawn into a
 * canvas that keeps each fill and each ring with its size, colour and stroke, at rest and under
 * focus, in legacy graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp", shadows = [ShadowDrawRecordingCanvas::class, ShadowComposeImageBitmap::class])
class NovaPrimaryRestLookComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var colors: NovaComposeColors
    private lateinit var surfaces: NovaLibrarySurfaces

    /** One thing drawn: its size in pixels, its colour, and its stroke when it is a ring. */
    private data class Mark(val width: Float, val height: Float, val color: Int, val stroke: Float?) {
        override fun toString() = "%.0fx%.0f #%08x%s".format(width, height, color, stroke?.let { " stroke %.1f".format(it) }.orEmpty())
    }

    /** Everything on screen drawn once, as it is now. */
    private class Drawing(val fills: List<Mark>, val rings: List<Mark>) {
        override fun toString() = "fills $fills, rings $rings"
    }

    private fun drawn(): Drawing {
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        // The legacy canvas keeps what is drawn on it and paints nothing: no bitmap under it.
        val canvas = Canvas()
        val shadow = Shadow.extract<ShadowDrawRecordingCanvas>(canvas)
        shadow.setWidth(root.width)
        shadow.setHeight(root.height)
        rule.runOnUiThread { root.draw(canvas) }
        val fills = mutableListOf<Mark>()
        val rings = mutableListOf<Mark>()
        fun keep(width: Float, height: Float, paint: Paint) {
            val mark = Mark(width, height, paint.color, paint.strokeWidth.takeIf { paint.style == Paint.Style.STROKE })
            if (mark.stroke != null) rings += mark else fills += mark
        }
        shadow.roundRects.forEach { keep(it.rect.width(), it.rect.height(), it.paint) }
        repeat(shadow.rectPaintHistoryCount) { i ->
            val event = shadow.getDrawnRect(i)
            keep(event.rect.width(), event.rect.height(), event.paint)
        }
        // A focus ring is a stroke along a path, the shape's own outline.
        repeat(shadow.pathPaintHistoryCount) { i ->
            val paint = shadow.getDrawnPathPaint(i)
            if (paint.style == Paint.Style.STROKE) rings += Mark(Float.NaN, Float.NaN, paint.color, paint.strokeWidth)
        }
        return Drawing(fills, rings)
    }

    private fun size(tag: String): IntSize = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size

    // Colours as drawn, 8 bits a channel, within the rounding a fill animated to its end keeps.
    private fun near(drawn: Int, colour: Color): Boolean {
        val want = colour.toArgb()
        return (0..3).all { shift -> abs((drawn shr (shift * 8) and 0xFF) - (want shr (shift * 8) and 0xFF)) <= 2 }
    }

    private fun Mark.sized(size: IntSize, slack: Float = 1.5f) = abs(width - size.width) <= slack && abs(height - size.height) <= slack

    private val ringPx: Float get() = with(rule.density) { NovaPanelMetrics.FocusRingWidth.toPx() }

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
                playFocusRequester = remember { FocusRequester() },
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
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.FocusMillis.toLong() + 32)
        rule.waitForIdle()
        val launch = rule.onNodeWithTag("nova-game-detail-primary")
        launch.assertIsFocused()
        val launchSize = size("nova-game-detail-primary")
        val focused = drawn()
        val focusedFill = focused.fills.filter { it.sized(launchSize) }
        assertTrue("under focus the accent fill, as big as Launch: $focused", focusedFill.any { near(it.color, colors.accent) })
        assertTrue(
            "with the one ring, in its label's colour, as wide as every ring: $focused",
            focused.rings.count { near(it.color, colors.onAccent) && abs(it.stroke!! - ringPx * 2) <= 0.5f } == 1,
        )

        rule.onNodeWithContentDescription(context.getString(R.string.nova_play_setup_title)).requestFocus()
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.FocusMillis.toLong() + 32)
        rule.waitForIdle()
        launch.assertIsNotFocused()
        val rest = drawn()
        val restFill = rest.fills.filter { it.sized(launchSize) }
        assertTrue("at rest the tile's own fill: $rest", restFill.any { near(it.color, surfaces.control) })
        assertFalse("never the accent, a second focus beside the real one: $rest", restFill.any { near(it.color, colors.accent) })
        assertFalse("and no ring of its own: $rest", rest.rings.any { near(it.color, colors.onAccent) })
    }

    @Test
    fun theStageHerosReviewAndLaunchRestsOnTheScrimAndFillsWithTheOneRingUnderFocus() {
        val games = listOf(
            PolarisGame(id = "control", name = "Control", source = "steam"),
            PolarisGame(id = "portal", name = "Portal", source = "steam"),
        )
        val keys = rule.setPanelContent {
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
        val surface = size("nova-stage-primary-action-surface")
        // A ring drawn along the surface's edge, inside it: its stroke centred half a ring in.
        fun Mark.ringOfTheSurface() = stroke != null && abs(width + stroke - surface.width) <= 1.5f && abs(height + stroke - surface.height) <= 1.5f

        rule.onNodeWithTag("nova-stage-primary-action").assertIsNotFocused()
        val rest = drawn()
        assertTrue("at rest the artwork scrim: $rest", rest.fills.any { it.sized(surface) && near(it.color, surfaces.focusedArtworkScrim) })
        assertFalse("not the accent: $rest", rest.fills.any { it.sized(surface) && near(it.color, colors.accent) })
        assertFalse("and no ring: $rest", rest.rings.any { it.ringOfTheSurface() })

        rule.onNodeWithTag("nova-stage-primary-action").requestFocus()
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.FocusMillis.toLong() + 32)
        rule.waitForIdle()
        val focused = drawn()
        assertTrue("under focus the accent fill: $focused", focused.fills.any { it.sized(surface) && near(it.color, colors.accent) })
        val rings = focused.rings.filter { it.ringOfTheSurface() }
        assertEquals("one ring, inside the surface's edge: $focused", 1, rings.size)
        assertTrue("in its label's colour: $focused", near(rings.single().color, colors.onAccent))
        assertEquals("as wide as every ring", ringPx, rings.single().stroke!!, 0.5f)

        // Focus gone again, down to the posters, the ring goes with it.
        rule.onNodeWithTag("nova-stage-primary-action").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.FocusMillis.toLong() + 32)
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-primary-action").assertIsNotFocused()
        val after = drawn()
        assertFalse("no ring once focus has gone: $after", after.rings.any { it.ringOfTheSurface() })
        assertTrue("and the scrim again: $after", after.fills.any { it.sized(surface) && near(it.color, surfaces.focusedArtworkScrim) })
    }
}
