package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the production Compact screen in portrait and landscape native smoke configurations. */
@RunWith(AndroidJUnit4::class)
class NovaLibraryCompactInstrumentationTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, name: String): MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(name + "\$delegate").run {
            isAccessible = true
            get(activity) as MutableState<T>
        }

    @Test fun compactHasOnePosterDirectActionsAndControllerBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getString("nova_library_layout_mode", null)
        prefs.edit().putString("nova_library_layout_mode", NovaLibraryLayoutMode.COMPACT.name).commit()
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "Library smoke")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
        try {
            ActivityScenario.launch<NovaLibraryActivity>(intent).use { scenario ->
                rule.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { ready = !state<Boolean>(it, "isInitialLoading").value }
                    ready
                }
                scenario.onActivity { activity ->
                    state<String?>(activity, "loadErrorMessage").value = null
                    state<List<PolarisGame>>(activity, "allGames").value = listOf(
                        PolarisGame(id = "recent", name = "A recent game", source = "steam", lastLaunched = 100))
                    // This used to bring the redundant landscape Continue rail back.
                    state<String>(activity, "searchQuery").value = "recent"
                }
                rule.waitForIdle()
                rule.onNodeWithTag("nova-portrait-menu-toggle").assertDoesNotExist()
                rule.onNodeWithText("Continue").assertDoesNotExist()
                rule.onNodeWithTag(NOVA_LIBRARY_HERO_TAG).assertDoesNotExist()
                rule.onAllNodesWithTag("nova-poster-recent").assertCountEquals(1)
                rule.onNodeWithTag("nova-poster-recent").assertIsDisplayed()
                val options = context.getString(R.string.nova_library_options_title)
                rule.onNodeWithContentDescription(options).assertIsDisplayed().performClick()
                rule.onNodeWithText(options).assertIsDisplayed()
                // Send the key through Android's active window: the Options dialog
                // owns input here, so direct Activity dispatch would bypass its gate.
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_B)
                rule.waitForIdle()
                rule.onNodeWithText(options).assertDoesNotExist()
                rule.onNodeWithContentDescription(options).assertIsDisplayed()
                rule.onNodeWithText(context.getString(R.string.nova_system_menu_title)).assertIsDisplayed()
                rule.onNodeWithTag("nova-poster-recent").assertIsDisplayed()
                val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    "landscape" else "portrait"
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "library-compact-$orientation.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            prefs.edit().apply {
                if (previous == null) remove("nova_library_layout_mode") else putString("nova_library_layout_mode", previous)
            }.commit()
        }
    }
}
