package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.setPanelContent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Command Center holds still while it is open: a live reading never moves a row under the
 * player or takes focus from the row that has it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterSteadyComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>

    private fun open(
        place: NovaQuickMenuPlace? = null,
        adjust: (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { it },
    ) {
        state = MutableStateFlow(adjust(NovaQuickMenuUiState.preview(rule.activity)))
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) {
                        NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks(), place = place)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focus(text: String) {
        rule.onNodeWithText(text).requestFocus()
        rule.waitForIdle()
    }

    private fun top(text: String): Float = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    private fun doctor(cause: String, informational: Boolean): (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { s ->
        s.copy(diagnosis = s.diagnosis.copy(likelyCause = cause, visible = true, available = true, informational = informational))
    }

    private fun reading(cause: String, informational: Boolean) {
        state.value = doctor(cause, informational)(state.value)
        rule.waitForIdle()
    }

    private val hud get() = rule.activity.getString(R.string.nova_quick_menu_nova_hud)

    private val pressure = "Sustained pressure on the link"
    private val observation = "Control channel retries, no confirmed loss"

    /**
     * Review blocking finding (N28): on papi's RP6 the verdict flips every second or two, and each
     * flip moved the Doctor card between its two slots, so every row between them jumped by a
     * card's height. The slot is kept for the opening; the focused row stays where it is.
     */
    @Test
    fun aVerdictFlipWhileOpenMovesNoRowUnderTheFocus() {
        open(adjust = doctor(pressure, informational = false))
        focus(hud)
        val row = top(hud)
        val card = top(pressure)
        assertTrue("a reading the strip warns about sits above the sections", card < row)

        reading(observation, informational = true)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals("the focused row stays where it was", row, top(hud), 0.5f)
        assertEquals("and the card keeps its slot", card, top(observation), 0.5f)

        reading(pressure, informational = false)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals("flipping back moves nothing either", row, top(hud), 0.5f)
    }

    /** The same flip with the card itself focused: it was rebuilt in the other slot, without focus. */
    @Test
    fun aVerdictFlipKeepsFocusOnTheDoctorCard() {
        open(adjust = doctor(pressure, informational = false))
        focus(pressure)
        val card = top(pressure)

        reading(observation, informational = true)
        rule.onNodeWithText(observation).assertIsFocused()
        assertEquals(card, top(observation), 0.5f)
    }

    /** A reading that only informs at the opening keeps the card last, even when pressure follows. */
    @Test
    fun aCardThatOpenedLastStaysLastWhenTheVerdictTurns() {
        open(adjust = doctor(observation, informational = true))
        focus(hud)
        val row = top(hud)
        assertTrue(top(observation) > row)

        reading(pressure, informational = false)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals(row, top(hud), 0.5f)
        assertTrue("the card is still after the sections", top(pressure) > row)
    }
}
