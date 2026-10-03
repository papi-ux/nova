package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stream sheets' controller cases, moved onto [NovaPanelWindow]: a Choice page stands in for
 * the mouse mode picker and a split stands in for the End Session sheet. Keys are injected, so
 * they reach the window's key gate the way a pad's do.
 */
@RunWith(AndroidJUnit4::class)
class NovaPanelWindowInstrumentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val surfaces get() = NovaSurfaces.of(rule.activity)
    private val appliedMode = AtomicInteger(Int.MIN_VALUE)
    private val ended = AtomicInteger(0)

    @After fun closeWindow() {
        rule.runOnUiThread { surfaces.dispose() }
    }

    @Test fun aChoiceStartsOnTheCurrentModeThenDownAndAAppliesTheOriginalModeIndex() {
        showMousePicker()
        rule.onNodeWithText(GAMING).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(DISABLED).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(4, appliedMode.get())
        rule.runOnIdle { assertFalse(surfaces.panel.isOpen) }
    }

    @Test fun bCancelsTheChoiceWithoutApplyingTheHighlightedMode() {
        showMousePicker()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(DISABLED).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(Int.MIN_VALUE, appliedMode.get())
        rule.runOnIdle { assertFalse(surfaces.panel.isOpen) }
    }

    @Test fun theOpeningButtonsReleaseAloneCannotApplyAChoice() {
        showMousePicker()
        rule.onNodeWithText(GAMING).assertIsFocused()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        rule.waitForIdle()
        assertEquals(Int.MIN_VALUE, appliedMode.get())
        rule.runOnIdle { assertTrue(surfaces.panel.isOpen) }
    }

    @Test fun theSplitStartsOnStaySoOneAIsSafe() {
        showEndSession()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithText(stay()).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithText(END_SESSION).assertIsFocused()
        assertEquals(0, ended.get())
    }

    @Test fun endingNeedsRightThenA() {
        showEndSession()
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithText(END).assertIsFocused()
        // The armed End ignores activation for its guard time; wait it out on either clock.
        rule.mainClock.advanceTimeBy(NovaPanelMetrics.SplitGuardMillis + 100)
        SystemClock.sleep(NovaPanelMetrics.SplitGuardMillis + 100)
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, ended.get())
    }

    private fun press(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        rule.waitForIdle()
    }

    private fun stay() = rule.activity.getString(R.string.nova_panel_stay)

    private fun showMousePicker() {
        // The external-display subset keeps its original indexes; list positions are not modes.
        val options = listOf(
            NovaOption(2, "Track pad (Natural)"),
            NovaOption(3, GAMING),
            NovaOption(4, DISABLED),
            NovaOption(-1, "Toggle local cursor"),
        )
        rule.runOnUiThread {
            surfaces.open(
                NovaCommonPage.Choice(
                    key = "mouse-mode",
                    title = "Mouse mode",
                    options = options,
                    current = 3,
                    onChoose = { appliedMode.set(it) },
                ),
            )
        }
        rule.waitForIdle()
    }

    private fun showEndSession() {
        rule.runOnUiThread {
            surfaces.open(
                NovaCommonPage.Menu(
                    key = "session",
                    title = "Session",
                    items = listOf(
                        NovaMenuItem.Destructive(
                            key = "end",
                            label = END_SESSION,
                            confirmLabel = END,
                            consequence = "The game closes on the host.",
                            onConfirm = { ended.incrementAndGet() },
                        ),
                    ),
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText(END_SESSION).assertIsFocused()
    }

    private companion object {
        const val GAMING = "Track pad (Gaming)"
        const val DISABLED = "Disabled"
        const val END_SESSION = "End session"
        const val END = "End"
    }
}
