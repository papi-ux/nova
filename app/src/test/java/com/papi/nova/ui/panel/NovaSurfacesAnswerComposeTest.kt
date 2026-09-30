package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.R
import com.papi.nova.utils.ExternalDisplayControlHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NovaSurfaces.confirm() and busy() settle exactly once, whichever way their page goes. The
 * surfaces here never open a window of their own (a companion placement whose deck is hidden), so
 * their panel and state pages are drawn once, in this test's composition, where its keys and
 * touches reach them as they would in the window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSurfacesAnswerComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val surfaces = NovaSurfaces(NovaWindowPlacement.Companion(Mockito.mock(ExternalDisplayControlHost::class.java)) {})
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var confirms = 0
    private var stays = 0

    @After
    fun tearDown() {
        scope.cancel()
        rule.runOnUiThread { surfaces.dispose() }
    }

    private fun draw(): NovaTestKeys = rule.setPanelContent {
        val states by surfaces.states.collectAsState()
        NovaSurfacesLayer(
            panel = surfaces.panel,
            states = states,
            scrim = NovaScrim.Stream,
            pageContent = { page -> NovaRow(title = "Row ${page.key}", onClick = {}, modifier = Modifier.novaInitialFocus()) },
            onIdle = {},
            isPosted = { key -> surfaces.states.value.any { it.key == key } },
        )
    }

    private fun question() = NovaCommonPage.Confirm(
        key = "end",
        title = "End this Nova session?",
        message = AnnotatedString("The game closes on the host."),
        stayLabel = "Stay",
        actionLabel = "End Session",
        destructive = true,
        onConfirm = { confirms++ },
        onStay = { stays++ },
    )

    /** What the caller of confirm() got, once: true, false, or null while it still waits. */
    private class Asked(val job: Job, val answers: MutableList<Boolean>)

    private fun ask(underRoot: Boolean = false): Asked {
        val answers = mutableListOf<Boolean>()
        if (underRoot) rule.runOnUiThread { surfaces.open(TestPage("root")) }
        val job = scope.launch { answers += surfaces.confirm(question()) }
        rule.waitForIdle()
        rule.onNodeWithText("Stay").assertIsFocused()
        return Asked(job, answers)
    }

    private fun assertStayedOnce(asked: Asked, how: String) {
        rule.waitForIdle()
        assertEquals("$how answers false, once", listOf(false), asked.answers)
        assertEquals("$how runs Stay once", 1, stays)
        assertEquals("$how never confirms", 0, confirms)
        assertFalse(surfaces.panel.contains("end"))
    }

    private fun assertQuietlyGone(asked: Asked, how: String) {
        rule.waitForIdle()
        assertEquals("$how answers false, once", listOf(false), asked.answers)
        assertEquals("$how runs neither callback", 0, stays + confirms)
        assertFalse(surfaces.panel.contains("end"))
    }

    @Test
    fun itsActionConfirmsOnceAndNothingAfterItAnswersAgain() {
        val keys = draw()
        val asked = ask()
        val page = surfaces.panel.top as NovaCommonPage.Confirm
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        assertEquals(listOf(true), asked.answers)
        assertEquals(1, confirms)

        // A stale press on the page on its way out, and every other exit, change nothing.
        rule.runOnUiThread {
            page.onConfirm()
            page.onStay()
            surfaces.panel.close()
            surfaces.clear(NovaStateOwner.App)
        }
        rule.waitForIdle()
        assertEquals(listOf(true), asked.answers)
        assertEquals(1, confirms)
        assertEquals(0, stays)
    }

    @Test
    fun bStays() {
        val keys = draw()
        val asked = ask()
        keys.back()
        assertStayedOnce(asked, "B")
    }

    @Test
    fun startStays() {
        val keys = draw()
        val asked = ask()
        keys.press(KeyEvent.KEYCODE_BUTTON_START)
        assertStayedOnce(asked, "Start")
    }

    @Test
    fun menuStays() {
        val keys = draw()
        val asked = ask()
        keys.press(KeyEvent.KEYCODE_MENU)
        assertStayedOnce(asked, "Menu")
    }

    @Test
    fun theScrimStays() {
        draw()
        val asked = ask()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.nova_panel_close_panel))
            .performTouchInput { click(Offset(centerX, 8f)) }
        assertStayedOnce(asked, "the scrim")
    }

    @Test
    fun aDragStays() {
        draw()
        val asked = ask()
        // The portrait sheet, dragged by its header (its page scrolls under a drag) down past the
        // bottom of the screen.
        val header = rule.onNodeWithText("End this Nova session?")
        header.performTouchInput { down(center) }
        // A frame between moves, so the sheet follows the finger as it does on a device.
        repeat(8) {
            header.performTouchInput { moveBy(Offset(0f, 300f)) }
            rule.waitForIdle()
        }
        header.performTouchInput { up() }
        assertStayedOnce(asked, "a drag")
    }

    @Test
    fun theHeaderStays() {
        draw()
        val asked = ask(underRoot = true)
        rule.onNode(androidx.compose.ui.test.hasTestTag(com.papi.nova.ui.panel.NovaPageBackTag) and androidx.compose.ui.test.hasText("End this Nova session?")).performClick()
        assertStayedOnce(asked, "the header")
        assertEquals("the header popped one page", "root", surfaces.panel.top?.key)
    }

    @Test
    fun aNewRootReplacingItStays() {
        draw()
        val asked = ask()
        rule.runOnUiThread { surfaces.open(TestPage("other")) }
        assertStayedOnce(asked, "a new root")
    }

    @Test
    fun clearTakesItDownQuietly() {
        draw()
        val asked = ask()
        rule.runOnUiThread { surfaces.clear(NovaStateOwner.App) }
        assertQuietlyGone(asked, "clear")
    }

    @Test
    fun disposingTheOwnerTakesItDownQuietly() {
        draw()
        val asked = ask()
        rule.runOnUiThread { surfaces.dispose() }
        assertQuietlyGone(asked, "dispose")
        rule.waitForIdle()
        assertTrue("the caller is not left waiting", asked.job.isCompleted)
    }

    @Test
    fun aCallerThatStopsWaitingTakesItDownQuietly() {
        draw()
        val asked = ask()
        asked.job.cancel()
        rule.waitForIdle()
        assertEquals("a cancelled caller gets no answer", emptyList<Boolean>(), asked.answers)
        assertEquals(0, stays + confirms)
        assertFalse(surfaces.panel.contains("end"))
    }

    @Test
    fun anActionReleasedAsThePanelClosesNeverConfirms() {
        val keys = draw()
        val asked = ask()
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("End Session").assertIsFocused()
        keys.down(NovaTestKeys.CENTER)
        // Start closes the panel, and the A that was already down is released in the same frame.
        rule.runOnUiThread {
            surfaces.panel.close()
            val now = SystemClock.uptimeMillis()
            keys.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, NovaTestKeys.CENTER, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
        assertStayedOnce(asked, "a close racing a release")
    }

    @Test
    fun aDisposedInstanceAnswersFalseAtOnce() {
        rule.runOnUiThread { surfaces.dispose() }
        val answers = mutableListOf<Boolean>()
        scope.launch { answers += surfaces.confirm(question()) }
        rule.waitForIdle()
        assertEquals(listOf(false), answers)
        assertFalse(surfaces.panel.isOpen)
    }

    // busy()

    private fun work(cancelLabel: String?, done: CompletableDeferred<Unit>? = null): Pair<Job, MutableList<String>> {
        val outcome = mutableListOf<String>()
        val job = scope.launch {
            try {
                surfaces.busy(title = "Saving", cancelLabel = cancelLabel) { done?.await() ?: awaitCancellation() }
                outcome += "done"
            } catch (e: CancellationException) {
                outcome += "cancelled"
                throw e
            }
        }
        rule.waitForIdle()
        return job to outcome
    }

    @Test
    fun cancellingTheCallerEndsTheWorkAndTakesItsPageDown() {
        draw()
        val (job, outcome) = work(cancelLabel = null)
        assertEquals(1, surfaces.states.value.size)
        job.cancel()
        rule.waitForIdle()
        assertEquals(listOf("cancelled"), outcome)
        assertTrue(surfaces.states.value.isEmpty())
    }

    @Test
    fun disposingTheOwnerEndsTheWork() {
        draw()
        val (job, outcome) = work(cancelLabel = null)
        rule.runOnUiThread { surfaces.dispose() }
        rule.waitForIdle()
        assertEquals(listOf("cancelled"), outcome)
        assertTrue(job.isCompleted)
    }

    @Test
    fun clearingItsPageEndsTheWorkNobodyCanSeeAnyMore() {
        draw()
        val (_, outcome) = work(cancelLabel = "Cancel")
        rule.runOnUiThread { surfaces.clear(NovaStateOwner.App) }
        rule.waitForIdle()
        assertEquals(listOf("cancelled"), outcome)
    }

    @Test
    fun aDisposedInstanceRunsNoWork() {
        rule.runOnUiThread { surfaces.dispose() }
        var ran = false
        val outcome = mutableListOf<String>()
        scope.launch {
            try {
                surfaces.busy(title = "Saving") { ran = true }
            } catch (e: CancellationException) {
                outcome += "cancelled"
            }
        }
        rule.waitForIdle()
        assertFalse(ran)
        assertEquals(listOf("cancelled"), outcome)
        assertTrue(surfaces.states.value.isEmpty())
    }

    @Test
    fun aBusyPageWithoutCancelCannotBeLeftByAnyPanelExit() {
        rule.runOnUiThread { surfaces.open(TestPage("root")) }
        val keys = draw()
        rule.onNodeWithText("Row root").assertIsFocused()
        rule.mainClock.autoAdvance = false
        val done = CompletableDeferred<Unit>()
        val (_, outcome) = work(cancelLabel = null, done = done)
        rule.advance(NovaPanelMetrics.BusyShowDelayMillis + 50)
        rule.frames(4)
        rule.onNodeWithText("Saving").assertExists()
        rule.advance(NovaPanelMetrics.SplitGuardMillis)

        keys.back()
        keys.gatedPress(KeyEvent.KEYCODE_BUTTON_B)
        keys.gatedPress(KeyEvent.KEYCODE_ESCAPE)
        keys.press(KeyEvent.KEYCODE_BUTTON_START)
        keys.press(KeyEvent.KEYCODE_MENU)
        keys.press(NovaTestKeys.CENTER)
        // The scrim and the sheet sit under the state page, which takes every touch.
        val root = rule.onRoot()
        root.performTouchInput { click(Offset(centerX, 8f)) }
        root.performTouchInput { down(Offset(centerX, bottom - 40f)) }
        repeat(8) {
            root.performTouchInput { moveBy(Offset(0f, 300f)) }
            rule.frames(1)
        }
        root.performTouchInput { up() }
        rule.frames(4)

        assertEquals("the work runs on", emptyList<String>(), outcome)
        assertEquals(1, surfaces.states.value.size)
        assertTrue("and the panel under it is still open", surfaces.panel.isOpen)

        done.complete(Unit)
        rule.frames(2)
        rule.advance(NovaPanelMetrics.BusyMinimumMillis)
        rule.frames(4)
        assertEquals(listOf("done"), outcome)
        assertTrue(surfaces.states.value.isEmpty())
        assertEquals("root", surfaces.panel.top?.key)
    }
}
