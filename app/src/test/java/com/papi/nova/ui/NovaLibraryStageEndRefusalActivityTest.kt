package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.BeforeClass
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The Stage hero says a refused End under its title (XR3), and it is the library that hands the
 * Stage the refusal: the Stage tests give it the line themselves, so a library that handed it none
 * passed them all. Here the library itself, in Stage on the RP6's landscape, shows its own End's
 * refusal on the Stage hero, from the same state its strip and its hero read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class NovaLibraryStageEndRefusalActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val game = PolarisGame(id = "active", name = "Control Ultimate Edition", source = "steam", launcherSource = "steam")
    private val session = NovaLibraryActiveSessionUiState(24, "active", "Control Ultimate Edition", "Retroid Pocket", true, 0, false, false, 1920, 1080, 60f)

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, name: String): MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(name + "\$delegate").run {
            isAccessible = true
            get(activity) as MutableState<T>
        }

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theLibrarysRefusedEndIsSaidOnItsStageHero() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("nova_library_layout_mode", NovaLibraryLayoutMode.STAGE.name)
            .commit()
        // A host that answers nothing: the library is given its games and its session below.
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_SERVER_NAME, "pc-papi")
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java, intent).create()
        val activity = controller.get()
        try {
            NovaLibraryActivity::class.java.getDeclaredField("apiClient").apply {
                isAccessible = true
                set(activity, mock(PolarisApiClient::class.java))
            }
            controller.start().resume().visible()
            idle()

            val line = context.getString(R.string.nova_library_end_started_elsewhere)
            state<Boolean>(activity, "isInitialLoading").value = false
            state<String?>(activity, "loadErrorMessage").value = null
            state<List<PolarisGame>>(activity, "allGames").value = listOf(game)
            state<NovaLibraryActiveSessionUiState?>(activity, "activeSession").value = session
            NovaLibraryActivity::class.java.getDeclaredField("end").run {
                isAccessible = true
                (get(activity) as NovaLibraryEnd).status = NovaLibraryEndStatus.Failed(24, line, canRetry = false)
            }
            rule.waitForIdle()

            rule.onNodeWithTag(NOVA_STAGE_END_REFUSED_TAG, useUnmergedTree = true).assertTextEquals(line)
        } finally {
            controller.pause().stop().destroy()
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        }
    }

    @Test fun pendingEndOwnsTheStageHero() = pendingEndOwnsActions(listOf(game))
    @Test fun pendingEndOwnsTheSessionOnlyStageHero() = pendingEndOwnsActions(emptyList())
    @Test @Config(qualifiers = "w412dp-h915dp-port")
    fun pendingEndOwnsThePortraitHero() = pendingEndOwnsActions(listOf(game))

    private fun pendingEndOwnsActions(games: List<PolarisGame>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("nova_library_layout_mode", NovaLibraryLayoutMode.STAGE.name).commit()
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java, intent).create()
        val activity = controller.get()
        try {
            NovaLibraryActivity::class.java.getDeclaredField("apiClient").apply {
                isAccessible = true; set(activity, mock(PolarisApiClient::class.java))
            }
            controller.start().resume().visible()
            idle()
            state<Boolean>(activity, "isInitialLoading").value = false
            state<String?>(activity, "loadErrorMessage").value = null
            state<List<PolarisGame>>(activity, "allGames").value = games
            state<NovaLibraryActiveSessionUiState?>(activity, "activeSession").value = session
            val end = NovaLibraryActivity::class.java.getDeclaredField("end").run {
                isAccessible = true; get(activity) as NovaLibraryEnd
            }
            end.status = NovaLibraryEndStatus.Ending(session.gameId)
            rule.waitForIdle()
            rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()
            rule.onNodeWithText(context.getString(R.string.game_dialog_action_end_session)).assertDoesNotExist()
            rule.onNodeWithText(context.getString(R.string.applist_menu_resume)).assertDoesNotExist()
            // A second surface cannot submit End or Resume while the first request is out.
            for (name in listOf("endActiveSession", "resumeActiveSession")) {
                NovaLibraryActivity::class.java.getDeclaredMethod(name, NovaLibraryActiveSessionUiState::class.java)
                    .apply { isAccessible = true }.invoke(activity, session)
                assertTrue("$name must leave the pending request alone", end.status is NovaLibraryEndStatus.Ending)
            }
        } finally {
            controller.pause().stop().destroy()
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        }
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
