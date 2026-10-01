package com.papi.nova.preferences

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrElse
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import com.papi.nova.ui.NovaFontScalePreferences
import com.papi.nova.ui.NovaThemeManager
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Settings Activity on an owned emulator, without paired hosts or a streaming fixture. */
@RunWith(AndroidJUnit4::class)
class NovaSettingsActivityInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontPercent get() = InstrumentationRegistry.getArguments().getString("fontPercent", "80").toInt()
    private val portrait get() = InstrumentationRegistry.getArguments().getString("orientation", "portrait") == "portrait"
    private val categories get() = NovaSettingDefinitions.load(context).categories

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        compose.waitForIdle()
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); settle() }
    private fun category(key: String) = compose.onNodeWithTag("nova-settings-category-$key")
    private fun requestFocus(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        settle()
        node.assertIsFocused()
    }

    private fun withSettings(block: (ActivityScenario<StreamSettings>) -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val saved = preferences.all
        val oldTheme = NovaThemeManager.getTheme(context)
        // The same purple Polaris theme as the player's screenshot, with real Activity scaling.
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_POLARIS)
        preferences.edit().putString("nova_control_size", "standard")
            .putBoolean(NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY, true)
            .putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, fontPercent).commit()
        try {
            ActivityScenario.launch(StreamSettings::class.java).use { scenario ->
                settle()
                scenario.onActivity { activity ->
                    assertEquals("Requested orientation belongs to the actual Activity", portrait,
                        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    assertEquals("The actual Activity applies Nova text size",
                        NovaFontScalePreferences.resolveFontScale(NovaFontScalePreferences.readSystemFontScale(activity), fontPercent),
                        activity.resources.configuration.fontScale, .001f)
                }
                // Leave Android touch mode through real input before requesting a controller target.
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                block(scenario)
            }
        } finally {
            NovaThemeManager.setTheme(context, oldTheme)
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
        }
    }

    @Test fun menuHideAndBackRetainTheActualSettingsPaneAndControllerFocus() = withSettings { scenario ->
        if (portrait) {
            val toggle = compose.onNodeWithTag("nova-portrait-menu-toggle")
            toggle.assertIsDisplayed().assertTextEquals("Menu")
            compose.onNodeWithTag("nova-portrait-settings-navigation").assertDoesNotExist()
            shot("settings-opening")
            requestFocus(toggle)
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            settle()
            toggle.assertTextEquals("Menu") // Opening belongs to release, not press.
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            settle()
            toggle.assertTextEquals("Hide menu").assertIsFocused()
            shot("settings-expanded-default")
            val selected = categories.first { it.key == "category_input" }
            category(selected.key).performScrollTo()
            requestFocus(category(selected.key))
            key(KeyEvent.KEYCODE_BUTTON_A)
            category(selected.key).assertIsSelected()
            shot("settings-navigation")
            requestFocus(toggle)
            key(KeyEvent.KEYCODE_BUTTON_A)
            toggle.assertTextEquals("Menu").assertIsFocused()
            compose.onNodeWithTag("nova-portrait-settings-navigation").assertDoesNotExist()
            key(KeyEvent.KEYCODE_BUTTON_A)
            category(selected.key).assertIsSelected()
            key(KeyEvent.KEYCODE_BUTTON_B)
            toggle.assertTextEquals("Menu").assertIsFocused()
            compose.onNodeWithTag("nova-portrait-settings-navigation").assertDoesNotExist()
            scenario.onActivity { assertFalse("B hides navigation before leaving Settings", it.isFinishing) }
            shot("settings-navigation-hidden")
        } else {
            compose.onNodeWithTag("nova-portrait-menu-toggle").assertDoesNotExist()
            compose.onNodeWithTag("nova-portrait-settings-navigation").assertDoesNotExist()
            val owner = categories.first().key
            requestFocus(category(owner))
            shot("settings-opening")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(isFocused() and hasTestTagPrefix("nova-settings-row-")).assertIsDisplayed()
            key(KeyEvent.KEYCODE_BUTTON_B)
            category(owner).assertIsFocused()
            scenario.onActivity { assertFalse("B returns to the owning landscape category", it.isFinishing) }
            shot("settings-navigation")
        }
    }

    @Test fun boundedNavigationKeepsCategoriesAndStreamShortcutsReachable() = withSettings { scenario ->
        if (portrait) {
            requestFocus(compose.onNodeWithTag("nova-portrait-menu-toggle"))
            key(KeyEvent.KEYCODE_BUTTON_A)
            shot("settings-expanded-top")
            val navigation = compose.onNodeWithTag("nova-portrait-settings-navigation").fetchSemanticsNode().boundsInRoot
            scenario.onActivity { activity ->
                val cap = activity.resources.configuration.screenHeightDp * .35f * activity.resources.displayMetrics.density
                assertTrue("Expanded navigation keeps the 35% height cap", navigation.height <= cap + 1f)
            }
            val search = compose.onNodeWithContentDescription("Search Settings").fetchSemanticsNode().boundsInRoot
            val legacy = compose.onNodeWithText("Legacy").fetchSemanticsNode().boundsInRoot
            assertTrue("Search and Legacy share the actual native navigation row", legacy.center.y in search.top..search.bottom)
            for (item in categories) {
                category(item.key).performScrollTo().assertIsDisplayed()
                requestFocus(category(item.key))
                key(KeyEvent.KEYCODE_BUTTON_A)
                category(item.key).assertIsFocused().assertIsSelected()
            }
            // Down from the final category retains the established boundary into its selected pane.
            requestFocus(category(categories.last().key))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            category(categories.last().key).assertIsSelected()
            compose.onNode(isFocused() and hasTestTagPrefix("nova-settings-row-")).assertIsDisplayed()
            shot("settings-selected-pane-reached")
            // No direct focus request: Up from the first pane row discovers the first shortcut,
            // Down visits every shortcut, and the final Down returns to the selected pane.
            key(KeyEvent.KEYCODE_DPAD_UP)
            val quickKeys = listOf("nova_stream_preset", PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.BITRATE_PREF_STRING,
                "video_format", "frame_pacing")
            quickKeys.forEachIndexed { index, setting ->
                if (index > 0) key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithTag("nova-settings-quick-$setting").assertIsFocused().assertIsDisplayed()
            }
            shot("settings-controller-last-shortcut")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(isFocused() and hasTestTagPrefix("nova-settings-row-")).assertIsDisplayed()
            category(categories.last().key).assertIsSelected()
            // Shortcuts remain reachable by scrolling; activation must enter their exact setting.
            val firstQuick = compose.onNodeWithTag("nova-settings-quick-nova_stream_preset")
            firstQuick.performScrollTo().assertIsDisplayed()
            requestFocus(firstQuick)
            shot("settings-shortcuts-reached")
            key(KeyEvent.KEYCODE_BUTTON_A)
            compose.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused().assertIsDisplayed()
            scenario.onActivity { assertFalse(it.isFinishing) }
        } else {
            requestFocus(category(categories.first().key))
            categories.forEachIndexed { index, item ->
                if (index > 0) key(KeyEvent.KEYCODE_DPAD_DOWN)
                category(item.key).assertIsFocused().assertIsSelected().assertIsDisplayed()
            }
            // A short landscape window intentionally gives the pane space instead of repeating shortcuts.
            var short = false
            scenario.onActivity { short = it.resources.configuration.screenHeightDp < 560 }
            if (short) compose.onNodeWithTag("nova-settings-quick-nova_stream_preset").assertDoesNotExist()
            shot("settings-last-category")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(isFocused() and hasTestTagPrefix("nova-settings-row-")).assertIsDisplayed()
        }
    }

    @Test fun searchClearAndLegacyUseTheActualActivityCallbacks() = withSettings { scenario ->
        if (portrait) {
            requestFocus(compose.onNodeWithTag("nova-portrait-menu-toggle"))
            key(KeyEvent.KEYCODE_BUTTON_A)
        }
        val search = compose.onNodeWithContentDescription("Search Settings")
        search.assertIsDisplayed().performClick().performTextInput("bitrate")
        settle()
        compose.onNodeWithText("Clear").assertIsDisplayed()
        // B closes the open editor before the query or menu; Right then exits the field to Clear.
        key(KeyEvent.KEYCODE_BUTTON_B)
        search.assertTextContains("bitrate")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Clear").assertIsFocused()
        shot("settings-search")
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Clear").assertDoesNotExist()
        search.assertTextEquals("")
        val legacy = compose.onNodeWithText("Legacy")
        if (portrait) legacy.performScrollTo()
        requestFocus(legacy)
        key(KeyEvent.KEYCODE_BUTTON_A)
        scenario.onActivity { activity ->
            assertTrue("Legacy action changes the actual Settings root", activity.findViewById<View>(R.id.modernSettingsButton).isShown)
            assertFalse(NovaSettingsFeatureFlags.isComposeSettingsEnabled(activity))
            activity.findViewById<View>(R.id.modernSettingsButton).performClick()
        }
        settle()
        scenario.onActivity { assertTrue(NovaSettingsFeatureFlags.isComposeSettingsEnabled(it)) }
        if (portrait) compose.onNodeWithTag("nova-portrait-menu-toggle").assertIsDisplayed()
        else category(categories.first().key).assertExists()
        shot("settings-legacy-return")
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag beginning $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }

    private fun shot(name: String) {
        val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "native")
        val directory = File(context.getExternalFilesDir(null), "settings-activity").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
