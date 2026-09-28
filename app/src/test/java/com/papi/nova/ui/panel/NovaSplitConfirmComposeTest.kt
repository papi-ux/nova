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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
}
