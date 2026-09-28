package com.papi.nova.ui.panel

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStateScreenComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var retries = 0
    private var closes = 0

    private fun problem(withSecondary: Boolean) = NovaStatePage.Problem(
        key = "lost",
        title = "Connection lost",
        message = "The host stopped answering.",
        eyebrow = "Stream",
        primary = NovaAction("Reconnect") { retries++ },
        secondary = if (withSecondary) listOf(NovaAction("Close") { closes++ }) else emptyList(),
    )

    @Test
    fun theProblemPrimaryIsFocusedAndBRunsTheSecondaryNeverThePrimary() {
        val keys = rule.setPanelContent { NovaStateScreen(problem(withSecondary = true)) }
        rule.onNodeWithText("Reconnect").assertIsFocused()
        keys.back()
        assertEquals(1, closes)
        assertEquals(0, retries)
    }

    @Test
    fun withNoSecondaryBRunsThePrimary() {
        val keys = rule.setPanelContent { NovaStateScreen(problem(withSecondary = false)) }
        keys.back()
        assertEquals(1, retries)
    }

    @Test
    fun focusCannotLeaveTheStatePage() {
        val keys = rule.setPanelContent { NovaStateScreen(problem(withSecondary = true)) }
        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.UP)
        rule.onNodeWithText("Reconnect").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText("Close").assertIsFocused()
    }

    @Test
    fun anActionThatTakesItsPageDownRunsOnceForTwoPressesInOneFrame() {
        var pages by mutableStateOf(emptyList<NovaStatePage>())
        var posted = emptyList<NovaStatePage>()
        var reconnects = 0
        val page = NovaStatePage.Problem(
            key = "lost",
            title = "Connection lost",
            message = "The host stopped answering.",
            primary = NovaAction("Reconnect") {
                // As the legacy helpers do: take the page down, then act.
                posted = emptyList()
                pages = posted
                reconnects++
            },
        )
        posted = listOf(page)
        pages = posted
        val keys = rule.setPanelContent {
            NovaStatePages(pages = pages, onShowingChange = {}, isPosted = { key -> posted.any { it.key == key } })
        }
        rule.onNodeWithText("Reconnect").assertIsFocused()

        keys.pressTwiceInOneFrame(NovaTestKeys.CENTER)

        assertEquals(1, reconnects)
    }

    @Test
    fun busyIsHiddenBefore300msAndHeldFor500ms() {
        var pages by mutableStateOf(emptyList<NovaStatePage>())
        rule.setPanelContent { NovaStatePages(pages = pages, onShowingChange = {}) }
        rule.mainClock.autoAdvance = false
        val busy = NovaStatePage.Busy(key = "adding", title = "Adding PC", message = MutableStateFlow("Looking for the host"))

        pages = listOf(busy)
        rule.waitForIdle()
        rule.advance(200)
        rule.onNodeWithText("Adding PC").assertDoesNotExist()
        rule.advance(150)
        rule.onNodeWithText("Adding PC").assertExists()

        // Dismissed 100ms after it appeared: it stays until 500ms have passed.
        rule.advance(100)
        pages = emptyList()
        rule.waitForIdle()
        rule.advance(250)
        rule.onNodeWithText("Adding PC").assertExists()
        rule.advance(250)
        rule.onNodeWithText("Adding PC").assertDoesNotExist()
    }

    @Test
    fun aBusyPagesCancelWaitsOutTheGuardOnceVisibleSoAMashedPressEndsNothing() {
        var cancels = 0
        var pages by mutableStateOf(emptyList<NovaStatePage>())
        val keys = rule.setPanelContent { NovaStatePages(pages = pages, onShowingChange = {}) }
        rule.mainClock.autoAdvance = false
        pages = listOf(
            NovaStatePage.Busy(
                key = "reconnecting",
                title = "Reconnecting",
                message = MutableStateFlow("Attempt 1 of 5"),
                cancel = NovaAction("Disconnect") { cancels++ },
            ),
        )
        rule.waitForIdle()
        rule.advance(NovaPanelMetrics.BusyShowDelayMillis + 50)
        rule.frames(4)
        rule.onNodeWithText("Disconnect").assertIsFocused()

        // Just visible: A and B from a player still mashing do nothing.
        keys.press(NovaTestKeys.CENTER)
        keys.back()
        rule.frames(2)
        assertEquals(0, cancels)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(2)
        assertEquals("past the guard the cancel is the way out", 1, cancels)
    }

    @Test
    fun aBusyPageGoneBefore300msNeverShows() {
        var pages by mutableStateOf(emptyList<NovaStatePage>())
        rule.setPanelContent { NovaStatePages(pages = pages, onShowingChange = {}) }
        rule.mainClock.autoAdvance = false
        pages = listOf(NovaStatePage.Busy(key = "quick", title = "Refreshing", message = MutableStateFlow("")))
        rule.waitForIdle()
        rule.advance(150)
        rule.onNodeWithText("Refreshing").assertDoesNotExist()
        pages = emptyList()
        rule.waitForIdle()
        rule.advance(600)
        rule.onNodeWithText("Refreshing").assertDoesNotExist()
    }

    @Test
    fun cancelCancelsABusyBlock() {
        rule.setPanelContent { }
        val surfaces = NovaSurfaces.of(rule.activity)
        var cancelled = false
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                surfaces.busy(title = "Connecting", cancelLabel = "Cancel") { awaitCancellation() }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val page = surfaces.states.value.single() as NovaStatePage.Busy
        assertEquals("Connecting", page.title)

        rule.runOnUiThread { page.cancel!!.run() }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(cancelled)
        assertTrue(surfaces.states.value.isEmpty())
        surfaces.dispose()
        scope.cancel()
    }

    @Test
    fun theCodePageShowsItsDigitsWithCloseFocused() {
        rule.setPanelContent {
            NovaStateScreen(
                NovaStatePage.Code(
                    key = "pin",
                    title = "Enter this PIN on the host",
                    code = "4721",
                    message = "Polaris asks for it under Pair.",
                    close = NovaAction("Close") { closes++ },
                ),
            )
        }
        rule.onNodeWithText("4721").assertExists()
        rule.onNodeWithText("Close").assertIsFocused()
    }
}
