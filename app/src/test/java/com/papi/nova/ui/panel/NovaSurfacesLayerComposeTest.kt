package com.papi.nova.ui.panel

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.NovaControllerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The content of a NovaPanelWindow: the panel frame with its stack, and the state pages above it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSurfacesLayerComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private var states by mutableStateOf(emptyList<NovaStatePage>())
    private val shoulders = mutableListOf<NovaShoulder>()
    private var rowActions = 0
    private var retries = 0
    private var stateBacks = 0

    private val closeLost = NovaAction("Close") {
        stateBacks++
        states = emptyList()
    }

    private val lost = NovaStatePage.Problem(
        key = "lost",
        title = "Connection lost",
        message = "The host stopped answering.",
        primary = NovaAction("Reconnect") { retries++ },
        back = NovaProblemBack.Close(closeLost),
        secondary = listOf(closeLost),
    )

    private fun setUp(
        hints: List<NovaControllerHint> = emptyList(),
        onShoulder: ((NovaShoulder) -> Unit)? = null,
    ): NovaTestKeys = rule.setPanelContent {
        NovaSurfacesLayer(
            panel = panel,
            states = states,
            scrim = NovaScrim.None,
            pageContent = { page ->
                NovaRow(title = "Row ${page.key}", onClick = { rowActions++ }, modifier = Modifier.novaInitialFocus())
            },
            onIdle = {},
            hints = hints,
            onShoulder = onShoulder,
        )
    }

    @Test
    fun aWindowPanelShowsTheOwnersHintsAndTakesTheShoulders() {
        panel.open(TestPage("options"))
        val keys = setUp(hints = listOf(NovaControllerHint("R1", "System")), onShoulder = { shoulders += it })

        rule.onNodeWithText("System").assertExists()
        keys.press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(NovaShoulder.Right), shoulders)
    }

    @Test
    fun aStatePageOverAnOpenPanelTakesFocusAndAAndGivesThemBack() {
        panel.open(TestPage("host"))
        val keys = setUp()
        rule.onNodeWithText("Row host").assertIsFocused()

        states = listOf(lost)
        rule.waitForIdle()
        rule.onNodeWithText("Reconnect").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A runs the recovery action", 1, retries)
        assertEquals("and never the covered row", 0, rowActions)

        keys.back()
        assertEquals(1, stateBacks)
        assertTrue("B left the state page, not the panel", panel.isOpen)
        rule.onNodeWithText("Row host").assertIsFocused()
    }

    @Test
    fun aPanelOpenedUnderAStatePageLeavesItBAndFocus() {
        states = listOf(lost)
        val keys = setUp()
        rule.onNodeWithText("Reconnect").assertIsFocused()

        rule.runOnIdle { panel.open(TestPage("notice")) }
        rule.waitForIdle()
        rule.onNodeWithText("Reconnect").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, retries)
        assertEquals(0, rowActions)

        keys.back()
        assertEquals("the state page's back ran, though the panel's handlers registered later", 1, stateBacks)
        assertTrue(panel.isOpen)
        rule.onNodeWithText("Row notice").assertIsFocused()
    }
}
