package com.papi.nova.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stream sheets' controller cases on what replaced them: Mouse Mode as the Command Center's
 * Choice page, and End Session as the split in the Command Center's header, both in a panel
 * window. Keys are injected, so they reach the window's key gate the way a pad's do.
 *
 * The window here has Screen placement: Stream placement needs a Game with a live stream. Its key
 * gate, focus and page stack are the same window's; what Stream placement adds is handing input
 * back to the stream on close, which the RP6 checklist covers by hand.
 */
@RunWith(AndroidJUnit4::class)
class NovaStreamSheetsInstrumentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val surfaces get() = NovaSurfaces.of(rule.activity)
    private val appliedMode = AtomicInteger(Int.MIN_VALUE)
    private val ended = AtomicInteger(0)

    @After fun closeWindow() {
        rule.runOnUiThread { surfaces.dispose() }
    }

    @Test fun mouseModeStartsOnTheCurrentModeThenDownAndAAppliesTheOriginalModeIndex() {
        showMouseMode()
        rule.onNodeWithText(GAMING).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(DISABLED).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(4, appliedMode.get())
    }

    @Test fun mouseModeBLeavesWithoutApplyingTheHighlightedMode() {
        showMouseMode()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithText(DISABLED).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(Int.MIN_VALUE, appliedMode.get())
    }

    @Test fun theOpeningButtonsReleaseAloneCannotApplyAMode() {
        showMouseMode()
        rule.onNodeWithText(GAMING).assertIsFocused()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        rule.waitForIdle()
        assertEquals(Int.MIN_VALUE, appliedMode.get())
        rule.runOnIdle { assertTrue(surfaces.panel.isOpen) }
    }

    @Test fun endSessionStartsOnStaySoOneAIsSafe() {
        showCommandCenter()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithText(rule.activity.getString(R.string.nova_panel_stay)).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithText(END_SESSION).assertIsFocused()
        assertEquals(0, ended.get())
    }

    @Test fun endingNeedsRightThenA() {
        showCommandCenter()
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
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

    /** Mouse Mode pushed from the Command Center's Mouse row, as the Command Center pushes it. */
    private fun showMouseMode() {
        showCommandCenter { NovaQuickMenuCallbacks(onControlAction = { pushMouseMode() }) }
        rule.onNodeWithText("Mouse").requestFocus()
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_A)
    }

    private fun pushMouseMode() {
        surfaces.panel.push(
            NovaMouseModeChoices.page(
                title = "Mouse mode",
                // The external-display subset keeps its original indexes; list positions are not modes.
                options = NovaMouseModeChoices.options(
                    modeNames = listOf("Direct", "Relative", "Track pad (Natural)", GAMING, DISABLED),
                    onExternalDisplay = true,
                    externalModes = setOf("Track pad (Natural)", GAMING, DISABLED),
                    localCursorLabel = "Toggle local cursor",
                ),
                current = 3,
                onChoose = { appliedMode.set(it) },
            ),
        )
    }

    private fun showCommandCenter(
        callbacks: () -> NovaQuickMenuCallbacks = { NovaQuickMenuCallbacks(onEndStream = { ended.incrementAndGet() }) },
    ) {
        val state = MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity))
        val built = callbacks()
        rule.runOnUiThread {
            surfaces.open(CommandCenterPage.Root("Command Center"), NovaEdge.Start) { page ->
                when (page) {
                    is CommandCenterPage.Root -> NovaQuickMenuContent(state = state, callbacks = built)
                    is CommandCenterPage.Listing -> CommandCenterListingPage(page)
                    else -> Unit
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(END_SESSION).requestFocus()
        rule.waitForIdle()
    }

    private companion object {
        const val GAMING = "Track pad (Gaming)"
        const val DISABLED = "Disabled"
        const val END_SESSION = "End Session"
    }
}
