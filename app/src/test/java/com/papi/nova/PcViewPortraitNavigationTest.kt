package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import java.security.cert.X509Certificate
import java.time.Duration
import com.papi.nova.preferences.AddComputerManually
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import kotlin.math.roundToInt
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
            val activity = it.get()
            // ShadowActivity has its own currentFocus field; unlike PhoneWindow it does not
            // follow the real View tree. Forward actual focus events for this Activity boundary.
            activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
                shadowOf(activity).setCurrentFocus(focused)
            }
            shadowOf(activity).setCurrentFocus(activity.window.decorView.findFocus())
            idleAndLayout(activity)
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

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun liveHostPowerGlyphsSurviveStateRefreshAndRailCollapse() {
        val activity = open().get()
        val model = ViewModelProvider(activity)[PcViewModel::class.java]
        @Suppress("UNCHECKED_CAST")
        val computers = model.computersLiveData as MutableLiveData<List<PcViewModel.ComputerObject>>
        val update = PcView::class.java.getDeclaredMethod("updateHostPowerAction").apply { isAccessible = true }
        val button = activity.findViewById<MaterialButton>(R.id.actionStartPolaris)
        val toggle = activity.findViewById<View>(R.id.dashboardRailToggle)
        for (online in listOf(false, true, false)) {
            // A pinned, address-less fixture reaches the actual host-state observer without
            // allowing any host request. This test never activates the power action.
            computers.value = listOf(PcViewModel.ComputerObject(ComputerDetails().apply {
                uuid = "owned-style-power"; name = "Owned style host"
                state = if (online) ComputerDetails.State.ONLINE else ComputerDetails.State.OFFLINE
                pairState = PairingManager.PairState.PAIRED
                serverCert = mock(X509Certificate::class.java)
            }))
            update.invoke(activity)
            idleAndLayout(activity)
            val expectedIcon = if (online) R.drawable.ic_host_sleep else R.drawable.ic_host_wake
            val expectedText = activity.getString(if (online) R.string.pcview_quick_sleep_host else R.string.pcview_quick_start_polaris)
            assertEquals("The actual runtime refresh supplies the state's glyph", expectedIcon, shadowOf(button.icon).createdFromResId)
            assertEquals(expectedText, button.text.toString())
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            update.invoke(activity)
            idleAndLayout(activity)
            assertEquals("", button.text.toString())
            assertEquals(expectedText, button.contentDescription.toString())
            assertEquals(expectedIcon, shadowOf(button.icon).createdFromResId)
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            idleAndLayout(activity)
            assertEquals(expectedText, button.text.toString())
            assertEquals(expectedIcon, shadowOf(button.icon).createdFromResId)
        }
    }

    @Test fun portraitNavigationUsesGroupedRowsWithAlignedIcons() = checkNavigationRows()

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun landscapeNavigationUsesGroupedRowsWithAlignedIcons() = checkNavigationRows()

    private fun checkNavigationRows() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        for (size in listOf("compact", "standard", "large")) {
            preferences.edit().putString("nova_control_size", size).commit()
            val activity = open().get()
            val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            if (portrait) menu(activity).performClick()
            idleAndLayout(activity)
            val density = activity.resources.displayMetrics.density
            val group = activity.findViewById<MaterialCardView>(R.id.hostsNavigationActions)
            assertTrue("Supporting actions share one bounded group", group.isShown)
            assertTrue("Group corners remain bounded rather than pill-shaped", group.radius <= 10 * density)
            val buttons = listOf(R.id.actionStartPolaris, R.id.profilesButton, R.id.actionTheme,
                R.id.actionGithub, R.id.actionSettings).map { activity.findViewById<MaterialButton>(it) }
            buttons.forEach { button ->
                assertEquals("Icons use the same start column", MaterialButton.ICON_GRAVITY_START, button.iconGravity)
                assertEquals("Labels align at the start", Gravity.START, button.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                assertEquals(View.TEXT_ALIGNMENT_GRAVITY, button.textAlignment)
                assertEquals(0f, button.letterSpacing, 0f)
                assertEquals((20 * density).roundToInt(), button.iconSize)
                assertTrue("Rows use bounded corners", button.cornerRadius <= 8 * density)
                assertTrue("The visible row fills its own 48dp target, without floating-pill gaps",
                    button.height - button.insetTop - button.insetBottom >= 48 * density - 1)
                var parent = button.parent
                while (parent is View && parent !== group) parent = parent.parent
                assertSame("Rows belong to the same navigation group", group, parent)
            }
            val first = IntArray(2); val second = IntArray(2)
            if (portrait) {
                buttons[0].getLocationInWindow(first); buttons[2].getLocationInWindow(second)
                assertTrue("Portrait row gap stays compact", second[1] - first[1] - buttons[0].height in 0..(4 * density).toInt())
            } else {
                buttons.zipWithNext().forEach { (a,b) ->
                    a.getLocationInWindow(first); b.getLocationInWindow(second)
                    assertTrue("Landscape rows stay together without overlap", second[1] - first[1] - a.height in 0..(4 * density).toInt())
                }
            }
        }
    }

    @Test fun portraitModeContentsStayCenteredInTheirActualControlTargets() = checkModeCardContents()

    @Test
    @Config(qualifiers = "w900dp-h480dp-land")
    fun landscapeModeContentsStayCenteredInTheirActualControlTargets() = checkModeCardContents()

    private fun checkModeCardContents() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        for (size in listOf("compact", "standard", "large")) {
            preferences.edit().putString("nova_control_size", size).commit()
            val activity = open().get()
            val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            if (portrait) menu(activity).performClick()
            idleAndLayout(activity)
            val density = activity.resources.displayMetrics.density
            val cards = listOf(R.id.modeServers, R.id.modeLibrary).map { activity.findViewById<MaterialCardView>(it) }
            cards.forEach { card ->
                val content = card.getChildAt(0) as LinearLayout
                val label = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<TextView>().last()
                val targetLocation = IntArray(2); val labelLocation = IntArray(2)
                card.getLocationInWindow(targetLocation); label.getLocationInWindow(labelLocation)
                assertTrue("$size mode retains its own 48dp target", card.height >= 48 * density - 1)
                assertEquals("$size mode label centers in the target, not in a shorter top-aligned child",
                    targetLocation[1] + card.height / 2f, labelLocation[1] + label.height / 2f, 1.5f)
                assertEquals("Mode labels use a readable size independent of Control Size",
                    (if (portrait) 17f else 14f) * activity.resources.displayMetrics.scaledDensity, label.textSize, .01f)
            }
            assertTrue("The current Hosts segment is selected", cards[0].isSelected)
            assertFalse("The Library action remains unselected", cards[1].isSelected)
            assertNotEquals("Selection has a readable fill independent of the focus ring",
                cards[0].cardBackgroundColor.defaultColor, cards[1].cardBackgroundColor.defaultColor)
        }
    }

    @Test fun portraitUpdateStatusUsesItsOwnFullWidthRowAndCompleteVersionText() {
        val activity = open().get()
        menu(activity).performClick()
        idleAndLayout(activity)
        val update = activity.findViewById<MaterialCardView>(R.id.actionNovaUpdate)
        val settings = activity.findViewById<View>(R.id.actionSettings)
        val group = activity.findViewById<View>(R.id.hostsNavigationActions)
        val updateLocation = IntArray(2); val groupLocation = IntArray(2)
        update.getLocationInWindow(updateLocation); group.getLocationInWindow(groupLocation)
        assertEquals("Update row fills the same width as the grouped navigation", group.width, update.width)
        assertEquals(groupLocation[0], updateLocation[0])
        assertTrue("Update lives below the Settings row", updateLocation[1] >= groupLocation[1] + group.height)
        assertEquals("Settings moves down into the update row", R.id.actionNovaUpdate, settings.nextFocusDownId)
        assertEquals("Update returns up to Settings", R.id.actionSettings, update.nextFocusUpId)
        assertEquals("Update moves down to Hosts", R.id.modeServers, update.nextFocusDownId)
        val version = activity.findViewById<TextView>(R.id.updateVersionLabel)
        // Exercise the delivered Beta string without altering BuildConfig or the real update
        // model. The actual geometry must hold it, not just an accessible truncated caption.
        version.text = "Nova 1.4.14-beta"
        idleAndLayout(activity)
        assertNull("Version does not ellipsize", version.ellipsize)
        assertTrue("The complete Beta version has usable width", version.layout.getEllipsisCount(0) == 0 &&
            version.layout.getLineEnd(version.layout.lineCount - 1) == version.text.length)
        assertTrue(update.height >= 48 * activity.resources.displayMetrics.density - 1)
    }

    companion object {
        @JvmStatic @BeforeClass fun suppressLogs() { TestLogSuppressor.install() }
    }
}
