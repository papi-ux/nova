package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.TestLogSuppressor
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Library rendering, including the visibility conditions that the old rail tests missed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class NovaPortraitLibraryActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val game = PolarisGame(id = "recent", name = "A recent game", source = "steam", lastLaunched = 100)

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, name: String): MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(name + "\$delegate").run {
            isAccessible = true
            get(activity) as MutableState<T>
        }

    private fun library(block: (NovaLibraryActivity) -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("nova_library_layout_mode", NovaLibraryLayoutMode.GRID.name).commit()
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java, intent).create()
        val activity = controller.get()
        try {
            NovaLibraryActivity::class.java.getDeclaredField("apiClient").apply {
                isAccessible = true
                set(activity, mock(PolarisApiClient::class.java))
            }
            controller.start().resume().visible()
            repeat(3) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
            state<Boolean>(activity, "isInitialLoading").value = false
            state<String?>(activity, "loadErrorMessage").value = null
            state<List<PolarisGame>>(activity, "allGames").value = listOf(game)
            rule.waitForIdle()
            block(activity)
        } finally {
            controller.pause().stop().destroy()
            preferences.edit().clear().commit()
        }
    }

    @Test fun regularDoesNotRepeatRecentGamesAboveItsGrid() = library {
        rule.onNodeWithText("Continue").assertDoesNotExist()
        rule.onNodeWithTag(NOVA_LIBRARY_HERO_TAG).assertDoesNotExist()
        rule.onNodeWithTag("nova-poster-recent").assertIsDisplayed()
    }

    @Test fun portraitMenuCanHideAndReopenWithoutRestoringTheOldContinueRow() = library {
        rule.onNodeWithText("Menu").assertIsDisplayed().performClick()
        rule.onNodeWithText("Options").assertIsDisplayed()
        rule.onNodeWithText("Hide menu").performClick()
        rule.onNodeWithText("Options").assertDoesNotExist()
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("System").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertDoesNotExist()
    }

    @Test fun anActiveSessionStaysReachableWhenThePortraitMenuIsHidden() = library { activity ->
        state<NovaLibraryActiveSessionUiState?>(activity, "activeSession").value =
            NovaLibraryActiveSessionUiState(24, "recent", "A recent game", "Test device", true, 0, false, false, 1920, 1080, 60f)
        rule.waitForIdle()
        rule.onNodeWithText("Menu").assertIsDisplayed()
        rule.onNodeWithTag(NOVA_LIBRARY_HERO_TAG).assertIsDisplayed()
        rule.onNodeWithText("Resume Stream").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertDoesNotExist()
    }

    companion object {
        @JvmStatic @BeforeClass fun suppressLogs() { TestLogSuppressor.install() }
    }
}
