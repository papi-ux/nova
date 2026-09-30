package com.papi.nova.profiles

import android.content.Context
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ProfilesActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.panel.NovaPanelMetrics
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delete profile is a split confirm in its row (R3): a tap arms it into Keep and Delete with
 * what is lost said under them, the pair takes the row, the destructive half ignores a press for
 * 400ms, and only a deliberate second press deletes.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesDeleteSplitComposeTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun frames(count: Int = 4) = repeat(count) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test
    fun deleteArmsIntoKeepAndDeleteAndOnlyADeliberateSecondPressDeletes() {
        ProfilesManager.instance = null
        val profiles = ProfilesManager.getInstance().apply { load(context) }
        val now = System.currentTimeMillis()
        profiles.add(SettingsProfile(UUID.randomUUID(), "Couch", now, now, null))

        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            val delete = context.getString(R.string.profile_manager_delete)
            val keep = context.getString(R.string.nova_panel_keep)

            rule.onNodeWithText(delete).performClick()
            frames(16)
            rule.onNodeWithText(keep).assertExists()
            rule.onNodeWithText(context.getString(R.string.nova_profiles_delete_consequence, "Couch")).assertExists()
            scenario.onActivity { activity ->
                assertEquals(
                    "armed, the pair takes the row",
                    View.GONE,
                    activity.findViewById<View>(R.id.profileDetails).visibility,
                )
            }

            rule.onNodeWithText(delete).performClick()
            frames()
            assertEquals("inside the guard, Delete does nothing", 1, profiles.getProfiles().size)

            rule.mainClock.advanceTimeBy(NovaPanelMetrics.SplitGuardMillis)
            rule.waitForIdle()
            rule.onNodeWithText(delete).performClick()
            frames()
            assertTrue("a deliberate second press deletes", profiles.getProfiles().isEmpty())
            // The row leaving is the answer: "Deleted preset" floated over the list (audit X2).
            assertNull("no Toast floats", org.robolectric.shadows.ShadowToast.getLatestToast())
        }
    }

    @Test
    fun keepDisarmsAndTheRowComesBack() {
        ProfilesManager.instance = null
        val profiles = ProfilesManager.getInstance().apply { load(context) }
        val now = System.currentTimeMillis()
        profiles.add(SettingsProfile(UUID.randomUUID(), "Desk", now, now, null))

        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithText(context.getString(R.string.profile_manager_delete)).performClick()
            frames(16)
            rule.onNodeWithText(context.getString(R.string.nova_panel_keep)).performClick()
            frames(16)

            assertEquals(1, profiles.getProfiles().size)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.profileDetails).visibility)
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
