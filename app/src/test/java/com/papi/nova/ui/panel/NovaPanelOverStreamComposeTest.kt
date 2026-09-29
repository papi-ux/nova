package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.STREAM_MIN_PANEL_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-game #12 with review finding 3: the solid floor over the stream belongs to the opening that
 * asked for it, the in-game Command Center, and not to every panel with the light scrim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelOverStreamComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Page(override val key: String) : NovaPage {
        override val title = key
    }

    private fun fillOver(panel: NovaPanelState): () -> Float {
        var fill = Float.NaN
        rule.setPanelContent {
            val surfaces = LocalNovaLibrarySurfaces.current
            CompositionLocalProvider(LocalNovaLibrarySurfaces provides surfaces.copy(panel = surfaces.panel.copy(alpha = 0.64f))) {
                NovaSurfacesLayer(
                    panel = panel,
                    states = emptyList(),
                    scrim = NovaScrim.Stream,
                    pageContent = { fill = LocalNovaLibrarySurfaces.current.panel.alpha },
                    onIdle = {},
                )
            }
        }
        rule.waitForIdle()
        return { fill }
    }

    @Test
    fun theCommandCenterOverTheStreamTakesTheSolidFloor() {
        val panel = NovaPanelState().apply { open(Page("command-center"), NovaEdge.Start, overStream = true) }
        val fill = fillOver(panel)
        assertTrue("at least the floor, less a colour's 8 bits", fill() >= STREAM_MIN_PANEL_ALPHA - 1f / 255f)
    }

    @Test
    fun anotherPanelWithTheLightScrimKeepsItsGlass() {
        val panel = NovaPanelState().apply { open(Page("companion-command-center"), NovaEdge.Start) }
        val fill = fillOver(panel)
        assertEquals(0.64f, fill(), 0.01f)
    }

    @Test
    fun theFloorBelongsToTheOpeningThatAskedForIt() {
        val panel = NovaPanelState()
        panel.open(Page("command-center"), NovaEdge.Start, overStream = true)
        panel.push(Page("mouse-mode"))
        assertTrue("its pages keep it", panel.overStream)
        panel.close()
        assertTrue("and so does its exit motion", panel.overStream)
        panel.push(Page("notice"))
        assertFalse("a panel opened without asking has none", panel.overStream)
    }
}
