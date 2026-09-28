package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
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
class NovaSplitConfirmComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var ended = 0
    private val state = NovaSplitConfirmState()

    private fun setUp(): NovaTestKeys {
        val keys = rule.setPanelContent {
            Column(Modifier.padding(top = 200.dp)) {
                Box(Modifier.size(48.dp).testTag("other").focusable())
                NovaSplitConfirm(
                    label = "End session",
                    confirmLabel = "End",
                    onConfirm = { ended++ },
                    consequence = "The game closes on the host.",
                    state = state,
                )
            }
        }
        rule.onNodeWithText("End session").requestFocus()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        return keys
    }

    private fun frames(count: Int = 4) = rule.frames(count)

    @Test
    fun aArmsAndStayTakesFocus() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithText("The game closes on the host.").assertExists()
    }

    @Test
    fun aOnStayDisarmsAndRefocusesTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        keys.press(NovaTestKeys.CENTER)
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
    }

    @Test
    fun mashedAPressesNeverConfirm() {
        val keys = setUp()
        repeat(3) {
            keys.press(NovaTestKeys.CENTER)
            frames(16)
        }
        rule.advance(1_000)
        repeat(3) {
            keys.press(NovaTestKeys.CENTER)
            frames(16)
        }
        assertEquals(0, ended)
    }

    @Test
    fun aRightAConfirmsOnlyAfterTheGuard() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("End").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("inside 400ms the destructive half ignores A", 0, ended)
        assertTrue(state.armed)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        frames()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertEquals(1, ended)
        assertFalse(state.armed)
    }

    @Test
    fun bDisarmsAndRefocusesTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
        assertEquals(0, ended)
    }

    @Test
    fun aDisarmFromOutsideHandsFocusBackToTheButton() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        rule.onNodeWithText("Stay").assertIsFocused()

        // The companion deck's back disarms the hoisted state itself.
        rule.runOnIdle { state.disarm() }
        frames(16)

        assertFalse(state.armed)
        rule.onNodeWithText("End session").assertIsFocused()
    }

    @Test
    fun anArmedButtonSplitsInItsOwnSlotNotAcrossTheRow() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames(16)
        val stay = rule.onNodeWithText("Stay").getUnclippedBoundsInRoot()
        val end = rule.onNodeWithText("End").getUnclippedBoundsInRoot()
        val pair = end.right - stay.left
        val twoHalves = NovaPanelMetrics.SplitHalfMinWidth * 2 + NovaPanelMetrics.SplitGap
        assertTrue("a narrow button widens only to two 96dp halves, not the full row: $pair", abs((pair - twoHalves).value) <= 1f)
    }

    @Test
    fun aTouchInsideThePairKeepsItArmed() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        val inside = rule.onNodeWithText("Stay").fetchSemanticsNode().boundsInWindow.center
        val now = SystemClock.uptimeMillis()
        val touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, inside.x, inside.y, 0)
        rule.runOnUiThread { NovaSplitConfirmRegistry.onTouch(rule.activity.window.decorView, touch) }
        frames()
        assertTrue(state.armed)
        touch.recycle()
    }

    @Test
    fun aRecycledViewShowingAnotherItemStartsDisarmed() {
        var view: ComposeView? = null
        rule.setPanelContent { AndroidView(factory = { context -> ComposeView(context).also { view = it } }) }
        rule.runOnUiThread { view!!.setNovaSplitConfirm("Delete profile", "Delete", itemKey = "handheld") {} }
        rule.onNodeWithText("Delete profile").performClick()
        rule.onNodeWithText("Stay").assertExists()

        rule.runOnUiThread { view!!.setNovaSplitConfirm("Delete profile", "Delete", itemKey = "living room") {} }
        rule.waitForIdle()

        rule.onNodeWithText("Stay").assertDoesNotExist()
        rule.onNodeWithText("Delete profile").assertExists()
    }

    @Test
    fun focusLeavingThePairDisarms() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        rule.onNodeWithTag("other").requestFocus()
        frames(16)
        assertFalse(state.armed)
        rule.onNodeWithTag("other").assertIsFocused()
    }

    @Test
    fun aTouchDoubleTapNeverConfirms() {
        setUp()
        rule.onNodeWithText("End session").performClick()
        frames()
        assertTrue(state.armed)
        rule.onNodeWithText("End").performClick()
        frames()
        assertEquals(0, ended)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        frames()
        rule.onNodeWithText("End").performClick()
        frames()
        assertEquals("a deliberate second tap after the guard ends it", 1, ended)
    }

    @Test
    fun aTouchOutsideThePairDisarms() {
        val keys = setUp()
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        rule.runOnUiThread { NovaSplitConfirmRegistry.onTouch(rule.activity.window.decorView, outside) }
        frames()
        assertFalse(state.armed)
        outside.recycle()
    }

    @Test
    fun aWindowWithTheInstalledFeedDisarmsOnATouchOutside() {
        val keys = setUp()
        rule.runOnUiThread {
            // A plain Activity window, such as the companion deck's, feeds the registry this way.
            NovaSplitConfirmRegistry.install(rule.activity.window)
            NovaSplitConfirmRegistry.install(rule.activity.window)
        }
        keys.press(NovaTestKeys.CENTER)
        frames()
        assertTrue(state.armed)
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        rule.runOnUiThread { rule.activity.window.callback.dispatchTouchEvent(outside) }
        frames()
        assertFalse(state.armed)
        outside.recycle()
    }
}
