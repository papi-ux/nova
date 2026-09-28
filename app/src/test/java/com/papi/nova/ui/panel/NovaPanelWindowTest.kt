package com.papi.nova.ui.panel

import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import com.papi.nova.utils.SpinnerDialog
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The window NovaSurfaces owns: how it is laid out, and that only NovaSurfaces closes it, so input
 * and focus always go back where they came from.
 *
 * These tests drive the window without a compose test rule, and a composition outside one does not
 * advance reliably once earlier Robolectric tests have run in the same JVM. So they call
 * [NovaSurfaces.onWindowIdle] themselves, as the window's content does once the panel's exit has
 * landed and no state page is left, rather than waiting for that content to get there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPanelWindowTest {
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()
    private lateinit var opener: View
    private lateinit var other: View

    private fun newActivity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }.get().apply {
            opener = focusableView()
            other = focusableView()
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(opener, LinearLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
                    addView(other, LinearLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
                },
            )
        }

    // A view with no size cannot take focus once it has been laid out.
    private fun ComponentActivity.focusableView() = View(this).apply {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    @After
    fun destroyActivities() {
        controllers.forEach { it.pause().stop().destroy() }
        idle(50)
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun latestWindow() = ShadowDialog.getLatestDialog() as ComponentDialog

    private fun menu() = NovaCommonPage.Menu(
        key = "host",
        title = "Living Room",
        items = listOf(NovaMenuItem.Action(key = "wake", label = "Wake", onClick = {})),
    )

    @Test
    fun theWindowFillsTheScreenRatherThanFloatingInsideTheBars() {
        val activity = newActivity()
        NovaSurfaces.of(activity).open(menu())
        idle(50)

        val window = latestWindow().window!!
        assertFalse("a floating window is fitted inside visible bars and leaves a gap", window.isFloating)
        assertTrue(
            "laid out against the screen, not inside the decor insets",
            window.attributes.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0,
        )
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
    }

    @Test
    fun aBackThatNoHandlerTakesLeavesTheWindowForNovaSurfacesToClose() {
        val activity = newActivity()
        val spinner = SpinnerDialog.displayDialog(activity, "Adding PC", "Looking for the host", false)
        idle(50)
        val window = latestWindow()

        // Before a Busy page shows nothing takes Back (as during a panel's exit motion), so this
        // reaches the dialog's own fallback, which cancels a cancelable dialog.
        window.onBackPressedDispatcher.onBackPressed()
        idle(50)

        assertTrue("a wait that cannot be cancelled keeps its window", window.isShowing)
        val surfaces = NovaSurfaces.of(activity)
        assertEquals(1, surfaces.states.value.size)
        spinner.dismiss()
        surfaces.onWindowIdle()
        assertFalse("NovaSurfaces closes it once nothing is left to show", window.isShowing)
    }

    @Test
    fun closingThePanelReturnsFocusToItsOpenerOnlyOnce() {
        val activity = newActivity()
        val surfaces = NovaSurfaces.of(activity)
        surfaces.open(menu(), returnFocus = NovaFocusReturn.View(opener))
        idle(50)
        val panelWindow = latestWindow()
        surfaces.panel.close()
        surfaces.onWindowIdle()
        idle(50)
        assertFalse(panelWindow.isShowing)
        assertTrue("closing the panel returned focus to its opener", opener.isFocused)

        other.requestFocus()
        val spinner = SpinnerDialog.displayDialog(activity, "Adding PC", "", false)
        idle(50)
        val spinnerWindow = latestWindow()
        spinner.dismiss()
        surfaces.onWindowIdle()
        idle(50)

        assertFalse("the window closed", spinnerWindow.isShowing)
        assertTrue("a wait that only held a state page leaves focus where it was", other.isFocused)
    }

    @Test
    fun aWindowDismissedFromOutsideStillClosesThePanelAndReturnsFocus() {
        val activity = newActivity()
        val surfaces = NovaSurfaces.of(activity)
        surfaces.open(menu(), returnFocus = NovaFocusReturn.View(opener))
        idle(50)
        other.requestFocus()

        latestWindow().dismiss()
        idle(50)

        assertFalse(surfaces.panel.isOpen)
        assertTrue(opener.isFocused)
    }

    private companion object {
        const val VIEW_SIZE = 100
    }
}
