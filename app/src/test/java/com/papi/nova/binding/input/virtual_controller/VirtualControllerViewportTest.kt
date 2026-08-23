package com.papi.nova.binding.input.virtual_controller

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class VirtualControllerViewportTest {
    private lateinit var context: Context
    private var originalMetricsWidth = 0
    private var originalMetricsHeight = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalMetricsWidth = context.resources.displayMetrics.widthPixels
        originalMetricsHeight = context.resources.displayMetrics.heightPixels
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(
                PreferenceConfiguration.ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING,
                PreferenceConfiguration.ONSCREEN_CONTROLLER_LAYOUT_PRESET_COMPACT_HANDHELD,
            )
            .putBoolean("checkbox_only_show_L3R3", false)
            .commit()
        compactProfile().edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.resources.displayMetrics.widthPixels = originalMetricsWidth
        context.resources.displayMetrics.heightPixels = originalMetricsHeight
        compactProfile().edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(PreferenceConfiguration.ONSCREEN_CONTROLLER_LAYOUT_PRESET_PREF_STRING)
            .remove("checkbox_only_show_L3R3")
            .commit()
    }

    @Test
    fun measuredLandscapeViewportWinsWhileResourceMetricsAreStillPortrait() {
        setResourceMetrics(width = 1080, height = 2400)
        val fixture = fixture(width = 2400, height = 1080)

        fixture.controller.refreshLayout()

        assertElementsInside(fixture.controller, width = 2400, height = 1080)
        assertFaceClusterAnchoredRight(fixture.controller, width = 2400)
    }

    @Test
    fun tallViewportFitsControlsWithoutNegativeRightAnchoring() {
        setResourceMetrics(width = 1080, height = 2400)
        val fixture = fixture(width = 1080, height = 2400)

        fixture.controller.refreshLayout()

        assertElementsInside(fixture.controller, width = 1080, height = 2400)
        assertFaceClusterAnchoredRight(fixture.controller, width = 1080)
    }

    @Test
    fun savedCoordinatesAreClampedInsideMeasuredViewport() {
        compactProfile().edit()
            .putString(
                VirtualControllerElement.EID_A.toString(),
                JSONObject()
                    .put("LEFT", 2600)
                    .put("TOP", 1200)
                    .put("WIDTH", 700)
                    .put("HEIGHT", 500)
                    .put("ENABLED", true)
                    .toString(),
            )
            .commit()
        val fixture = fixture(width = 2400, height = 1080)

        fixture.controller.refreshLayout()

        assertElementsInside(fixture.controller, width = 2400, height = 1080)
        assertFaceClusterAnchoredRight(fixture.controller, width = 2400)
    }

    @Test
    fun controllerRelayoutsWhenMeasuredViewportChanges() {
        setResourceMetrics(width = 1080, height = 2400)
        val fixture = fixture(width = 1080, height = 2400)
        fixture.controller.refreshLayout()
        val portraitLeft = elementParams(fixture.controller, VirtualControllerElement.EID_A).leftMargin

        fixture.parent.layout(0, 0, 2400, 1080)
        shadowOf(fixture.activity.mainLooper).idle()

        assertElementsInside(fixture.controller, width = 2400, height = 1080)
        val landscapeLeft = elementParams(fixture.controller, VirtualControllerElement.EID_A).leftMargin
        assertTrue(
            "A should move with the right edge after relayout: before=$portraitLeft after=$landscapeLeft parent=${fixture.parent.width}x${fixture.parent.height}",
            landscapeLeft > portraitLeft,
        )
        assertTrue("A should land in the landscape viewport's right half", landscapeLeft > 1200)
    }

    @Test
    fun hiddenControllerStaysHiddenAcrossViewportRelayout() {
        val fixture = fixture(width = 2400, height = 1080)
        fixture.controller.refreshLayout()
        fixture.controller.hide()

        fixture.parent.layout(0, 0, 2160, 1080)
        shadowOf(fixture.activity.mainLooper).idle()

        assertTrue("controller should retain its elements after relayout", fixture.controller.getElements().isNotEmpty())
        fixture.controller.getElements().forEach { element ->
            assertEquals(
                "hidden controller element ${element.elementId} became visible after relayout",
                View.GONE,
                element.visibility,
            )
        }
        assertEquals(
            "hidden configuration control should show, not hide, on the next toggle",
            1,
            fixture.controller.switchShowHide(),
        )
    }

    private fun fixture(width: Int, height: Int): Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        parent.layout(0, 0, width, height)
        return Fixture(
            activity = activity,
            parent = parent,
            controller = VirtualController(null, parent, activity),
        )
    }

    private fun compactProfile() =
        context.getSharedPreferences("OSC_compact_handheld", Context.MODE_PRIVATE)

    private fun setResourceMetrics(width: Int, height: Int) {
        context.resources.displayMetrics.widthPixels = width
        context.resources.displayMetrics.heightPixels = height
    }

    private fun assertElementsInside(controller: VirtualController, width: Int, height: Int) {
        assertTrue("controller should create touch elements", controller.getElements().isNotEmpty())
        controller.getElements().forEach { element ->
            val params = element.layoutParams as FrameLayout.LayoutParams
            assertTrue("element ${element.elementId} width must be positive", params.width > 0)
            assertTrue("element ${element.elementId} height must be positive", params.height > 0)
            assertTrue("element ${element.elementId} starts left of viewport", params.leftMargin >= 0)
            assertTrue("element ${element.elementId} starts above viewport", params.topMargin >= 0)
            assertTrue(
                "element ${element.elementId} exceeds viewport width: ${params.leftMargin}+${params.width}>$width",
                params.leftMargin + params.width <= width,
            )
            assertTrue(
                "element ${element.elementId} exceeds viewport height: ${params.topMargin}+${params.height}>$height",
                params.topMargin + params.height <= height,
            )
        }
    }

    private fun assertFaceClusterAnchoredRight(controller: VirtualController, width: Int) {
        val params = elementParams(controller, VirtualControllerElement.EID_A)
        assertTrue("A should remain in the viewport's right half", params.leftMargin >= width / 2)
    }

    private fun elementParams(controller: VirtualController, elementId: Int): FrameLayout.LayoutParams {
        val element = controller.getElements().firstOrNull { it.elementId == elementId }
        assertNotNull("missing controller element $elementId", element)
        return element!!.layoutParams as FrameLayout.LayoutParams
    }

    private data class Fixture(
        val activity: Activity,
        val parent: FrameLayout,
        val controller: VirtualController,
    )
}
