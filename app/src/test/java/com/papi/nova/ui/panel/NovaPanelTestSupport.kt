package com.papi.nova.ui.panel

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.papi.nova.ui.compose.NovaComposeTheme

internal typealias NovaTestRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Sets [content] under the Nova theme in keyboard input mode, and returns a way to send raw key
 * events to it: raw events carry the repeat counts and cancel flags the contract depends on.
 */
internal fun NovaTestRule.setPanelContent(content: @Composable () -> Unit): NovaTestKeys {
    val keys = NovaTestKeys(this)
    setContent {
        keys.view = LocalView.current
        keys.inputModes = LocalInputModeManager.current
        NovaComposeTheme(content = content)
    }
    runOnIdle { keys.inputModes.requestInputMode(InputMode.Keyboard) }
    return keys
}

internal class NovaTestKeys(private val rule: NovaTestRule) {
    lateinit var view: View
    lateinit var inputModes: InputModeManager

    fun down(code: Int, repeat: Int = 0) = send(KeyEvent.ACTION_DOWN, code, repeat, 0)

    fun up(code: Int, canceled: Boolean = false) =
        send(KeyEvent.ACTION_UP, code, 0, if (canceled) KeyEvent.FLAG_CANCELED else 0)

    fun press(code: Int) {
        down(code)
        up(code)
    }

    fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun send(action: Int, code: Int, repeat: Int, flags: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, code, repeat, 0, 0, 0, flags, InputDevice.SOURCE_KEYBOARD)
        rule.runOnUiThread { view.dispatchKeyEvent(event) }
        rule.waitForIdle()
        if (!rule.mainClock.autoAdvance) rule.frames(1)
    }

    companion object {
        const val A = KeyEvent.KEYCODE_BUTTON_A
        const val CENTER = KeyEvent.KEYCODE_DPAD_CENTER
        const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val UP = KeyEvent.KEYCODE_DPAD_UP
        const val DOWN = KeyEvent.KEYCODE_DPAD_DOWN
    }
}

/**
 * Advances the manual clock [count] frames. Each frame also idles the main looper, which carries
 * snapshot notifications under Robolectric; advancing the clock alone does not.
 */
internal fun NovaTestRule.frames(count: Int = 4) = repeat(count) {
    mainClock.advanceTimeByFrame()
    waitForIdle()
}

/** Advances the manual clock by [millis], then idles the main looper. */
internal fun NovaTestRule.advance(millis: Long) {
    mainClock.advanceTimeBy(millis)
    waitForIdle()
}
