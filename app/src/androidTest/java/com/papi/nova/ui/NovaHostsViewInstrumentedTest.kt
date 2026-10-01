package com.papi.nova.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.PcView
import com.papi.nova.BuildConfig
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/** Owned-emulator proof of real Hosts View routing; no paired-host or streaming fixture. */
@RunWith(AndroidJUnit4::class)
class NovaHostsViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontPercent: Int
        get() = InstrumentationRegistry.getArguments().getString("fontPercent", "80").toInt()

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(450)
        instrumentation.waitForIdleSync()
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }

    private fun withPreferences(block: () -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val saved = preferences.all
        val welcome = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        val oldWelcome = welcome.getBoolean("welcome_seen", false)
        // A supported static theme keeps the fixture's idle gate independent of particle draws.
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_OLED)
        preferences.edit().putString("nova_control_size", "standard")
            .putBoolean("nova_dashboard_rail_collapsed", false)
            .putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, fontPercent).commit()
        welcome.edit().putBoolean("welcome_seen", true).commit()
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit()
            .putString("Renderer", "OwnedHostsFixture").putString("Fingerprint", android.os.Build.FINGERPRINT).commit()
        try { block() } finally {
            val edit = preferences.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            } }
            edit.commit()
            welcome.edit().putBoolean("welcome_seen", oldWelcome).commit()
        }
    }

    @Test fun openingHostsMenuAndControllerFocusUseTheActualActivity() = withPreferences {
        ActivityScenario.launch(PcView::class.java).use { scenario ->
            settle()
            // A preceding raw-touch test may leave Android in touch mode. Enter controller mode
            // through real D-pad input before requesting a non-touch-focusable action.
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            var portrait = false
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                assertEquals("The actual Hosts Activity uses the requested Nova text size",
                    NovaFontScalePreferences.resolveFontScale(NovaFontScalePreferences.readSystemFontScale(activity), fontPercent),
                    activity.resources.configuration.fontScale, .001f)
                val toggle = activity.findViewById<TextView>(R.id.dashboardRailToggle)
                assertNotNull(toggle)
                if (portrait) {
                    assertEquals("Menu", toggle.text.toString())
                    assertFalse(activity.findViewById<View>(R.id.actionSettings).isShown)
                    assertTrue(activity.findViewById<View>(R.id.emptyAddServer).isShown)
                    assertTrue(activity.findViewById<View>(R.id.filterAllServers).isShown)
                }
                assertTrue(toggle.requestFocus())
            }
            shot("hosts-opening")
            key(KeyEvent.KEYCODE_BUTTON_A)
            if (portrait) {
                scenario.onActivity { activity ->
                    assertEquals("Hide menu", activity.findViewById<TextView>(R.id.dashboardRailToggle).text.toString())
                    assertEquals(R.id.dashboardRailToggle, activity.currentFocus?.id)
                    assertTrue(activity.findViewById<View>(R.id.actionSettings).isShown)
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertEquals(R.id.actionStartPolaris, it.currentFocus?.id) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertEquals(R.id.actionTheme, it.currentFocus?.id) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertEquals(R.id.actionSettings, it.currentFocus?.id) }
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    assertEquals("Hide menu", activity.findViewById<TextView>(R.id.dashboardRailToggle).text.toString())
                    assertEquals(R.id.actionSettings, activity.currentFocus?.id)
                }
            } else {
                scenario.onActivity { activity ->
                    assertEquals("", activity.findViewById<TextView>(R.id.actionAddServer).text.toString())
                    val toggle = activity.findViewById<MaterialButton>(R.id.dashboardRailToggle)
                    assertEquals(MaterialButton.ICON_GRAVITY_TEXT_START, toggle.iconGravity)
                    assertEquals(Gravity.CENTER, toggle.gravity)
                    listOf(R.id.actionStartPolaris, R.id.profilesButton, R.id.actionTheme, R.id.actionGithub, R.id.actionSettings).forEach { id ->
                        val button = activity.findViewById<MaterialButton>(id)
                        assertEquals(MaterialButton.ICON_GRAVITY_TEXT_START, button.iconGravity)
                        assertEquals(Gravity.CENTER, button.gravity)
                        val rect = Rect()
                        assertTrue(button.getGlobalVisibleRect(rect))
                        assertTrue("Collapsed icons retain their full horizontal touch slots", rect.width() >= button.width - 1)
                    }
                }
                shot("hosts-collapsed")
                key(KeyEvent.KEYCODE_BUTTON_A)
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.pcview_quick_add_server),
                        activity.findViewById<TextView>(R.id.actionAddServer).text.toString())
                }
            }
            shot("hosts-navigation")
        }
    }

    @Test fun panelBackKeepsPortraitMenuUntilTheNextBackAndSizingKeepsTargetsSeparate() = withPreferences {
        ActivityScenario.launch(PcView::class.java).use { scenario ->
            settle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            var portrait = false
            lateinit var themeTarget: View
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if (portrait) activity.findViewById<View>(R.id.dashboardRailToggle).performClick()
                themeTarget = activity.findViewById(R.id.actionTheme)
                assertTrue(themeTarget.requestFocus())
            }
            settle()
            // A real touch at the lower edge exercises the whole row, including the space that
            // used to sit outside the smaller pill background, without a host/network action.
            tapLowerEdge(themeTarget)
            scenario.onActivity { assertTrue(NovaSurfaces.existing(it)?.panel?.isOpen == true) }
            key(KeyEvent.KEYCODE_BUTTON_B)
            scenario.onActivity { activity ->
                assertFalse(NovaSurfaces.existing(activity)?.panel?.isOpen == true)
                assertFalse(activity.isFinishing)
                if (portrait) assertEquals("Hide menu", activity.findViewById<TextView>(R.id.dashboardRailToggle).text.toString())
            }
            shot("hosts-touch-panel-back")
            if (portrait) {
                // Raw touch -> B above proves panel closure while Android remains in touch
                // mode. Start this controller-navigation segment through a real D-pad key;
                // a B-only touch-to-controller focus transition is a separate boundary.
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_BUTTON_B)
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals("Menu", activity.findViewById<TextView>(R.id.dashboardRailToggle).text.toString())
                    assertEquals(R.id.dashboardRailToggle, activity.currentFocus?.id)
                    activity.findViewById<View>(R.id.dashboardRailToggle).performClick()
                }
            }
            for (size in listOf("compact", "standard", "large", "standard")) {
                scenario.moveToState(Lifecycle.State.CREATED)
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("nova_control_size", size).commit()
                scenario.moveToState(Lifecycle.State.RESUMED)
                settle()
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    listOf(R.id.dashboardRailToggle, R.id.actionStartPolaris, R.id.profilesButton,
                        R.id.actionTheme, R.id.actionGithub, R.id.actionSettings, R.id.actionAddServer).forEach { id ->
                        val target = activity.findViewById<View>(id)
                        assertTrue("$size action $id width", target.width >= 48 * density - 1)
                        assertTrue("$size action $id height", target.height >= 48 * density - 1)
                    }
                    val group = activity.findViewById<MaterialCardView>(R.id.hostsNavigationActions)
                    assertTrue("One visible navigation group", group.isShown && group.radius <= 10 * density)
                    listOf(R.id.actionStartPolaris, R.id.profilesButton, R.id.actionTheme, R.id.actionGithub, R.id.actionSettings).forEach { id ->
                        val row = activity.findViewById<MaterialButton>(id)
                        assertEquals(MaterialButton.ICON_GRAVITY_START, row.iconGravity)
                        assertEquals(Gravity.START, row.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                        assertEquals(View.TEXT_ALIGNMENT_GRAVITY, row.textAlignment)
                        assertTrue("Bounded row corners", row.cornerRadius <= 8 * density)
                        assertTrue("Visible navigation row fills its full target", row.height - row.insetTop - row.insetBottom >= 48 * density - 1)
                        assertEquals((20 * density).roundToInt(), row.iconSize)
                    }
                    val theme = activity.findViewById<View>(R.id.actionTheme)
                    val github = activity.findViewById<View>(R.id.actionGithub)
                    val a = IntArray(2); val b = IntArray(2)
                    theme.getLocationInWindow(a); github.getLocationInWindow(b)
                    assertTrue("Distinct sibling targets do not overlap", a[0] + theme.width <= b[0] ||
                        b[0] + github.width <= a[0] || a[1] + theme.height <= b[1] || b[1] + github.height <= a[1])
                }
                shot("hosts-$size")
            }
        }
    }

    @Test fun supportingActionsScrollWhollyIntoTheirNavigationPane() = withPreferences {
        ActivityScenario.launch(PcView::class.java).use { scenario ->
            settle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            var portrait = false
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if (portrait) activity.findViewById<View>(R.id.dashboardRailToggle).performClick()
            }
            settle()
            val rows = listOf(R.id.actionStartPolaris, R.id.profilesButton, R.id.actionTheme,
                R.id.actionGithub, R.id.actionSettings, R.id.actionNovaUpdate, R.id.modeServers,
                R.id.modeLibrary, R.id.actionAddServer, R.id.actionScanPair)
            for (id in rows) {
                scenario.onActivity { activity ->
                    val action = activity.findViewById<View>(id)
                    assertTrue("Visible supporting action $id accepts focus", action.isShown && action.requestFocus())
                }
                settle()
                scenario.onActivity { activity ->
                    val action = activity.findViewById<View>(id)
                    val pane = activity.findViewById<View>(if (portrait) R.id.dashboardPortraitNavigation else R.id.dashboardCockpitRail)
                    val visible = Rect()
                    assertEquals("Supporting action keeps actual Activity focus", id, activity.currentFocus?.id)
                    assertTrue("Focused action has visible pixels", action.getGlobalVisibleRect(visible))
                    assertTrue("Focused action is not clipped above or below the navigation viewport",
                        visible.height() >= action.height - 1)
                    assertTrue("Focused action is not clipped across the navigation viewport", visible.width() >= action.width - 1)
                    val density = activity.resources.displayMetrics.density
                    assertTrue("Supporting navigation leaves a computer pane", if (portrait)
                        pane.height < activity.window.decorView.height * .4f else pane.width < activity.window.decorView.width * .4f)
                    assertTrue("Supporting action retains its own touch target", action.width >= 48 * density - 1 && action.height >= 48 * density - 1)
                }
            }
            shot("hosts-navigation-last-action")
        }
    }

    @Test fun boundHostCardsResizeWithoutTextScalingAndRawManageTouchesStayOwned() = withPreferences {
        ActivityScenario.launch(PcView::class.java).use { scenario ->
            var managePresses = 0
            var primaryPresses = 0
            var originalText = 0f
            var standardPadding = 0
            for (size in listOf("standard", "compact", "large", "standard")) {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("nova_control_size", size).commit()
                lateinit var manage: View
                scenario.onActivity { activity ->
                    val adapter = PcGridAdapter(activity, PreferenceConfiguration())
                    adapter.setItems(listOf(PcViewModel.ComputerObject(ComputerDetails().apply {
                        uuid = "owned-native-host"; name = "Living room computer with a long name"; state = ComputerDetails.State.ONLINE
                    })))
                    adapter.setOnItemClickListener { primaryPresses++ }
                    adapter.setOnServerActionListener { managePresses++ }
                    val holder = adapter.onCreateViewHolder(RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }, 0)
                    adapter.onBindViewHolder(holder, 0)
                    activity.setContentView(FrameLayout(activity).apply {
                        setPadding(16, 32, 16, 32)
                        addView(holder.itemView, FrameLayout.LayoutParams(-1, -2))
                    })
                    manage = holder.itemView.findViewById(R.id.server_actions_button)
                    val text = holder.itemView.findViewById<TextView>(R.id.grid_text).textSize
                    val padding = holder.itemView.findViewById<View>(R.id.server_card_body).paddingLeft
                    if (originalText == 0f) { originalText = text; standardPadding = padding }
                    assertEquals(originalText, text, 0f)
                    when (size) {
                        "compact" -> assertTrue(padding < standardPadding)
                        "large" -> assertTrue(padding > standardPadding)
                        else -> assertEquals(standardPadding, padding)
                    }
                }
                settle()
                val location = IntArray(2)
                instrumentation.runOnMainSync {
                    val density = manage.resources.displayMetrics.density
                    assertTrue(manage.width >= 48 * density - 1 && manage.height >= 48 * density - 1)
                    manage.getLocationOnScreen(location)
                }
                val before = managePresses
                val time = SystemClock.uptimeMillis()
                val x = location[0] + manage.width / 2f
                val y = location[1] + manage.height - 2f
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0)
                try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
                finally { down.recycle(); up.recycle() }
                settle()
                assertEquals("A raw touch at the Manage target's bottom edge stays on Manage", before + 1, managePresses)
                assertEquals(0, primaryPresses)
                shot("host-card-$size")
            }
        }
    }

    @Test fun destinationCardsCenterTheirContentAndPortraitUpdateShowsTheCompleteBetaLabel() = withPreferences {
        ActivityScenario.launch(PcView::class.java).use { scenario ->
            settle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            var portrait = false
            val geometry = JSONArray()
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if (portrait) activity.findViewById<View>(R.id.dashboardRailToggle).performClick()
            }
            settle()
            for (size in listOf("compact", "standard", "large")) {
                scenario.moveToState(Lifecycle.State.CREATED)
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("nova_control_size", size).commit()
                scenario.moveToState(Lifecycle.State.RESUMED)
                settle()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.modeServers).requestFocus()) }
                settle()
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    val cards = listOf(R.id.modeServers, R.id.modeLibrary).map { activity.findViewById<MaterialCardView>(it) }
                    cards.forEach { card ->
                        val content = card.getChildAt(0) as LinearLayout
                        val label = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<TextView>().last()
                        val targetLocation = IntArray(2); val labelLocation = IntArray(2)
                        card.getLocationInWindow(targetLocation); label.getLocationInWindow(labelLocation)
                        val offset = labelLocation[1] + label.height / 2f - targetLocation[1] - card.height / 2f
                        assertEquals("$size label centers in the real hit target", 0f, offset, 1.5f)
                        assertEquals((if (portrait) 17f else 14f) * activity.resources.displayMetrics.scaledDensity,
                            label.textSize, .01f)
                        assertTrue(card.width >= 48 * density - 1 && card.height >= 48 * density - 1)
                        assertFullyVisible(card)
                        assertFullLabel(label)
                        geometry.put(JSONObject().put("control_size", size).put("card", activity.resources.getResourceEntryName(card.id))
                            .put("width_px", card.width).put("height_px", card.height).put("label_center_offset_px", offset)
                            .put("label_text_size_px", label.textSize).put("selected", card.isSelected))
                    }
                    assertTrue(cards[0].isSelected)
                    assertFalse(cards[1].isSelected)
                    assertNotEquals(cards[0].cardBackgroundColor.defaultColor, cards[1].cardBackgroundColor.defaultColor)
                }
                shot("hosts-modes-$size")
                if (portrait) {
                    // Real directional input follows the changed visual rows. Requesting only
                    // an initial controller target keeps these transitions discriminating.
                    scenario.onActivity { assertTrue(it.findViewById<View>(R.id.actionSettings).requestFocus()) }
                    settle()
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    scenario.onActivity { assertEquals(R.id.actionNovaUpdate, it.currentFocus?.id) }
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    scenario.onActivity { assertEquals(R.id.modeServers, it.currentFocus?.id) }
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    scenario.onActivity { assertEquals(R.id.modeLibrary, it.currentFocus?.id) }
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    scenario.onActivity { assertEquals(R.id.actionNovaUpdate, it.currentFocus?.id) }
                } else {
                    scenario.onActivity { assertTrue(it.findViewById<View>(R.id.actionNovaUpdate).requestFocus()) }
                    settle()
                }
                scenario.onActivity { activity ->
                    val update = activity.findViewById<MaterialCardView>(R.id.actionNovaUpdate)
                    if (portrait) {
                        val group = activity.findViewById<View>(R.id.hostsNavigationActions)
                        assertEquals("Portrait update occupies the full grouped-rail width", group.width, update.width)
                    }
                    assertFullyVisible(update)
                    val version = activity.findViewById<TextView>(R.id.updateVersionLabel)
                    assertEquals("Actual update state still uses compiled version metadata",
                        activity.getString(R.string.pcview_update_pill_current_version, BuildConfig.VERSION_NAME), version.text.toString())
                    // Debug x86 geometry proof is separate from Root's Beta artifact gate. Stress
                    // the exact delivered Beta caption without changing updater/model/metadata.
                    version.text = "Nova 1.4.14-beta"
                }
                settle()
                scenario.onActivity { activity ->
                    val update = activity.findViewById<View>(R.id.actionNovaUpdate)
                    val version = activity.findViewById<TextView>(R.id.updateVersionLabel)
                    val status = activity.findViewById<TextView>(R.id.updateStatusLabel)
                    assertNull(version.ellipsize)
                    assertFullLabel(version)
                    assertFullLabel(status)
                    assertFullyVisible(update)
                    val density = activity.resources.displayMetrics.density
                    assertTrue(update.height >= 48 * density - 1)
                    geometry.put(JSONObject().put("control_size", size).put("card", "actionNovaUpdate")
                        .put("width_px", update.width).put("height_px", update.height)
                        .put("stress_version", version.text.toString()).put("lines", version.layout.lineCount))
                }
                shot("hosts-update-$size")
                lateinit var hostsTarget: View
                scenario.onActivity { activity ->
                    // Hosts is a local filter action: no pairing, power or updater network call.
                    activity.findViewById<View>(R.id.filterOnlineServers).performClick()
                    hostsTarget = activity.findViewById(R.id.modeServers)
                    assertTrue(hostsTarget.requestFocus())
                }
                settle()
                tapLowerEdge(hostsTarget)
                scenario.onActivity { activity ->
                    assertEquals("Raw touch in the mode target's lower edge retains the Hosts action",
                        R.id.filterAllServers, activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.serverFilterTabs).checkedChipId)
                }
            }
            val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "native")
            val directory = File(context.getExternalFilesDir(null), "hosts-view").apply { mkdirs() }
            File(directory, "hosts-card-geometry-$suffix.json").writeText(JSONObject()
                .put("compiled_version", BuildConfig.VERSION_NAME).put("build_type", BuildConfig.BUILD_TYPE)
                .put("font_percent", fontPercent).put("geometry", geometry).toString(2))
        }
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        assertTrue("Entire target is visible horizontally", visible.width() >= view.width - 1)
        assertTrue("Entire target is visible vertically", visible.height() >= view.height - 1)
    }

    private fun assertFullLabel(label: TextView) {
        assertNotNull(label.layout)
        assertTrue("All label characters are laid out", label.layout.getLineEnd(label.layout.lineCount - 1) == label.text.length)
        for (line in 0 until label.layout.lineCount) {
            assertEquals("No line ellipsizes", 0, label.layout.getEllipsisCount(line))
            assertTrue("Text fits its laid-out column", label.layout.getLineWidth(line) <= label.width - label.paddingLeft - label.paddingRight + 1f)
        }
        assertTrue("All text lines fit vertically", label.layout.height <= label.height - label.paddingTop - label.paddingBottom + 1)
    }

    private fun tapLowerEdge(target: View) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { target.getLocationOnScreen(location) }
        val time = SystemClock.uptimeMillis()
        val x = location[0] + target.width / 2f
        val y = location[1] + target.height - 2f
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, y, 0)
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
        finally { down.recycle(); up.recycle() }
        settle()
    }

    private fun shot(name: String) {
        val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "native")
        val directory = File(context.getExternalFilesDir(null), "hosts-view").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
