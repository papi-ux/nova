package com.papi.nova.profiles

import android.app.Activity
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.Game
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.ServerHelper
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A network rule's profile applies to one launch: it is read in place of the active profile
 * without becoming active, so other hosts, resumes and the next launch keep the active one.
 */
@Config(
    sdk = [33],
    shadows = [
        com.papi.nova.shadows.ShadowMoonBridge::class,
        com.papi.nova.shadows.ShadowGameManager::class,
    ],
)
@RunWith(RobolectricTestRunner::class)
class ProfilesLaunchOverlayTest {
    private lateinit var context: Context
    private lateinit var manager: ProfilesManager
    private lateinit var active: SettingsProfile
    private lateinit var travel: SettingsProfile

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        File(context.filesDir, "profiles").deleteRecursively()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString("video_format", "auto")
            .putString("list_video_scale_mode", "fit")
            .commit()
        manager = ProfilesManager.getInstance()
        active = profile("Home", mapOf("video_format" to "forceh265", "list_video_scale_mode" to "fill"))
        travel = profile("Travel", mapOf("video_format" to "neverh265", "list_video_scale_mode" to "stretch"))
        manager.add(active)
        manager.add(travel)
        manager.setActive(active.getUuid())
    }

    private fun profile(name: String, options: Map<String, Any>) =
        SettingsProfile(UUID.randomUUID(), name, 1L, 1L, options)

    @Test
    fun theLaunchProfileOverlaysWithoutChangingTheActiveOne() {
        val launch = manager.getOverlayingSharedPreferences(context, travel.getUuid().toString())
        assertEquals("neverh265", launch.getString("video_format", null))
        assertEquals("stretch", launch.getString("list_video_scale_mode", null))

        assertSame(active, manager.getActive())
        assertEquals("forceh265", manager.getOverlayingSharedPreferences(context).getString("video_format", null))
    }

    @Test
    fun aMissingOrMalformedLaunchProfileFallsBackToTheActiveOne() {
        listOf(null, "", "not-a-uuid", UUID.randomUUID().toString()).forEach { uuid ->
            assertEquals(uuid.toString(), "forceh265",
                manager.getOverlayingSharedPreferences(context, uuid).getString("video_format", null))
        }
        assertNull(manager.getProfile("not-a-uuid"))
        assertSame(travel, manager.getProfile(travel.getUuid().toString()))
    }

    @Test
    fun readPreferencesTakesCodecAndScalingFromTheProfileItWasGiven() {
        val launch = PreferenceConfiguration.readPreferences(context,
            manager.getOverlayingSharedPreferences(context, travel.getUuid().toString()))
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_H264, launch.videoFormat)
        assertEquals(PreferenceConfiguration.ScaleMode.STRETCH, launch.videoScaleMode)

        val durable = PreferenceConfiguration.readPreferences(context)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, durable.videoFormat)
        assertEquals(PreferenceConfiguration.ScaleMode.FILL, durable.videoScaleMode)
        assertSame(active, manager.getActive())
    }

    /**
     * Regression for the passed-preferences fix: every value readPreferences used to take from the
     * active profile behind its caller's back now comes from the preferences it is given, as the
     * profile editor passes its own. The active profile says the opposite of each value.
     */
    @Test
    fun readPreferencesTakesEveryHelperValueFromPassedPreferencesNotTheActiveProfile() {
        manager.update(SettingsProfile(active.getUuid(), "Home", 1L, 2L, mapOf(
            "video_format" to "forceh265", "list_video_scale_mode" to "fill", "frame_pacing" to "latency",
            "pref_low_latency_frame_balance" to false, "analog_scrolling" to "right")))
        val edited = context.getSharedPreferences("edited-profile-under-test", Context.MODE_PRIVATE)
        edited.edit().clear()
            .putString("video_format", "neverh265")
            .putString("list_video_scale_mode", "stretch")
            .putString("frame_pacing", "balanced")
            .putBoolean("pref_low_latency_frame_balance", true)
            .putString("analog_scrolling", "left")
            .commit()

        val config = PreferenceConfiguration.readPreferences(context, edited)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_H264, config.videoFormat)
        assertEquals(PreferenceConfiguration.ScaleMode.STRETCH, config.videoScaleMode)
        assertEquals(PreferenceConfiguration.FRAME_PACING_BALANCED, config.framePacing)
        assertEquals(true, config.preferLowerDelays)
        assertEquals(PreferenceConfiguration.AnalogStickForScrolling.LEFT, config.analogStickForScrolling)
        assertEquals("balanced", PreferenceConfiguration.getSelectedFramePacingName(edited))

        // Without passed preferences nothing changed: the active profile is read, as before.
        val durable = PreferenceConfiguration.readPreferences(context)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC, durable.videoFormat)
        assertEquals(PreferenceConfiguration.ScaleMode.FILL, durable.videoScaleMode)
        assertEquals(PreferenceConfiguration.AnalogStickForScrolling.RIGHT, durable.analogStickForScrolling)
        assertEquals(false, durable.preferLowerDelays)
        assertEquals("latency", PreferenceConfiguration.getSelectedFramePacingName(context))
    }

    @Test
    fun writesThroughTheLaunchOverlayGoToBasePreferencesNotTheProfile() {
        manager.getOverlayingSharedPreferences(context, travel.getUuid().toString()).edit()
            .putString("mouse_mode_list", "2").commit()
        assertEquals("2", PreferenceManager.getDefaultSharedPreferences(context).getString("mouse_mode_list", null))
        assertFalse(travel.getOptions().orEmpty().containsKey("mouse_mode_list"))
    }

    @Test
    fun theStartIntentCarriesTheLaunchProfileOnlyWhenOneWasChosen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun intent(launchProfile: String?) = ServerHelper.createStartIntent(
            activity, NvApp("Game", "game-uuid", 7, false), "192.0.2.1", 47989, 47984, "unique", "pc-uuid", "PC",
            withVDisplay = false, displayModeExplicit = true, watchOnly = false,
            serverCommands = null, serverCert = null, launchProfileUuid = launchProfile,
        )
        assertEquals(travel.getUuid().toString(),
            intent(travel.getUuid().toString()).getStringExtra(Game.EXTRA_LAUNCH_PROFILE_UUID))
        assertFalse(intent(null).hasExtra(Game.EXTRA_LAUNCH_PROFILE_UUID))
        assertFalse(intent("").hasExtra(Game.EXTRA_LAUNCH_PROFILE_UUID))
        assertSame(active, manager.getActive())
    }
}
