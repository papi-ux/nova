package com.papi.nova.ui

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.R
import com.papi.nova.utils.MouseModeOption
import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NovaStreamSheetsInstrumentationTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private var sheet: BottomSheetDialog? = null
    private val appliedMode = AtomicInteger(Int.MIN_VALUE)
    private val ended = AtomicInteger(0)
    private val stayed = AtomicInteger(0)

    @After fun dismissSheet() { activity.scenario.onActivity { sheet?.dismiss() } }

    @Test fun mousePickerStartsOnCurrentModeThenDownAndAAppliesTheOriginalModeIndex() {
        showMousePicker()
        action("nova-mouse-mode-3").check(matches(hasFocus()))
            .perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
        action("nova-mouse-mode-4").check(matches(hasFocus()))
            .perform(pressKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(4, appliedMode.get())
        activity.scenario.onActivity { assertFalse(sheet!!.isShowing) }
    }

    @Test fun mousePickerBCancelsWithoutApplyingTheHighlightedMode() {
        showMousePicker()
        action("nova-mouse-mode-3").perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
        action("nova-mouse-mode-4").check(matches(hasFocus()))
            .perform(pressKey(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Int.MIN_VALUE, appliedMode.get())
        activity.scenario.onActivity { assertFalse(sheet!!.isShowing) }
    }

    @Test fun theOpeningButtonsReleaseAloneCannotApplyAChoice() {
        showMousePicker()
        action("nova-mouse-mode-3").check(matches(hasFocus()))
        activity.scenario.onActivity {
            sheet!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            assertTrue(sheet!!.isShowing)
        }
        assertEquals(Int.MIN_VALUE, appliedMode.get())
    }

    @Test fun aTwoActionConfirmationStartsOnStayAndOneAIsSafe() {
        showConfirmation()
        action("stay").check(matches(hasFocus())).perform(pressKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1, stayed.get())
        assertEquals(0, ended.get())
    }

    @Test fun aTwoActionConfirmationNeedsDownThenAToEnd() {
        showConfirmation()
        action("stay").check(matches(hasFocus())).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
        action("end").check(matches(hasFocus())).perform(pressKey(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(0, stayed.get())
        assertEquals(1, ended.get())
    }

    private fun action(tag: String) = onView(withTagValue(equalTo<Any>(tag))).inRoot(isDialog())

    private fun showMousePicker() {
        activity.scenario.onActivity { host ->
            val context = ContextThemeWrapper(host, R.style.SettingsTheme)
            // The external-display subset keeps its original indexes; list positions are not modes.
            val options = listOf(
                MouseModeOption(2, "Track pad (Natural)"),
                MouseModeOption(3, "Track pad (Gaming)"),
                MouseModeOption(4, "Disabled"),
                MouseModeOption(-1, "Toggle local cursor"),
            )
            sheet = NovaMouseModePicker.create(context, options, 3) { appliedMode.set(it.index) }
            sheet!!.show()
        }
    }

    private fun showConfirmation() {
        activity.scenario.onActivity { host ->
            val context = ContextThemeWrapper(host, R.style.SettingsTheme)
            val dialog = BottomSheetDialog(context)
            sheet = dialog
            val content = NovaSheetChrome.createSheetContainer(context)
            val stay = TextView(context).apply {
                id = View.generateViewId()
                tag = "stay"
                setText(R.string.game_dialog_action_stay_in_game)
                NovaSheetChrome.styleSheetAction(this)
                setOnClickListener { stayed.incrementAndGet(); dialog.dismiss() }
            }
            val end = TextView(context).apply {
                id = View.generateViewId()
                tag = "end"
                setText(R.string.game_dialog_action_end_session)
                NovaSheetChrome.styleSheetAction(this, destructive = true)
                setOnClickListener { ended.incrementAndGet(); dialog.dismiss() }
            }
            stay.nextFocusDownId = end.id
            end.nextFocusUpId = stay.id
            for (row in listOf(stay, end)) {
                content.addView(row, LinearLayout.LayoutParams(-1, (48 * context.resources.displayMetrics.density).toInt()))
            }
            dialog.setContentView(content)
            dialog.setOnShowListener {
                NovaSheetChrome.applyBottomSheetChrome(dialog, content)
                NovaStreamSheetFocus.onShow(dialog, stay)
            }
            dialog.show()
        }
    }
}
