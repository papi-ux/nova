package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 3: the solid floor meant for the Command Center over the stream was keyed on the
 * light scrim, which Play Setup's panel on the game page uses too, so it stopped following Menu
 * Opacity. It keeps the glass Menu Opacity chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupPanelGlassComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private object Plan : NovaPage {
        override val key = "play-setup-plan"
        override val title = "Play Setup"
    }

    @Test
    fun playSetupOverTheGamePageFollowsMenuOpacity() {
        val panel = NovaPanelState().apply { open(Plan) }
        var fill = Float.NaN
        rule.setPanelContent {
            val surfaces = LocalNovaLibrarySurfaces.current
            val glass = surfaces.copy(panel = surfaces.panel.copy(alpha = 0.64f))
            CompositionLocalProvider(LocalNovaLibrarySurfaces provides glass) {
                NovaPlaySetupPanel(panel = panel, onClose = {}, content = { fill = LocalNovaLibrarySurfaces.current.panel.alpha })
            }
        }
        rule.waitForIdle()
        assertEquals("the panel's fill is the one Menu Opacity chose", 0.64f, fill, 0.01f)
    }
}
