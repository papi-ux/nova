package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPanelPlacementKey
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.NovaSurfacesWindowContent
import com.papi.nova.ui.panel.NovaWindowPlacement
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.ExternalDisplayControlHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 2 (in-game #4): the HUD rows say "the HUD is under this panel" when, and only
 * when, the panel lies over the HUD: an edge panel over the stream, on the HUD's own display,
 * whose width holds the HUD's left edge. The caption asked only whether the HUD sat within 4dp of
 * its corner, so it showed on the companion display, whose stream and HUD are on the other screen,
 * and not for a HUD dragged along the top under the panel. The Command Center is drawn in its
 * window's content as the window composes it, on a landscape handheld 800dp wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp-land-xhdpi")
class NovaCommandCenterHudCaptionComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var surfaces: NovaSurfaces
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    @After
    fun tearDown() {
        if (::surfaces.isInitialized) rule.runOnUiThread { surfaces.dispose() }
    }

    private fun streamWindow(): NovaSurfaces {
        val game = Mockito.mock(Game::class.java)
        Mockito.`when`(game.isFinishing).thenReturn(true)
        return NovaSurfaces(NovaWindowPlacement.Stream(game))
    }

    private fun companionDisplay(): NovaSurfaces =
        NovaSurfaces(NovaWindowPlacement.Companion(Mockito.mock(ExternalDisplayControlHost::class.java)) {})

    private val density get() = rule.activity.resources.displayMetrics.density

    /** The HUD's corner as NovaStreamHud lays it out when it never stored a position. */
    private val corner get() = NovaCommandCenterHudCorner.leftPx(Float.NaN, density, television = false)

    private fun hudAt(x: Float): (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { s ->
        s.copy(
            hudMode = s.hudMode.copy(enabled = true, hudLeftPx = x),
            hudOpacity = s.hudOpacity.copy(enabled = true, hudLeftPx = x),
        )
    }

    private fun open(on: NovaSurfaces, hudLeftPx: Float) {
        surfaces = on
        state = MutableStateFlow(hudAt(hudLeftPx)(NovaQuickMenuUiState.preview(rule.activity)))
        rule.setPanelContent { NovaSurfacesWindowContent(on) }
        rule.runOnUiThread {
            on.open(CommandCenterPage.Root("Command Center"), NovaEdge.Start) { page ->
                if (page is CommandCenterPage.Root) NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks())
            }
        }
        rule.waitForIdle()
    }

    private fun move(hudLeftPx: Float) {
        state.value = hudAt(hudLeftPx)(state.value)
        rule.waitForIdle()
    }

    /** Where the panel's inner edge is, in the window's pixels, from the panel the frame draws. */
    private fun panelEdge(): Float =
        rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey)).fetchSemanticsNode().boundsInRoot.right

    private fun string(id: Int) = rule.activity.getString(id)

    private fun assertUnder(yes: Boolean, why: String) {
        listOf(R.string.nova_cc_hud_mode_under_panel, R.string.nova_cc_hud_opacity_under_panel).forEach { id ->
            val caption = rule.onNodeWithText(string(id))
            if (yes) caption.assertExists(why) else caption.assertDoesNotExist()
        }
        if (!yes) rule.onNodeWithText(string(R.string.nova_quick_menu_hud_mode_caption)).assertExists(why)
    }

    @Test
    fun aHudInItsCornerIsUnderTheCommandCenterOverTheStream() {
        open(streamWindow(), hudLeftPx = corner)
        assertUnder(true, "the HUD's own corner, the top start, is under the panel")
    }

    @Test
    fun aHudDraggedAlongTheTopIsUnderThePanelUntilItPassesItsEdge() {
        open(streamWindow(), hudLeftPx = corner)
        val edge = panelEdge()
        move(edge - 40f)
        assertUnder(true, "a HUD dragged along the top but still within the panel's width is under it")
        move(edge + 40f)
        assertUnder(false, "past the panel's edge the HUD is in sight")
    }

    @Test
    fun theCompanionDisplayNeverSaysTheHudIsUnderIt() {
        open(companionDisplay(), hudLeftPx = corner)
        assertUnder(false, "on the companion display the HUD is with the stream, on the other screen")
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port-xhdpi")
    fun aPortraitSheetLeavesTheHudsCornerClear() {
        open(streamWindow(), hudLeftPx = corner)
        assertUnder(false, "a sheet rises from the bottom and leaves the top clear")
    }
}
