package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.ui.panel.NovaSplitConfirmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The companion deck's End Session tile confirms in its own tile, and its split is hoisted so
 * the deck's Back can take it back before anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCompanionDeckEndTileComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val split = NovaSplitConfirmState()
    private var ended = 0

    private fun showDeck() {
        rule.runOnUiThread {
            val deck = NovaCompanionCommandDeckView(rule.activity, endSessionSplit = split, composeOwner = rule.activity) { id ->
                if (id == NovaCompanionCommandActionId.END_SESSION) ended++
            }
            rule.activity.setContentView(deck)
            deck.render(
                NovaCompanionCommandDeckState.from(
                    hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
                    sessionState = "streaming",
                    displayRole = "Companion",
                    unavailableLabel = "Unavailable",
                ),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun aTapArmsTheTileAndEndsNothing() {
        showDeck()
        rule.onNodeWithText("End Session").performClick()
        rule.waitForIdle()

        assertTrue(split.armed)
        rule.onNodeWithText("Stay").assertExists()
        assertEquals(0, ended)
    }

    @Test
    fun theDecksBackTakesTheArmedTileBack() {
        showDeck()
        rule.onNodeWithText("End Session").performClick()
        rule.waitForIdle()
        assertTrue(split.armed)

        // What ExternalDisplayControlController.handleCompanionBack does first.
        rule.runOnUiThread { split.disarm() }
        rule.waitForIdle()

        assertFalse(split.armed)
        rule.onNodeWithText("Stay").assertDoesNotExist()
        assertEquals(0, ended)
    }

    @Test
    fun theArmedEndHalfEndsTheSessionOnce() {
        showDeck()
        rule.onNodeWithText("End Session").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(450)
        rule.waitForIdle()

        rule.onNodeWithText("End Session").performClick()
        rule.waitForIdle()

        assertEquals(1, ended)
        assertFalse(split.armed)
    }
}
