package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaActivatableComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var xCount = 0
    private var yCount = 0
    private val x = FocusRequester()
    private val y = FocusRequester()

    private fun setUp(): NovaTestKeys = rule.setPanelContent {
        Column {
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("x")
                    .focusRequester(x)
                    .novaActivatable { xCount++ }
                    .focusable(),
            )
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("y")
                    .focusRequester(y)
                    .novaActivatable { yCount++ }
                    .focusable(),
            )
        }
    }

    private fun focus(requester: FocusRequester) {
        rule.runOnIdle { requester.requestFocus() }
        rule.waitForIdle()
    }

    @Test
    fun pressingOnOneElementAndReleasingOnAnotherActivatesNeither() {
        val keys = setUp()
        focus(x)
        keys.down(NovaTestKeys.CENTER)
        focus(y)
        rule.onNodeWithTag("y").assertIsFocused()
        keys.up(NovaTestKeys.CENTER)

        assertEquals(0, xCount)
        assertEquals(0, yCount)
    }

    @Test
    fun aFocusedElementInsideAnotherActsAndTheOuterOneDoesNot() {
        var outer = 0
        var inner = 0
        val outerFocus = FocusRequester()
        val innerFocus = FocusRequester()
        val keys = rule.setPanelContent {
            // A card with a button inside it: A belongs to whichever of the two has focus.
            Box(Modifier.size(120.dp).focusRequester(outerFocus).novaClickable { outer++ }) {
                Box(Modifier.size(48.dp).focusRequester(innerFocus).novaClickable { inner++ })
            }
        }
        focus(innerFocus)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, inner)
        assertEquals("the card saw the press on its way down and left it alone", 0, outer)

        focus(outerFocus)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, outer)
        assertEquals(1, inner)
    }

    @Test
    fun aPressAndReleaseOnTheSameElementActivatesOnce() {
        val keys = setUp()
        focus(x)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(1, xCount)
        assertEquals(0, yCount)
    }

    @Test
    fun aHeldKeyActivatesOnceOnRelease() {
        val keys = setUp()
        focus(x)
        keys.down(NovaTestKeys.CENTER)
        keys.down(NovaTestKeys.CENTER, repeat = 1)
        keys.down(NovaTestKeys.CENTER, repeat = 2)
        keys.down(NovaTestKeys.CENTER, repeat = 3)
        assertEquals("nothing acts on key down", 0, xCount)
        keys.up(NovaTestKeys.CENTER)
        assertEquals(1, xCount)
    }

    @Test
    fun anUpWithNoDownIsIgnored() {
        val keys = setUp()
        focus(x)
        keys.up(NovaTestKeys.CENTER)
        keys.up(KeyEvent.KEYCODE_ENTER)
        assertEquals(0, xCount)
    }

    @Test
    fun aRawButtonAWorksWhereNoGateRuns() {
        val keys = setUp()
        focus(x)
        keys.press(NovaTestKeys.A)
        assertEquals(1, xCount)
    }

    @Test
    fun aCancelledReleaseDoesNothing() {
        val keys = setUp()
        focus(x)
        keys.down(NovaTestKeys.CENTER)
        keys.up(NovaTestKeys.CENTER, canceled = true)
        assertEquals(0, xCount)
    }

    @Test
    fun aReleaseOfADifferentKeyDoesNothing() {
        val keys = setUp()
        focus(x)
        keys.down(NovaTestKeys.CENTER)
        keys.up(KeyEvent.KEYCODE_ENTER)
        assertEquals(0, xCount)
    }

    @Test
    fun novaClickableActsOnceForATapAndOnceForAKeyPress() {
        var clicks = 0
        val keys = rule.setPanelContent {
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("button")
                    .novaClickable { clicks++ },
            )
        }
        rule.onNodeWithTag("button").performClick()
        assertEquals(1, clicks)

        rule.onNodeWithTag("button").requestFocus()
        keys.press(NovaTestKeys.CENTER)
        assertEquals("the node latch and clickable's own key handling never both fire", 2, clicks)
    }

    @Test
    fun theGateResendsAAsCenterWithEverythingElseKept() {
        val gate = NovaKeyGate()
        val delivered = mutableListOf<KeyEvent>()
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 3, 7, 96, 0, InputDevice.SOURCE_GAMEPAD)
        val up = KeyEvent(downTime, downTime + 9, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, 0, 3, 7, 96, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_GAMEPAD)

        gate.dispatch(down, onBack = {}, deliver = { delivered += it; false })
        gate.dispatch(up, onBack = {}, deliver = { delivered += it; true })

        assertEquals(2, delivered.size)
        delivered.zip(listOf(down, up)).forEach { (sent, original) ->
            assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, sent.keyCode)
            assertEquals(original.action, sent.action)
            assertEquals(original.downTime, sent.downTime)
            assertEquals(original.eventTime, sent.eventTime)
            assertEquals(original.deviceId, sent.deviceId)
            assertEquals(original.scanCode, sent.scanCode)
            assertEquals(original.source, sent.source)
            assertEquals(original.flags, sent.flags)
            assertEquals(original.metaState, sent.metaState)
        }
    }

    @Test
    fun theGateTurnsAMatchedBReleaseIntoBack() {
        val gate = NovaKeyGate()
        var backs = 0
        val now = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(now, now, action, KeyEvent.KEYCODE_BUTTON_B, 0)
        gate.dispatch(event(KeyEvent.ACTION_DOWN), onBack = { backs++ }, deliver = { error("B is never delivered") })
        gate.dispatch(event(KeyEvent.ACTION_UP), onBack = { backs++ }, deliver = { error("B is never delivered") })
        assertEquals(1, backs)
    }
}
