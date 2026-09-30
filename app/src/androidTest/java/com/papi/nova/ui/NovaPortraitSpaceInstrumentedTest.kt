package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.preferences.*
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owned-emulator fixtures, not a paired host, real artwork or streaming acceptance. */
@RunWith(AndroidJUnit4::class)
class NovaPortraitLibraryInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, name: String): MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(name + "\$delegate").run {
            isAccessible = true
            get(activity) as MutableState<T>
        }

    @Test fun regularMenuReopensOnTouchAndControllerBackWithoutDuplicatingGames() {
        // Artwork and reveal animations keep running on a large-text Library. Advance a
        // bounded frame clock for each interaction instead of waiting for them to stop.
        compose.mainClock.autoAdvance = false
        fun settle() { compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previousLayout = preferences.getString("nova_library_layout_mode", null)
        preferences.edit().putString("nova_library_layout_mode", NovaLibraryLayoutMode.GRID.name).commit()
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "Test host")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
        try {
            ActivityScenario.launch<NovaLibraryActivity>(intent).use { scenario ->
                lateinit var loading: MutableState<Boolean>
                scenario.onActivity { loading = state(it, "isInitialLoading") }
                compose.waitUntil(10_000) { !loading.value }
                scenario.onActivity { activity ->
                    for (name in listOf("libraryPoll", "spacesPoll", "activeSessionImmediateRefreshJob", "activeSessionRefreshJob")) {
                        NovaLibraryActivity::class.java.getDeclaredField(name).apply {
                            isAccessible = true
                            (get(activity) as? Job)?.cancel()
                        }
                    }
                    state<String?>(activity, "loadErrorMessage").value = null
                    state<List<PolarisGame>>(activity, "allGames").value = (0..11).map { index ->
                        PolarisGame(id = "game-$index", name = "Game ${index + 1}", source = "steam", lastLaunched = 100 - index.toLong())
                    }
                }
                settle()
                compose.onNodeWithText("Continue").assertDoesNotExist()
                compose.onNodeWithTag(NOVA_LIBRARY_HERO_TAG).assertDoesNotExist()
                compose.onNodeWithTag("nova-poster-game-0").assertIsDisplayed()
                val collapsedPoster = compose.onNodeWithTag("nova-poster-game-0").getUnclippedBoundsInRoot()
                val bar = compose.onNodeWithTag("nova-portrait-menu-bar").getUnclippedBoundsInRoot()
                assertTrue("the menu leaves most portrait height for the grid", (bar.bottom - bar.top).value < 100)
                portraitShot("regular-collapsed")

                compose.onNodeWithTag("nova-portrait-menu-toggle").performClick()
                settle()
                compose.onNodeWithText("Options").assertIsDisplayed()
                portraitShot("regular-expanded")
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                compose.onNodeWithTag("nova-portrait-menu-toggle")
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
                val time = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    instrumentation.sendKeySync(KeyEvent(time, time, action, KeyEvent.KEYCODE_BUTTON_B,
                        0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD))
                }
                settle()
                compose.onNodeWithText("Menu").assertIsDisplayed().assertIsFocused()
                compose.onNodeWithText("Options").assertDoesNotExist()
                assertEquals(collapsedPoster.top.value,
                    compose.onNodeWithTag("nova-poster-game-0").getUnclippedBoundsInRoot().top.value, 1f)

                scenario.onActivity { activity ->
                    state<NovaLibraryActiveSessionUiState?>(activity, "activeSession").value =
                        NovaLibraryActiveSessionUiState(24, "game-0", "Game 1", "Test client", true, 0, false, false, 1920, 1080, 60f)
                }
                settle()
                compose.onNodeWithText("Resume Stream").assertIsDisplayed()
                compose.onNodeWithText("Continue").assertDoesNotExist()
                portraitShot("regular-active-session")
            }
        } finally {
            preferences.edit().apply {
                if (previousLayout == null) remove("nova_library_layout_mode") else putString("nova_library_layout_mode", previousLayout)
            }.commit()
        }
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaPortraitSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsMenuPreservesItsPaneAndGivesItsHeightBackToRows() {
        val definitions = NovaSettingDefinitions.load(compose.activity)
        var selected by mutableStateOf(definitions.categories.first().key)
        var values by mutableStateOf(definitions.settings.mapNotNull { definition ->
            definition.defaultValue?.let { definition.key to it }
        }.toMap())
        lateinit var inputMode: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                inputMode = LocalInputModeManager.current
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, values, selected, ""),
                    title = "Settings", subtitle = "Test defaults", onBack = {}, onOpenLegacy = {},
                    onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                    onResetSetting = {}, onValue = { definition, value, done -> values = values + (definition.key to value); done() },
                    onSetting = {},
                )
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        val firstKey = definitions.settingsForCategory(selected).first().key
        compose.onNodeWithText("Menu").assertIsDisplayed()
        compose.onNodeWithTag("nova-settings-row-$firstKey").assertIsDisplayed()
        val collapsedTop = compose.onNodeWithTag("nova-settings-row-$firstKey").getUnclippedBoundsInRoot().top.value
        portraitShot("settings-collapsed")
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("the first setting stays in the upper 40 percent even with enlarged text",
            collapsedTop < (root.bottom - root.top).value * 0.4f)
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithText("Legacy").assertIsDisplayed()
        portraitShot("settings-expanded")
        compose.onNodeWithTag("nova-portrait-menu-toggle").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        // The fixture activity has no Nova key gate. Exercise Android Back dispatch explicitly.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Menu").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Legacy").assertDoesNotExist()
        compose.onNodeWithTag("nova-portrait-menu-toggle").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("nova-settings-row-$firstKey").assertIsFocused()
        assertEquals(collapsedTop, compose.onNodeWithTag("nova-settings-row-$firstKey").getUnclippedBoundsInRoot().top.value, 1f)
        assertEquals(definitions.categories.first().key, selected)
    }
}

private fun portraitShot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "normal")
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "portrait-space")
    check(directory.mkdirs() || directory.isDirectory)
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
