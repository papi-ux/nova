package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.preference.PreferenceManager
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Command Center's HUD position while Nova HUD is off (#482).
 *
 * With the HUD off there is no HUD to ask where it sits, so the stream read no corner and the row
 * offered "Custom ✓" before the four corners, though the saved position was the top left and
 * nothing custom was set. The stream now reads the saved corner from the preferences whether the
 * HUD shows or not, and the row shows it as it does with the HUD on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi", shadows = [ShadowMoonBridge::class])
class NovaCommandCenterHudPositionOffComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(rule.activity)

    @After fun clear() {
        prefs.edit().clear().commit()
    }

    private fun save(x: Float?, y: Float?) {
        prefs.edit().clear().putBoolean("nova_polaris_hud", false).apply {
            if (x != null) putFloat("nova_polaris_hud_position_x_fraction", x)
            if (y != null) putFloat("nova_polaris_hud_position_y_fraction", y)
        }.commit()
    }

    /** A stream with the HUD off: its HUD was never made. */
    private fun cornerReadByTheStream(): NovaHudCorner? {
        val game = Robolectric.buildActivity(Game::class.java).get()
        return game.novaHudPositionCorner
    }

    @Test fun withTheHudOffTheStreamReadsTheSavedCorner() {
        save(0f, 0f)
        assertEquals(NovaHudCorner.TOP_LEFT, cornerReadByTheStream())
        save(1f, 1f)
        assertEquals(NovaHudCorner.BOTTOM_RIGHT, cornerReadByTheStream())
        save(null, null)
        assertEquals("nothing saved: the HUD shows at the top left", NovaHudCorner.TOP_LEFT, cornerReadByTheStream())
        save(0.4f, 0.6f)
        assertNull("a dragged place is still between the corners", cornerReadByTheStream())
    }

    @Test fun theRowShowsTheSavedCornerAndNoCustomWhileTheHudIsOff() {
        save(0f, 0f)
        val preview = NovaQuickMenuUiState.preview(rule.activity)
        val state = MutableStateFlow(preview.copy(
            hudMode = preview.hudMode.copy(enabled = false),
            hudPositionCorner = cornerReadByTheStream(),
        ))
        val panel = NovaPanelState().apply { open(CommandCenterPage.Root("Command Center")) }
        rule.setPanelContent {
            NovaPageStackHost(panel, containFocus = false) {
                NovaQuickMenuContent(state, NovaQuickMenuCallbacks())
            }
        }
        rule.waitForIdle()
        val row = rule.onNodeWithTag("nova-cc-hud-position").fetchSemanticsNode().config
        assertEquals(rule.activity.getString(R.string.nova_cc_hud_top_left), row[SemanticsProperties.StateDescription])
        assertFalse("no Custom choice is offered", row[SemanticsProperties.Text].any {
            it.text == rule.activity.getString(R.string.nova_cc_hud_dragged)
        })
        rule.onNodeWithText(rule.activity.getString(R.string.nova_cc_hud_dragged), useUnmergedTree = true).assertDoesNotExist()
    }
}
