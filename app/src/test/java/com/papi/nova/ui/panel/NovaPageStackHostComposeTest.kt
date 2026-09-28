package com.papi.nova.ui.panel

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPageStackHostComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()
    private var closeRequests = 0
    private val shoulders = mutableListOf<NovaShoulder>()
    private var stateShown by mutableStateOf(false)
    private var stateBacks = 0
    private var stateRetries = 0
    private val split = NovaSplitConfirmState()
    private var name by mutableStateOf("")
    private var watcherSawLeave = false

    private fun setUp(): NovaTestKeys = rule.setPanelContent {
        Box(Modifier.fillMaxSize()) {
            // As in a panel window: the window holds focus in, so the state page beside the host
            // can take it.
            NovaPageStackHost(
                state = state,
                containFocus = false,
                onCloseRequest = { closeRequests++ },
                onShoulder = { shoulders += it },
            ) { page ->
                when (page.key) {
                    "rows" -> LazyColumn(state = listState) {
                        items(40) { i ->
                            NovaRow(
                                title = "Row $i",
                                onClick = { panel.push(TestPage("detail")) },
                                modifier = Modifier
                                    .then(if (i == 2) Modifier.novaInitialFocus() else Modifier)
                                    .novaRestorableFocus(i, i),
                            )
                        }
                    }
                    "detail" -> Column {
                        NovaRow(title = "Detail A", onClick = {})
                        NovaRow(title = "Detail B", onClick = { panel.push(TestPage("third")) }, modifier = Modifier.novaInitialFocus())
                    }
                    "deep" -> Column {
                        NovaTextField(value = name, onValueChange = { name = it }, label = "Name")
                        NovaSplitConfirm(label = "Delete", confirmLabel = "Delete now", onConfirm = {}, state = split)
                    }
                    "watcher" -> {
                        // Owner code that follows isTop in composition, as the spec's API invites.
                        LaunchedEffect(isTop) { if (!isTop) watcherSawLeave = true }
                        NovaRow(title = "Watcher", onClick = { panel.push(TestPage("above")) }, modifier = Modifier.novaInitialFocus())
                    }
                    else -> NovaRow(title = "Page ${page.key}", onClick = {})
                }
            }
            if (stateShown) {
                NovaStateScreen(
                    NovaStatePage.Problem(
                        key = "lost",
                        title = "Connection lost",
                        message = "The host stopped answering.",
                        primary = NovaAction("Reconnect") { stateRetries++ },
                        secondary = listOf(NovaAction("Close") { stateBacks++; stateShown = false }),
                    ),
                )
            }
        }
    }

    @Test
    fun openingFocusesTheMarkedElement() {
        state.open(TestPage("rows"))
        setUp()
        rule.onNodeWithText("Row 2").assertIsFocused()
    }

    @Test
    fun aPushFocusesTheNewPagesMarkedElement() {
        state.open(TestPage("rows"))
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(2, state.depth)
        rule.onNodeWithText("Detail B").assertIsFocused()
    }

    @Test
    fun aPopRestoresTheRememberedRowEvenOneScrolledOutOfTheList() {
        state.open(TestPage("rows"))
        val keys = setUp()
        repeat(28) { keys.press(NovaTestKeys.DOWN) }
        rule.onNodeWithText("Row 30").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.onNodeWithText("Detail B").assertIsFocused()

        // While the list is covered, scroll it back to the top, so row 30 is not composed on return.
        val rows = state.entryBelow(state.topEntry!!)!!
        rule.runOnIdle { rows.listState.requestScrollToItem(0) }

        keys.back()
        assertEquals(1, state.depth)
        rule.onNodeWithText("Row 30").assertIsFocused()
    }

    @Test
    fun bPopsExactlyOnePage() {
        state.open(TestPage("rows"))
        state.push(TestPage("detail"))
        state.push(TestPage("third"))
        val keys = setUp()
        keys.back()
        assertEquals(2, state.depth)
        assertEquals("detail", state.top?.key)
        rule.onNodeWithText("Detail B").assertIsFocused()
    }

    @Test
    fun bAtTheRootAsksToClose() {
        state.open(TestPage("rows"))
        val keys = setUp()
        keys.back()
        assertEquals(1, closeRequests)
        assertEquals(1, state.depth)
    }

    @Test
    fun theBackOrderUnwindsOneLevelAtATime() {
        state.open(TestPage("rows"))
        state.push(TestPage("deep"))
        val keys = setUp()

        // A state page takes focus and A, and answers B first with its own back action.
        stateShown = true
        rule.waitForIdle()
        rule.onNodeWithText("Reconnect").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("A reached the state page's primary", 1, stateRetries)
        keys.back()
        assertEquals(1, stateBacks)
        assertEquals("B ran the back action, not the primary again", 1, stateRetries)
        assertEquals(2, state.depth)

        // An armed split disarms and keeps the page.
        rule.onNodeWithText("Delete").requestFocus()
        keys.press(NovaTestKeys.CENTER)
        assertTrue(split.armed)
        keys.back()
        assertFalse(split.armed)
        assertEquals(2, state.depth)

        // An open field closes, and only the keyboard goes.
        val field = rule.onNodeWithContentDescription("Name")
        field.requestFocus()
        keys.press(NovaTestKeys.CENTER)
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        keys.back()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        assertEquals(2, state.depth)

        // Then one page, then the panel.
        keys.back()
        assertEquals(1, state.depth)
        assertEquals(0, closeRequests)
        keys.back()
        assertEquals(1, closeRequests)
    }

    @Test
    fun anOwnerPageFollowingIsTopSeesItLeaveWhenAPageIsPushed() {
        state.open(TestPage("watcher"))
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(2, state.depth)
        assertTrue("isTop is snapshot state, so a skipped owner page still sees it change", watcherSawLeave)
    }

    @Test
    fun shouldersAndStartActOnRelease() {
        state.open(TestPage("rows"))
        val keys = setUp()
        keys.down(KeyEvent.KEYCODE_BUTTON_R1)
        assertTrue("nothing on the press", shoulders.isEmpty())
        keys.up(KeyEvent.KEYCODE_BUTTON_R1)
        keys.press(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(listOf(NovaShoulder.Right, NovaShoulder.Left), shoulders)

        // The release of a Start press that began elsewhere does nothing.
        keys.up(KeyEvent.KEYCODE_BUTTON_START)
        assertEquals(0, closeRequests)
        keys.press(KeyEvent.KEYCODE_BUTTON_START)
        assertEquals(1, closeRequests)
    }

    @Test
    fun withNoShoulderHandlerL1AndR1PassToTheScreen() {
        state.open(TestPage("plain"))
        val outside = mutableListOf<Int>()
        val keys = rule.setPanelContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyUp) outside += it.nativeKeyEvent.keyCode
                        false
                    },
            ) {
                NovaPageStackHost(state = state, onCloseRequest = { closeRequests++ }) { page ->
                    NovaRow(title = "Page ${page.key}", onClick = {}, modifier = Modifier.novaInitialFocus())
                }
            }
        }
        keys.press(KeyEvent.KEYCODE_BUTTON_L1)
        keys.press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(
            "a Settings pane with no peers leaves L1 and R1 to the category stepping around it",
            listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1),
            outside,
        )
    }
}
