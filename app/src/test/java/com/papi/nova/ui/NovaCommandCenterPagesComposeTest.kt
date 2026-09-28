package com.papi.nova.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Command Center on the panel foundation: its root page, the End Session split in its header,
 * and the pages it pushes. Keys go in as Center, as the panel window's gate delivers A.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterPagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private var ended = 0
    private var dismissed = 0
    private val chosenModes = mutableListOf<Int>()
    private val sentKeys = mutableListOf<String>()
    private val serverCommandRuns = mutableListOf<Int>()
    private val switches = mutableListOf<Boolean>()
    private val quickKeys = mutableListOf<NovaQuickMenuActionId>()

    private val callbacks = NovaQuickMenuCallbacks(
        onDismiss = { dismissed++ },
        onEndStream = { ended++ },
        onQuickKey = { quickKeys += it },
        onControlAction = { id ->
            if (id == NovaQuickMenuActionId.MOUSE_MODE) {
                panel.push(
                    NovaMouseModeChoices.page(
                        title = "Mouse mode",
                        options = NovaMouseModeChoices.options(
                            modeNames = listOf("Direct", "Relative", "Track pad (Natural)", "Track pad (Gaming)", "Disabled"),
                            onExternalDisplay = false,
                            externalModes = emptySet(),
                            localCursorLabel = "Toggle local cursor",
                        ),
                        current = 3,
                        onChoose = { chosenModes += it },
                    ),
                )
            }
        },
        onSessionAction = { id ->
            when (id) {
                NovaQuickMenuActionId.MORE_KEYS -> panel.push(keysPage())
                NovaQuickMenuActionId.MORE_CONTROLS -> panel.push(moreControlsPage())
                else -> Unit
            }
        },
    )

    private fun keysPage() = CommandCenterPage.Keys(
        "More Keys",
        listOf(
            CommandCenterSection(
                null,
                listOf("Esc", "F11", "Insert").map { label ->
                    NovaMenuItem.Action(key = label, label = label, onClick = { sentKeys += label })
                } + NovaMenuItem.Destructive(
                    key = NovaCommandCenterKeys.CLOSE_APP_KEY,
                    label = "Alt + F4",
                    confirmLabel = "Close App",
                    consequence = "Closes the focused window on the host.",
                    onConfirm = { sentKeys += "Alt + F4" },
                ),
            ),
        ),
    )

    private fun moreControlsPage() = CommandCenterPage.MoreControls(
        "More Controls",
        listOf(
            CommandCenterSection(
                "Host",
                listOf(
                    NovaMenuItem.Action(
                        key = "server-commands",
                        label = "Server Commands",
                        disabledReason = "This host offers no server commands.",
                        onClick = { serverCommandRuns += -1 },
                    ),
                ),
            ),
            CommandCenterSection(
                "Touch and screen",
                listOf(
                    NovaMenuItem.Value(
                        key = "zoom",
                        label = "Pan and zoom",
                        options = listOf(NovaOption(false, "Off"), NovaOption(true, "On")),
                        current = false,
                        onChange = { switches += it },
                    ),
                ),
            ),
        ),
    )

    private fun open(): NovaTestKeys {
        val state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity))
        panel.open(CommandCenterPage.Root("Command Center"))
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    when (page) {
                        is CommandCenterPage.Root -> NovaQuickMenuContent(state = state, callbacks = callbacks)
                        is CommandCenterPage.Listing -> CommandCenterListingPage(page)
                        else -> Unit
                    }
                }
            }
        }
        rule.waitForIdle()
        return keys
    }

    private fun focus(text: String) {
        rule.onNodeWithText(text).requestFocus()
        rule.waitForIdle()
    }

    @Test
    fun theRootOpensOnTheSessionStripAndLeavesEveryPageToThePanel() {
        open()
        assertEquals(1, panel.depth)
        assertTrue(panel.top is CommandCenterPage.Root)
        rule.onNodeWithText("Close").assertExists()
        rule.onNodeWithText("Disconnect").assertExists()
        rule.onNodeWithText("End Session").assertExists()
    }

    @Test
    fun endSessionArmsInPlaceAndCloseAndDisconnectMakeRoom() {
        val keys = open()
        focus("End Session")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)

        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithText("Close").assertDoesNotExist()
        rule.onNodeWithText("Disconnect").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(com.papi.nova.R.string.nova_cc_end_session_consequence)).assertExists()

        keys.back()
        rule.frames(16)
        assertEquals("B disarms and never ends", 0, ended)
        assertEquals("B disarms before it closes anything", 1, panel.depth)
        rule.onNodeWithText("Close").assertExists()
        rule.onNodeWithText("End Session").assertIsFocused()
    }

    @Test
    fun endSessionIsAButtonOfTheHeaderRowAndItsPairSpansTheRow() {
        val keys = open()
        val close = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        val disconnect = rule.onNodeWithText("Disconnect").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithText("End Session").getUnclippedBoundsInRoot()
        assertEquals("as tall as the buttons beside it", close.height.value, end.height.value, 0.5f)
        assertEquals("its third of the row", disconnect.width.value, end.width.value, 0.5f)
        assertEquals("on their line", close.top.value, end.top.value, 0.5f)

        focus("End Session")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val confirm = rule.onNodeWithText("End Session").getUnclippedBoundsInRoot()
        assertEquals("armed, the pair starts where Close did", close.left.value, stay.left.value, 0.5f)
        assertEquals("and ends where End Session did", end.right.value, confirm.right.value, 0.5f)
        assertEquals("each half a button's height", close.height.value, stay.height.value, 0.5f)
    }

    @Test
    fun endingNeedsRightThenAAfterTheGuard() {
        val keys = open()
        rule.mainClock.autoAdvance = false
        focus("End Session")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        assertEquals("A on Stay is safe", 0, ended)

        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        keys.press(NovaTestKeys.RIGHT)
        rule.advance(450)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertEquals(1, ended)
    }

    @Test
    fun mouseModeIsAChoicePageOnTheCurrentModeAndReturnsToItsRow() {
        val keys = open()
        focus("Mouse")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)

        assertEquals("the Command Center stays open under the page", 2, panel.depth)
        rule.onNodeWithText("Track pad (Gaming)").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Disabled").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)

        assertEquals(listOf(4), chosenModes)
        assertEquals(1, panel.depth)
        rule.onNodeWithText("Mouse").assertIsFocused()
    }

    @Test
    fun moreKeysOpensTheKeyListAndAKeyClosesThePanelBeforeItIsSent() {
        val keys = open()
        focus("More Keys")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)

        assertTrue(panel.top is CommandCenterPage.Keys)
        rule.onNodeWithText("Esc").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()

        assertFalse(panel.isOpen)
        assertEquals(listOf("Esc"), sentKeys)
    }

    @Test
    fun moreControlsShowsWhyARowIsOffAndSwitchesInPlace() {
        val keys = open()
        focus("More Controls")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)

        assertTrue(panel.top is CommandCenterPage.MoreControls)
        rule.onNodeWithText("This host offers no server commands.").assertExists()
        // The page opens on its first row that can act; the disabled one above stays reachable.
        rule.onNodeWithText("Pan and zoom").assertIsFocused()
        keys.press(NovaTestKeys.UP)
        rule.onNodeWithText("Server Commands").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        assertTrue("a disabled row swallows A", serverCommandRuns.isEmpty())
        assertTrue(panel.top is CommandCenterPage.MoreControls)

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.RIGHT)
        rule.frames(4)
        assertEquals(listOf(true), switches)
        assertTrue("a setting changes in its row and the page stays", panel.top is CommandCenterPage.MoreControls)

        keys.back()
        rule.frames(16)
        assertEquals(1, panel.depth)
        rule.onNodeWithText("More Controls").assertIsFocused()
    }

    @Test
    fun externalDisplaysKeepTheTouchpadModesByTheirOriginalIndexes() {
        val options = NovaMouseModeChoices.options(
            modeNames = listOf("Direct", "Relative", "Track pad (Natural)", "Track pad (Gaming)", "Disabled"),
            onExternalDisplay = true,
            externalModes = setOf("Track pad (Natural)", "Track pad (Gaming)", "Disabled"),
            localCursorLabel = "Toggle local cursor",
        )
        assertEquals(listOf(2, 3, 4, NovaMouseModeChoices.LocalCursor), options.map { it.value })
        assertEquals("Toggle local cursor", options.last().label)
    }

    @Test
    fun altF4OnTheKeysPageSplitsAndOnlyARightAClosesTheApp() {
        val keys = open()
        focus("More Keys")
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        focus("Alt + F4")

        rule.mainClock.autoAdvance = false
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithText("Closes the focused window on the host.").assertExists()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        assertTrue("A on Stay is safe, and one A never sends Alt + F4", sentKeys.isEmpty())
        assertTrue(panel.top is CommandCenterPage.Keys)

        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        keys.press(NovaTestKeys.RIGHT)
        rule.advance(450)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)
        rule.mainClock.autoAdvance = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()

        assertEquals(listOf("Alt + F4"), sentKeys)
        assertFalse("the panel closes before the keys go, as every key's does", panel.isOpen)
    }

    @Test
    fun altF4InTheRootGridSplitsAcrossItsRowAndOneANeverSendsIt() {
        val keys = open()
        val altF4 = rule.activity.getString(com.papi.nova.R.string.game_menu_send_keys_alt_f4)
        val esc = rule.activity.getString(com.papi.nova.R.string.game_menu_send_keys_esc)
        focus(altF4)

        rule.mainClock.autoAdvance = false
        // Arm, Stay, arm: mashed A never sends it, and leaves the pair armed on Stay.
        repeat(3) {
            keys.press(NovaTestKeys.CENTER)
            rule.frames(16)
        }
        assertTrue("mashed A never sends it", quickKeys.isEmpty())
        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithText(rule.activity.getString(com.papi.nova.R.string.nova_cc_alt_f4_consequence)).assertExists()
        // Armed, the pair takes its row: the keys beside Alt + F4 step aside, the rows around it stay.
        rule.onAllNodesWithText(esc).assertCountEquals(1)
        keys.press(NovaTestKeys.RIGHT)
        rule.advance(450)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(4)

        assertEquals(listOf(NovaQuickMenuActionId.QUICK_ALT_F4), quickKeys)
    }
}
