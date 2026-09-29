package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaComposeColors
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
 * The Command Center holds still while it is open: the Doctor card has one place, under the
 * session strip, whatever the reading says, and a live reading never moves a row under the player
 * or takes focus from the row that has it (review finding 1, N28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterSteadyComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = NovaPanelState()
    private lateinit var state: MutableStateFlow<NovaQuickMenuUiState>
    private lateinit var colors: NovaComposeColors

    private fun open(adjust: (NovaQuickMenuUiState) -> NovaQuickMenuUiState) {
        state = MutableStateFlow(adjust(NovaQuickMenuUiState.preview(rule.activity)))
        panel.open(CommandCenterPage.Root("Command Center"))
        rule.setPanelContent {
            colors = LocalNovaComposeColors.current
            Box(Modifier.fillMaxSize()) {
                NovaPageStackHost(state = panel, containFocus = false) { page ->
                    if (page is CommandCenterPage.Root) {
                        NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks(), place = NovaQuickMenuPlace())
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

    private fun bounds(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private fun top(text: String): Float = bounds(text).top.value

    private fun colour(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().layoutInput.style.color
    }

    /** A reading from the host, with nothing Nova can run, so what A does is the same line. */
    private fun doctor(cause: String, informational: Boolean): (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { s ->
        s.copy(
            diagnosis = s.diagnosis.copy(
                likelyCause = cause,
                visible = true,
                available = true,
                informational = informational,
                actionExecutable = false,
                capability = NovaQuickMenuDoctorCapability.MANUAL,
                tryFirst = "",
                evidenceHighlight = "",
                classification = "UNKNOWN",
                aiExplanation = "",
                informationalSource = "",
            ),
        )
    }

    /** What the card shows before the host's first answer: no reading, nothing to press. */
    private val placeholder: (NovaQuickMenuUiState) -> NovaQuickMenuUiState = { s ->
        doctor(waiting, informational = true)(s).let { it.copy(diagnosis = it.diagnosis.copy(available = false)) }
    }

    private fun reading(cause: String, informational: Boolean) {
        state.value = doctor(cause, informational)(state.value)
        rule.waitForIdle()
    }

    private val hud get() = rule.activity.getString(R.string.nova_quick_menu_nova_hud)
    private val copies get() = rule.activity.getString(R.string.nova_quick_menu_doctor_capability_manual)

    private val waiting = "Connect to Polaris for HOST / NET / CLIENT diagnostics"
    private val pressure = "Sustained pressure on the link"
    private val observation = "Control channel retries, no confirmed loss"

    /**
     * Review finding 1 (N28): on papi's RP6 the verdict flips every second or two, and each flip
     * moved the Doctor card between two places, so every row between them jumped by a card's
     * height. The card has one place; the focused row stays where it is.
     */
    @Test
    fun aVerdictFlipWhileOpenMovesNothingAndKeepsFocus() {
        open(doctor(pressure, informational = false))
        focus(hud)
        val row = top(hud)
        val card = top(pressure)
        assertTrue("the card sits above the sections", card < row)

        reading(observation, informational = true)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals("the focused row stays where it was", row, top(hud), 0.5f)
        assertEquals("and the card where it was", card, top(observation), 0.5f)

        reading(pressure, informational = false)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals("flipping back moves nothing either", row, top(hud), 0.5f)
        assertEquals(card, top(pressure), 0.5f)
    }

    /** A reading that only informs at the opening sits in the same place, under the strip. */
    @Test
    fun aReadingThatOnlyInformsKeepsTheCardsOnePlace() {
        open(doctor(observation, informational = true))
        assertTrue("under the strip and above the sections, not after Quick Keys", top(observation) > top("Close") && top(observation) < top(hud))
        focus(hud)
        val row = top(hud)
        val card = top(observation)

        reading(pressure, informational = false)
        rule.onNodeWithText(hud).assertIsFocused()
        assertEquals(row, top(hud), 0.5f)
        assertEquals("pressure reads in the same place", card, top(pressure), 0.5f)
    }

    /**
     * Every opening starts before the host has answered. The first reading lands in the
     * placeholder's place: nothing jumps by a card's height, and the focused row keeps focus.
     */
    @Test
    fun theHostsFirstReadingLandsWhereThePlaceholderWas() {
        open(placeholder)
        focus(hud)
        val card = bounds(waiting)
        val below = top(hud) - card.bottom.value
        assertTrue("the placeholder sits above the sections too", card.top.value < top(hud))

        reading(pressure, informational = false)
        rule.onNodeWithText(hud).assertIsFocused()
        val now = bounds(pressure)
        assertEquals("the reading takes the placeholder's place", card.top.value, now.top.value, 0.5f)
        // The reading carries a line the placeholder had no use for, what A does; the rows under
        // the card move with that line and never by a card's height.
        assertEquals("the rows under it stay against it", below, top(hud) - now.bottom.value, 0.5f)
    }

    /** The same flip with the card itself focused: it keeps focus and its place. */
    @Test
    fun aVerdictFlipKeepsFocusOnTheDoctorCard() {
        open(doctor(pressure, informational = false))
        focus(pressure)
        val card = top(pressure)

        reading(observation, informational = true)
        rule.onNodeWithText(observation).assertIsFocused()
        assertEquals(card, top(observation), 0.5f)
    }

    /** N28's point kept in place: a reading that only informs says so and reads quieter. */
    @Test
    fun aReadingThatOnlyInformsReadsQuieterInsideTheCard() {
        open(doctor(pressure, informational = false))
        assertEquals("a reading to act on is in the text colour", colors.textPrimary, colour(pressure))
        assertEquals(colors.accent, colour(copies))

        reading(observation, informational = true)
        assertEquals("one that only informs is in the secondary text", colors.textSecondary, colour(observation))
        val nothing = rule.activity.getString(R.string.nova_cc_doctor_nothing_to_fix, copies)
        rule.onNodeWithText(nothing).assertExists()
        assertEquals(colors.textSecondary, colour(nothing))
    }
}
