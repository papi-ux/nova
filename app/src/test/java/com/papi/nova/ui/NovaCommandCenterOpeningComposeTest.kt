package com.papi.nova.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the Command Center opens and what expanding Advanced shows (#420). On the RP6 it reopened
 * scrolled down to Controls with focus up on Close, and Advanced's new rows stayed under the edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w640dp-h360dp-land")
class NovaCommandCenterOpeningComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private val advanced get() = rule.activity.getString(R.string.nova_quick_menu_advanced_tuning)

    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun open(place: NovaQuickMenuPlace = NovaQuickMenuPlace()): NovaTestKeys {
        state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity))
        panel.open(CommandCenterPage.Root("Command Center"))
        val callbacks = NovaQuickMenuCallbacks(
            onToggleAdvanced = { state.value = state.value.copy(advancedExpanded = !state.value.advancedExpanded) },
        )
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) {
                        NovaQuickMenuContent(state = state, callbacks = callbacks, place = place)
                    }
                }
            }
        }
        rule.waitForIdle()
        return keys
    }

    private fun reopen() {
        panel.close()
        rule.frames(16)
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.waitForIdle()
        rule.frames(4)
    }

    private fun focus(text: String) {
        rule.onNodeWithText(text).requestFocus()
        rule.waitForIdle()
    }

    /** The RP6's "clipped at the bottom edge": a sliver of a row counts as displayed, so measure it. */
    private fun assertLastAdvancedRowIsWhollyInTheList() {
        val list = rule.onNodeWithTag(NOVA_CC_SECTIONS_TAG).getUnclippedBoundsInRoot()
        val last = rule.onNodeWithText(state.value.advancedRows.last().label).getUnclippedBoundsInRoot()
        assertTrue("the row ends at ${last.bottom}, the list at ${list.bottom}", last.bottom <= list.bottom)
    }

    /** Touch scrolls the list without moving focus: that was never a place anyone chose. */
    @Test
    fun aListScrolledByTouchReopensAtTheTopOnClose() {
        open()
        rule.onNodeWithText("Keyboard").performScrollTo()
        rule.waitForIdle()
        reopen()

        rule.onNodeWithText("Close").assertIsFocused()
        rule.onNodeWithText("Live Tuning").assertIsDisplayed()
        rule.onNodeWithText("Keyboard").assertIsNotDisplayed()
    }

    /** The RP6's path: down to Controls, up to a header button, leave, and reopen on Close. */
    @Test
    fun focusLeavingTheListForTheHeaderReopensAtTheTop() {
        open()
        focus("Keyboard")
        rule.onNodeWithText("Keyboard").assertIsDisplayed()
        focus("Disconnect")
        reopen()

        rule.onNodeWithText("Close").assertIsFocused()
        rule.onNodeWithText("Live Tuning").assertIsDisplayed()
        rule.onNodeWithText("Keyboard").assertIsNotDisplayed()
    }

    /** In-game #16 still holds: the row left with focus is the remembered place, and it is shown. */
    @Test
    fun aRowLeftWithFocusReopensThereAndIsShown() {
        open()
        focus("Keyboard")
        reopen()

        rule.onNodeWithText("Keyboard").assertIsFocused()
        rule.onNodeWithText("Keyboard").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TALL)
    fun expandingAdvancedShowsItsRowsAndKeepsFocusOnTheToggle() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        rule.frames(16)

        rule.onNodeWithText(advanced).assertIsFocused()
        rule.onNodeWithText(advanced).assertIsDisplayed()
        assertLastAdvancedRowIsWhollyInTheList()
    }

    /** Too short for the whole block: the toggle is still shown, with the first row coming in under it. */
    @Test
    fun expandingAdvancedOnAShortListStartsItsRowsInView() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        rule.frames(16)

        val list = rule.onNodeWithTag(NOVA_CC_SECTIONS_TAG).getUnclippedBoundsInRoot()
        val toggle = rule.onNodeWithText(advanced).getUnclippedBoundsInRoot()
        val first = rule.onNodeWithText(state.value.advancedRows.first().label).getUnclippedBoundsInRoot()
        assertTrue("the toggle starts at ${toggle.top}, the list at ${list.top}", toggle.top >= list.top)
        assertTrue("the first row starts at ${first.top}, the list ends at ${list.bottom}", first.top < list.bottom)
    }

    @Test
    fun aControllerMovesDownFromTheToggleIntoAdvancedsRows() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        rule.frames(16)
        keys.press(NovaTestKeys.DOWN)
        rule.frames(8)

        rule.onNodeWithText("Clear Game Profile").assertIsFocused()
        rule.onNodeWithText("Clear Game Profile").assertIsDisplayed()
    }

    /** With animation off the rows are simply there; no scroll plays out over frames. */
    @Test
    @Config(qualifiers = TALL)
    fun expandingAdvancedJumpsWhenAnimationIsOff() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(3)

        assertLastAdvancedRowIsWhollyInTheList()
    }

    /** An opening with Advanced already open is not an expansion, so it does not scroll there. */
    @Test
    fun reopeningWithAdvancedOpenStaysAtTheTop() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        rule.frames(16)
        focus("Close")
        reopen()

        rule.onNodeWithText("Close").assertIsFocused()
        rule.onNodeWithText("Live Tuning").assertIsDisplayed()
    }
}

private const val TALL = "w900dp-h720dp-land"
