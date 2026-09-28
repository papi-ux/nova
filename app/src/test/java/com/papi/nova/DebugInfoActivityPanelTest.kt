package com.papi.nova

import android.os.Looper
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/**
 * The debug screen's pickers open as pages in the right-edge panel (spec group 5): the amplitude
 * is a Slider page, and the vibration and gamepad lists are Choice pages that mark the current
 * value. These tests read the state layer: the pages each button puts up and what picking does.
 * NovaPanelSourceGuardTest keeps the old dialogs from coming back; debug.txt lists none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugInfoActivityPanelTest {
    private lateinit var controller: ActivityController<DebugInfoActivity>
    private lateinit var activity: DebugInfoActivity

    @Before
    fun startScreen() {
        controller = Robolectric.buildActivity(DebugInfoActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun closeScreen() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun panel() = NovaSurfaces.of(activity).panel

    private fun click(id: Int) {
        activity.findViewById<View>(id).performClick()
        idle()
    }

    /** What a Choice page does on a pick: it leaves (closing the panel at its root), then chooses. */
    private fun <T> pick(page: NovaCommonPage.Choice<T>, value: T) {
        if (!panel().pop()) panel().close()
        page.onChoose(value)
        idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> topChoice() = panel().top as NovaCommonPage.Choice<T>

    @Test
    fun theAmplitudeIsASliderPageAndOnlySaveChangesIt() {
        click(R.id.bt_vibrator_value)

        val page = panel().top as NovaCommonPage.Slider
        assertEquals(activity.getString(R.string.debug_info_amplitude_title), page.title)
        assertEquals(DebugInfoPages.DEFAULT_AMPLITUDE, page.value)
        assertEquals(0..255, page.range)
        assertEquals("Left and Right move five at a time, so 0 to 255 is 51 presses", 5, page.step)
        assertEquals("100 of 255", page.format(100))
        assertNull("the amplitude opens no dialog of its own", ShadowAlertDialog.getLatestAlertDialog())

        panel().close()
        idle()
        val button = activity.findViewById<Button>(R.id.bt_vibrator_value)
        assertEquals(
            "B left the page without saving, so the amplitude is as it was",
            activity.getString(R.string.debug_info_vibration_amplitude, DebugInfoPages.DEFAULT_AMPLITUDE),
            button.text.toString(),
        )

        click(R.id.bt_vibrator_value)
        val again = panel().top as NovaCommonPage.Slider
        panel().close()
        again.onSave(100)
        assertEquals(activity.getString(R.string.debug_info_vibration_amplitude, 100), button.text.toString())

        click(R.id.bt_vibrator_value)
        assertEquals("the page opens on the saved amplitude", 100, (panel().top as NovaCommonPage.Slider).value)
    }

    @Test
    fun deviceVibrationIsAChoiceThatMarksTheTypeThisDeviceRanLast() {
        click(R.id.bt_vibrator)

        val first = topChoice<DebugVibration>()
        assertEquals(activity.getString(R.string.debug_info_device_vibration_title), first.title)
        assertEquals(listOf(DebugVibration.Simple, DebugVibration.Continuous), first.options.map { it.value })
        assertNull("nothing has run yet, so nothing is current and focus starts on the first type", first.current)
        assertEquals(
            "the continuous type says which amplitude it runs at",
            activity.getString(R.string.debug_info_continuous_caption, DebugInfoPages.DEFAULT_AMPLITUDE),
            first.options[1].caption,
        )

        pick(first, DebugVibration.Continuous)
        val vibrator = shadowOf(activity.getSystemService(Vibrator::class.java))
        assertTrue("continuous HD vibration runs until it is stopped", vibrator.isVibrating)
        assertFalse(panel().isOpen)

        click(R.id.bt_vibrator)
        assertEquals(DebugVibration.Continuous, topChoice<DebugVibration>().current)
        panel().close()
        idle()

        click(R.id.bt_vibrator_cancle)
        assertFalse("Stop Vibration still stops it", vibrator.isVibrating)
    }

    @Test
    fun gamepadRumbleWithNoGamepadSaysSoAndOpensNothing() {
        click(R.id.bt_vibrator_gamepad)

        assertEquals(activity.getString(R.string.debug_info_no_gamepad_detected), ShadowToast.getTextOfLatestToast())
        assertFalse(panel().isOpen)
    }

    @Test
    fun aGamepadWithNoVibratorShowsWhyInsteadOfFailingAfterThePick() {
        val opener = activity.findViewById<View>(R.id.bt_vibrator_gamepad)
        activity.showGamepadRumblePages(listOf(pad(7), pad(9, hasVibrator = false)), opener, picked = null)

        val list = topChoice<Int>()
        assertEquals(activity.getString(R.string.debug_info_test_gamepad_rumble), list.title)
        assertEquals(listOf("Pad 7", "Pad 9"), list.options.map { it.label })
        assertEquals(
            listOf(null, activity.getString(R.string.debug_info_no_vibrator)),
            list.options.map { it.disabledReason },
        )
        assertEquals(activity.getString(R.string.debug_info_vid_pid) + "045e_0b07", list.options[0].caption)
        assertNull(list.current)
    }

    @Test
    fun pickingAGamepadPutsItsTypesOverTheListAndATypeReturnsToTheList() {
        val opener = activity.findViewById<View>(R.id.bt_vibrator_gamepad)
        val pads = listOf(pad(7), pad(8))
        activity.showGamepadRumblePages(pads, opener, picked = null)

        pick(topChoice<Int>(), 8)
        assertEquals("the panel stays open: the list is back under the types, so B returns to it", 2, panel().depth)
        val types = topChoice<DebugVibration>()
        assertEquals("the types page is titled with the gamepad it tests", "Pad 8", types.title)
        assertNull(types.current)

        pick(types, DebugVibration.Simple)
        val list = topChoice<Int>()
        assertEquals("picking a type pops to the list, which marks the gamepad just tested", 8, list.current)
        assertEquals(1, panel().depth)

        pick(list, 8)
        assertEquals("the types page marks the type that gamepad ran last", DebugVibration.Simple, topChoice<DebugVibration>().current)
        panel().pop()
        pick(topChoice<Int>(), 7)
        assertNull("each gamepad keeps its own last type", topChoice<DebugVibration>().current)
    }

    @Test
    fun bLeavesTheScreenOnReleaseThroughTheKeyGate() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        assertFalse("B acts on release, never on the press", activity.isFinishing)

        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        assertTrue("the debug screen opts into the gate, so a pad's B goes back like Back", activity.isFinishing)
    }

    private fun pad(id: Int, hasVibrator: Boolean = true) =
        DebugGamepad(id = id, name = "Pad $id", vidPid = "045e_0b%02x".format(id), hasVibrator = hasVibrator)
}
