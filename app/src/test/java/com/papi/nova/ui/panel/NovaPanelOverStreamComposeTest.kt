package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.Game
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.utils.ExternalDisplayControlHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every actual window keeps the selected opacity, including stream notices and confirmations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelOverStreamComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Page(override val key: String) : NovaPage {
        override val title = key
    }

    private lateinit var surfaces: NovaSurfaces

    @After
    fun tearDown() {
        if (::surfaces.isInitialized) rule.runOnUiThread { surfaces.dispose() }
    }

    /** The stream's window, for a Game that cannot show one, so it draws in this composition. */
    private fun streamWindow(): NovaSurfaces {
        val game = Mockito.mock(Game::class.java)
        Mockito.`when`(game.isFinishing).thenReturn(true)
        return NovaSurfaces(NovaWindowPlacement.Stream(game))
    }

    /** A companion display whose deck is hidden, so it never opens a window of its own either. */
    private fun companionDisplay(): NovaSurfaces =
        NovaSurfaces(NovaWindowPlacement.Companion(Mockito.mock(ExternalDisplayControlHost::class.java)) {})

    private fun draw(on: NovaSurfaces): NovaSurfaces {
        surfaces = on
        rule.setPanelContent {
            val theme = LocalNovaLibrarySurfaces.current
            // Menu Opacity at 64%: glass a game's own text reads through.
            CompositionLocalProvider(LocalNovaLibrarySurfaces provides theme.copy(panel = theme.panel.copy(alpha = GLASS))) {
                NovaSurfacesWindowContent(on)
            }
        }
        rule.waitForIdle()
        return on
    }

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun fill(): Float =
        rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey)).fetchSemanticsNode().config[NovaPanelPlacementKey].fill.alpha

    private fun assertGlass(what: String) = assertEquals("$what keeps Menu Opacity's glass", GLASS, fill(), 0.01f)

    /** Dialog.displayDialog's non-blocking notice, which Game posts over the stream. */
    private fun notice() = NovaCommonPage.Notice(
        key = "nova-legacy-dialog-1",
        title = "Connection warning",
        message = "The host is not answering.",
        closeLabel = "Close",
    )

    /** The on-stream keys editor's Add Keys, a list of key names presented in Game's window. */
    private fun addKeys() = NovaCommonPage.MultiChoice(
        key = "add-keys",
        title = "Add Keys",
        options = listOf(NovaOption(0, "Esc"), NovaOption(1, "F11")),
        selected = emptySet(),
        doneLabel = "Add",
        onDone = {},
    )

    /** What quit() presents after a Space's Disconnect has closed the Command Center. */
    private fun leaveSpace() = NovaCommonPage.Confirm(
        key = "leave-space",
        title = "Leave this Space?",
        message = AnnotatedString("The Space keeps running on the host."),
        stayLabel = "Stay",
        actionLabel = "Leave Space",
        destructive = true,
        onConfirm = {},
    )

    @Test
    fun theCommandCenterAndItsPagesKeepTheGlassOverTheStream() {
        val stream = draw(streamWindow())
        ui { stream.open(Page("command-center"), NovaEdge.Start) }
        assertGlass("the Command Center")
        ui { stream.panel.push(Page("mouse-mode")) }
        assertGlass("a page it pushes")
    }

    @Test
    fun aNoticePostedOverTheStreamKeepsTheGlass() {
        val stream = draw(streamWindow())
        ui { stream.present(notice()) }
        assertGlass("a notice from Dialog.displayDialog")
    }

    @Test
    fun theKeysEditorsAddKeysKeepsTheGlass() {
        val stream = draw(streamWindow())
        ui { stream.present(addKeys()) }
        assertGlass("Add Keys")
    }

    @Test
    fun theConfirmASpacesDisconnectLeadsToKeepsTheGlass() {
        val stream = draw(streamWindow())
        ui { stream.open(Page("command-center"), NovaEdge.Start) }
        // Disconnect closes the Command Center, then quit() asks on a panel that opens afresh.
        ui { stream.panel.close() }
        ui { stream.present(leaveSpace()) }
        assertGlass("the Leave Space confirm")
    }

    @Test
    fun theCompanionDisplayKeepsMenuOpacitysGlass() {
        val companion = draw(companionDisplay())
        ui { companion.open(Page("command-center"), NovaEdge.Start) }
        assertGlass("the Command Center on a companion display")
        ui { companion.present(notice()) }
        assertGlass("a notice there")
    }

    private companion object {
        const val GLASS = 0.64f
    }
}
