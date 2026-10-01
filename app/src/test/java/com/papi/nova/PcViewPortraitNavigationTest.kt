package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.preferences.AddComputerManually
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Exercises the opening Hosts Activity, rather than a model of its missing portrait rail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-port", shadows = [
    com.papi.nova.shadows.ShadowMoonBridge::class,
    com.papi.nova.shadows.ShadowGameManager::class,
])
class PcViewPortraitNavigationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<PcView>>()

    @Before fun prepare() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer").putString("Fingerprint", Build.FINGERPRINT).commit()
        shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    @After fun close() {
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun open(saved: Bundle? = null): ActivityController<PcView> =
        Robolectric.buildActivity(PcView::class.java).create(saved).start().resume().visible().also {
            controllers += it
            idleAndLayout(it.get())
        }

    private fun idleAndLayout(activity: PcView) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val density = activity.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((activity.resources.configuration.screenWidthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((activity.resources.configuration.screenHeightDp * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun menu(activity: PcView): TextView {
        val toggle = activity.findViewById<TextView>(R.id.dashboardRailToggle)
        assertNotNull("Opening Hosts needs a focusable portrait Menu/Hide action", toggle)
        return toggle!!
    }

    private fun press(activity: PcView, key: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        idleAndLayout(activity)
    }

    @Test fun freshPortraitHidesSupportingNavigationAndReclaimsTheComputerPane() {
        val activity = open().get()
        val toggle = menu(activity)
        assertEquals("Menu", toggle.text.toString())
        assertTrue(toggle.isShown && toggle.isFocusable)
        assertFalse(activity.findViewById<View>(R.id.actionSettings).isShown)
        assertTrue(activity.findViewById<View>(R.id.filterAllServers).isShown)
        val hiddenHeight = activity.findViewById<View>(R.id.pcFragmentContainer).height
        toggle.performClick()
        idleAndLayout(activity)
        assertTrue(activity.findViewById<View>(R.id.actionSettings).isShown)
        val expandedHeight = activity.findViewById<View>(R.id.pcFragmentContainer).height
        assertTrue("Hiding navigation returns space: hidden=$hiddenHeight expanded=$expandedHeight " +
            "header=${activity.findViewById<View>(R.id.pcViewHeader).height} root=${activity.window.decorView.height}",
            hiddenHeight > expandedHeight)
    }

    @Test fun aOpensAndClosesMenuInPlaceWithEveryRequiredActionReachable() {
        val activity = open().get()
        val toggle = menu(activity)
        assertTrue(toggle.requestFocus())
        press(activity, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("Hide menu", toggle.text.toString())
        assertTrue(toggle.hasFocus())
        listOf(R.id.actionStartPolaris, R.id.profilesButton, R.id.actionTheme, R.id.actionGithub,
            R.id.actionSettings, R.id.actionNovaUpdate, R.id.modeServers, R.id.modeLibrary,
            R.id.actionAddServer, R.id.actionScanPair).forEach { id ->
            val action = activity.findViewById<View>(id)
            assertTrue("Navigation action $id is visible and focusable", action.isShown && action.isFocusable)
            assertTrue("Navigation action $id accepts focus", action.requestFocus())
        }
        toggle.requestFocus()
        press(activity, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("Menu", toggle.text.toString())
        assertTrue(toggle.hasFocus())
        assertFalse(activity.findViewById<View>(R.id.actionAddServer).isShown)
    }

    @Test fun bClosesExpandedMenuBeforeActivityBackAndReturnsFocusFromHiddenAction() {
        val activity = open().get()
        menu(activity).performClick()
        idleAndLayout(activity)
        assertTrue(activity.findViewById<View>(R.id.actionSettings).requestFocus())
        press(activity, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse("First B only hides supporting navigation", activity.isFinishing)
        assertEquals("Menu", menu(activity).text.toString())
        assertTrue(menu(activity).hasFocus())
        press(activity, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue("A second B retains the existing Activity back behavior", activity.isFinishing)
    }

    @Test fun menuChoicesNeverOverwriteTheIndependentLandscapeCollapsePreference() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("nova_dashboard_rail_collapsed", true).commit()
        val activity = open().get()
        assertFalse(activity.findViewById<View>(R.id.actionSettings).isShown)
        menu(activity).performClick()
        idleAndLayout(activity)
        assertTrue(activity.findViewById<View>(R.id.actionSettings).isShown)
        menu(activity).performClick()
        assertTrue(preferences.getBoolean("nova_dashboard_rail_collapsed", false))
    }

    @Test fun firstPairRoutesRemainVisibleWithMenuHiddenAndManualAddUsesExistingActivity() {
        val activity = open().get()
        menu(activity)
        listOf(R.id.emptyRefresh, R.id.emptyAddServer, R.id.emptyScanPair).forEach { id ->
            val action = activity.findViewById<View>(id)
            assertTrue("First-pair route $id survives hidden menu", action.isShown && action.isFocusable)
        }
        activity.findViewById<View>(R.id.emptyAddServer).performClick()
        assertEquals(AddComputerManually::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun savedPortraitExpansionAndSemanticFocusRestoreWithoutChangingLandscapePreference() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putBoolean("nova_dashboard_rail_collapsed", false).commit()
        val controller = open()
        menu(controller.get()).performClick()
        idleAndLayout(controller.get())
        assertTrue(controller.get().findViewById<View>(R.id.actionSettings).requestFocus())
        val focusTarget = controller.get().findViewById<View>(R.id.actionSettings)
        assertEquals("Activity focus: target=${focusTarget.hasFocus()} attached=${focusTarget.isAttachedToWindow} " +
            "decor=${controller.get().window.decorView.findFocus()?.id} header=${controller.get().findViewById<View>(R.id.pcViewHeader).findFocus()?.id}",
            R.id.actionSettings, controller.get().currentFocus?.id)
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertEquals("Semantic focus is recorded before the lifecycle changes", R.id.actionSettings,
            saved.getInt("nova.pcview.focusAction", View.NO_ID))
        controller.pause().stop().destroy()
        controllers.remove(controller)
        val restored = open(saved).get()
        assertEquals("Hide menu", menu(restored).text.toString())
        assertTrue(restored.findViewById<View>(R.id.actionSettings).isShown)
        assertEquals(R.id.actionSettings, restored.currentFocus?.id)
        assertFalse(preferences.getBoolean("nova_dashboard_rail_collapsed", true))
    }

    @Test fun settingsReturnReappliesControlSizeWithoutCompoundingOrChangingText() {
        val controller = open()
        val activity = controller.get()
        val title = activity.findViewById<TextView>(R.id.pcViewTitle)
        val textSize = title.textSize
        val density = activity.resources.displayMetrics.density
        val header = activity.findViewById<View>(R.id.pcViewHeader)
        val standard = header.paddingLeft
        controller.pause()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("nova_control_size", "large").commit()
        controller.resume()
        idleAndLayout(activity)
        val large = header.paddingLeft
        assertTrue("View header spacing must apply the device Control Size", large > standard)
        controller.pause().resume()
        idleAndLayout(activity)
        assertEquals("Repeated resume cannot compound dimensions", large, header.paddingLeft)
        controller.pause()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("nova_control_size", "standard").commit()
        controller.resume()
        idleAndLayout(activity)
        assertEquals(standard, header.paddingLeft)
        assertEquals(textSize, title.textSize, 0f)
        assertEquals(density, activity.resources.displayMetrics.density, 0f)
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun landscapeStandardRetainsVisibleLabelsWithNonoverlapping48dpActionTargets() {
        val activity = open().get()
        val density = activity.resources.displayMetrics.density
        listOf(R.id.dashboardRailToggle, R.id.actionStartPolaris, R.id.profilesButton,
            R.id.actionTheme, R.id.actionGithub, R.id.actionSettings, R.id.actionAddServer).forEach { id ->
            val action = activity.findViewById<View>(id)
            assertTrue("Landscape action $id width must remain a real 48dp target", action.width >= 48 * density)
            assertTrue("Landscape action $id height must remain a real 48dp target", action.height >= 48 * density)
        }
        assertEquals(context.getString(R.string.pcview_quick_add_server),
            activity.findViewById<TextView>(R.id.actionAddServer).text.toString())
        assertTrue(activity.findViewById<View>(R.id.actionAddServer).bottom <=
            activity.findViewById<View>(R.id.actionScanPair).bottom)
    }

    companion object {
        @JvmStatic @BeforeClass fun suppressLogs() { TestLogSuppressor.install() }
    }
}
