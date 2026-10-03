package com.papi.nova

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The debug screen's pages as a pad and a finger meet them in the panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugInfoPagesComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = NovaPanelState()

    private fun host(): NovaTestKeys = rule.setPanelContent { NovaPageStackHost(state = state) {} }

    private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    @Test
    fun theGamepadListOpensOnTheChosenGamepadAndOneWithNoVibratorCannotBePicked() {
        val chosen = mutableListOf<DebugGamepad>()
        val pads = listOf(
            DebugGamepad(3, "Handheld controls", "2020_3001", hasVibrator = false),
            DebugGamepad(5, "Wireless controller", "054c_0ce6", hasVibrator = true),
        )
        state.open(DebugInfoPages.gamepads(rule.activity, pads, current = 5) { chosen += it })
        val keys = host()

        rule.onNodeWithText("Wireless controller").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithText(string(R.string.debug_info_no_vibrator)).assertExists()

        keys.press(NovaTestKeys.UP)
        rule.onNodeWithText("Handheld controls").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertTrue("A on a gamepad with no vibrator does nothing: its row says why", chosen.isEmpty())
        assertTrue(state.isOpen)

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(listOf(5), chosen.map { it.id })
    }

    @Test
    fun theVibrationTypesOpenOnTheOneRunLast() {
        var chosen: DebugVibration? = null
        state.open(
            DebugInfoPages.vibration(
                context = rule.activity,
                key = DebugInfoPages.DEVICE_VIBRATION_PAGE,
                title = string(R.string.debug_info_device_vibration_title),
                current = DebugVibration.Continuous,
                amplitude = 180,
            ) { chosen = it },
        )
        val keys = host()

        rule.onNodeWithText(string(R.string.debug_info_continuous_hd_vibration)).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithText(string(R.string.debug_info_continuous_caption, 180)).assertExists()

        keys.press(NovaTestKeys.UP)
        keys.press(NovaTestKeys.CENTER)
        assertEquals(DebugVibration.Simple, chosen)
        assertFalse("one A picks and the root page closes the panel", state.isOpen)
    }

    @Test
    fun theAmplitudeTrackStepsByFiveStopsAtFullAndSavesOnSave() {
        var saved: Int? = null
        state.open(DebugInfoPages.amplitude(rule.activity, 240) { saved = it })
        val keys = host()

        rule.onNodeWithText("240 of 255").assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        rule.onNodeWithText("245 of 255").assertIsFocused()
        repeat(4) { keys.press(NovaTestKeys.RIGHT) }
        rule.onNodeWithText("255 of 255").assertIsFocused()
        assertNull("moving the track saves nothing", saved)

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        rule.onNodeWithText(string(R.string.nova_panel_save)).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        assertEquals(255, saved)
        assertFalse(state.isOpen)
    }
}
