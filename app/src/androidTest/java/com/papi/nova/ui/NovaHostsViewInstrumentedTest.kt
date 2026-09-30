package com.papi.nova.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.PcView
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned-emulator proof of real Hosts View routing; no paired-host or streaming fixture. */
@RunWith(AndroidJUnit4::class)
class NovaHostsViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

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
            .putBoolean("nova_dashboard_rail_collapsed", false).commit()
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
            var portrait = false
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
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
                scenario.onActivity { assertEquals("", it.findViewById<TextView>(R.id.actionAddServer).text.toString()) }
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
            var portrait = false
            scenario.onActivity { activity ->
                portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if (portrait) activity.findViewById<View>(R.id.dashboardRailToggle).performClick()
                activity.findViewById<View>(R.id.actionTheme).performClick()
            }
            settle()
            scenario.onActivity { assertTrue(NovaSurfaces.existing(it)?.panel?.isOpen == true) }
            key(KeyEvent.KEYCODE_BUTTON_B)
            scenario.onActivity { activity ->
                assertFalse(NovaSurfaces.existing(activity)?.panel?.isOpen == true)
                assertFalse(activity.isFinishing)
                if (portrait) assertEquals("Hide menu", activity.findViewById<TextView>(R.id.dashboardRailToggle).text.toString())
            }
            if (portrait) {
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

    private fun shot(name: String) {
        val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "native")
        val directory = File(context.getExternalFilesDir(null), "hosts-view").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
