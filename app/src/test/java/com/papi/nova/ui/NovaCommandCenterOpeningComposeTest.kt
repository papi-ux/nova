package com.papi.nova.ui

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
    private val advanced get() = rule.activity.getString(R.string.nova_cc_technical_details)

    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun open(place: NovaQuickMenuPlace = NovaQuickMenuPlace()): NovaTestKeys {
        state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity))
        panel.open(CommandCenterPage.Root("Command Center"))
        val callbacks = NovaQuickMenuCallbacks(
            onTechnicalDetails = { panel.push(CommandCenterPage.TechnicalDetails) },
        )
        val keys = rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    when (page) {
                        is CommandCenterPage.Root -> NovaQuickMenuContent(state, callbacks, place = place)
                        CommandCenterPage.TechnicalDetails -> NovaCommandCenterDetails(CommandCenterPage.TechnicalDetails, state, callbacks, place)
                        else -> Unit
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
    fun technicalControlsPushAPageAndBackRestoresItsRootEntry() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        assertTrue(panel.top is CommandCenterPage.TechnicalDetails)
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onNodeWithText("Clear Game Profile").performScrollTo().assertIsDisplayed()
        keys.back()
        rule.frames(16)
        assertTrue(panel.top is CommandCenterPage.Root)
        rule.onNodeWithText(advanced).assertIsFocused()
        rule.onNodeWithText(advanced).assertIsDisplayed()
    }

    @Test
    fun technicalControlsKeepTheFixedHeaderWhileTheirListScrolls() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        rule.onNodeWithText("Clear Game Profile").performScrollTo()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onNodeWithText("Disconnect").assertIsDisplayed()
        rule.onNodeWithText("End Session").assertIsDisplayed()
        val row = rule.onNodeWithText("Clear Game Profile").getUnclippedBoundsInRoot()
        val list = rule.onNodeWithTag(NOVA_CC_SECTIONS_TAG).getUnclippedBoundsInRoot()
        assertTrue(row.bottom <= list.bottom)
    }

    @Test
    fun closingTechnicalControlsThenReopeningStartsAtTheTop() {
        val keys = open()
        focus(advanced)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(16)
        focus("Close")
        reopen()
        rule.onNodeWithText("Close").assertIsFocused()
        rule.onNodeWithText("Live Tuning").assertIsDisplayed()
    }
}
