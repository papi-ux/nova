package com.papi.nova.ui.panel

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.NovaControllerHint
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var ends = 0
    private val split = NovaSplitConfirmState()
    private val surfaceChanges = mutableListOf<Boolean>()
    private var keys: NovaTestKeys? = null

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
                Column {
                    NovaRow(title = "Row ${page.key}", onClick = { rowActions++ }, modifier = Modifier.novaInitialFocus())
                    // As the Command Center's End Session: a split whose place focus comes back to.
                    if (page.key == "command-center") {
                        NovaSplitConfirm(
                            label = "End Session",
                            confirmLabel = "End now",
                            onConfirm = { ends++ },
                            state = split,
                            modifier = Modifier.novaRestorableFocus("end"),
                        )
                    }
                }
            },
            onIdle = {},
            hints = hints,
            onShoulder = onShoulder,
            onActiveSurfaceChange = { covered ->
                surfaceChanges += covered
                // As the window does: a press that began on one surface never finishes on the other.
                keys?.resetGate()
            },
        )
    }.also { keys = it }

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
    fun aStatePageOverAnArmedEndDisarmsItOwnsEveryInputAndGivesFocusBackToEnd() {
        panel.open(TestPage("command-center"))
        val keys = setUp()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("End Session").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertTrue(split.armed)

        states = listOf(lost)
        rule.waitForIdle()
        assertFalse("a split the state page covers disarms", split.armed)
        rule.onNodeWithText("Reconnect").assertIsFocused()
        // Hidden from accessibility: nothing on the covered panel can be found, let alone acted on.
        rule.onNodeWithText("Row command-center").assertDoesNotExist()
        rule.onNodeWithText("End Session").assertDoesNotExist()
        rule.onNodeWithText("Stay").assertDoesNotExist()

        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A belongs to the state page", 1, retries)
        assertEquals("never to the hidden panel", 0, ends + rowActions)

        keys.gatedPress(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("B follows the state page's own back", 1, stateBacks)
        assertTrue(panel.isOpen)
        rule.onNodeWithText("End Session").assertIsFocused()
        assertFalse(split.armed)
        assertEquals(0, ends)
    }

    @Test
    fun aPressThatBeganOnOneSurfaceNeverFinishesOnTheOther() {
        panel.open(TestPage("host"))
        val keys = setUp()
        rule.onNodeWithText("Row host").assertIsFocused()

        // B goes down on the panel and a state page covers it before the release.
        keys.gatedDown(KeyEvent.KEYCODE_BUTTON_B)
        states = listOf(lost)
        rule.waitForIdle()
        keys.gatedUp(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("the release of a press the state page never saw does not leave it", 0, stateBacks)
        rule.onNodeWithText("Reconnect").assertIsFocused()

        // Start goes down on the state page and the page goes before the release.
        keys.down(KeyEvent.KEYCODE_BUTTON_START)
        states = emptyList()
        rule.waitForIdle()
        rule.onNodeWithText("Row host").assertIsFocused()
        keys.up(KeyEvent.KEYCODE_BUTTON_START)
        assertTrue("the panel never saw that Start go down", panel.isOpen)

        // Start went down on the panel, a state page came and went, and then it was released.
        keys.down(KeyEvent.KEYCODE_BUTTON_START)
        states = listOf(lost)
        rule.waitForIdle()
        states = emptyList()
        rule.waitForIdle()
        keys.up(KeyEvent.KEYCODE_BUTTON_START)
        assertTrue("a press from before the state page does not close the panel after it", panel.isOpen)
        assertEquals(listOf(false, true, false, true, false), surfaceChanges)
    }

    @Test
    fun aPostedBusyPageOwnsThePanelsInputBeforeItShows() {
        panel.open(TestPage("host"))
        val keys = setUp()
        rule.onNodeWithText("Row host").assertIsFocused()
        rule.mainClock.autoAdvance = false

        states = listOf(NovaStatePage.Busy(key = "saving", title = "Saving", message = MutableStateFlow("")))
        rule.frames(2)
        rule.onNodeWithText("Saving").assertDoesNotExist()
        rule.onNodeWithText("Row host").assertDoesNotExist()
        keys.press(NovaTestKeys.CENTER)
        keys.press(KeyEvent.KEYCODE_BUTTON_START)
        assertEquals("the panel acts on nothing while a Busy page waits to show", 0, rowActions)
        assertTrue(panel.isOpen)

        // Done before it ever showed: the panel is the active surface again.
        states = emptyList()
        rule.frames(4)
        rule.onNodeWithText("Row host").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, rowActions)
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
