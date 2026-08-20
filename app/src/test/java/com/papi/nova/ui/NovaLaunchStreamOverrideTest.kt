package com.papi.nova.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaLaunchStreamOverrideTest {

    private fun choice(targetMode: String, id: String = "balanced") = NovaDisplayResolutionChoice(
        id = id,
        title = "Balanced",
        targetMode = targetMode,
        badge = "",
        reason = "",
        advanced = false,
        custom = false,
        safe = true,
        recommended = false,
    )

    private fun recoveryBlob(): JSONObject = JSONObject(
        "{\"display_mode\":\"1920x1080x30\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
            "\"target_bitrate_kbps\":8000," +
            "\"profile_state\":{\"label\":\"Recovery\"}," +
            "\"stability\":{\"mode\":\"stability_first\",\"safe_profile\":{\"target_fps\":30}}}"
    )

    @Test
    fun nothingChosenReturnsTheRawBlobUntouched() {
        val raw = recoveryBlob()
        assertSame(raw, NovaLaunchStreamOverride.compose(raw, null, null, 1920, 1080, 60))
        assertNull(NovaLaunchStreamOverride.compose(null, null, null, 1920, 1080, 60))
    }

    @Test
    fun resolutionPickKeepsTheStabilityBlockAndTheHostFps() {
        val raw = recoveryBlob()
        val composed = NovaLaunchStreamOverride.compose(raw, choice("1440x810x60"), null, 1920, 1080, 120)!!

        // Resolution from the pick, fps from the pick's own target mode.
        assertEquals("1440x810x60", composed.getString("display_mode"))
        assertTrue(composed.getBoolean("paired_profile_applied"))
        assertEquals("balanced", composed.getString("display_planner_choice"))
        // The honesty fix: the recovery clamp's inputs survive the pick.
        assertEquals("stability_first", composed.getJSONObject("stability").getString("mode"))
        assertEquals(30.0, composed.getDouble("safe_target_fps"), 0.0)
        assertEquals(8000, composed.getInt("target_bitrate_kbps"))
        assertFalse(composed.optBoolean("safe_target_fps_relaxed", false))
    }

    @Test
    fun fpsPinReleasesTheSafeTargetExplicitly() {
        val raw = recoveryBlob()
        val composed = NovaLaunchStreamOverride.compose(raw, null, 120, 1280, 800, 60)!!

        // Resolution from the host blob, fps from the pin.
        assertEquals("1920x1080x120", composed.getString("display_mode"))
        assertTrue(composed.getBoolean("safe_target_fps_relaxed"))
        assertEquals(120.0, composed.getDouble("effective_target_fps"), 0.0)
        // Still composed, never replaced: stability travels with the release flag.
        assertEquals("stability_first", composed.getJSONObject("stability").getString("mode"))
    }

    @Test
    fun resolutionPickAndFpsPinComposeIntoOneMode() {
        val composed = NovaLaunchStreamOverride.compose(recoveryBlob(), choice("1440x810x60"), 120, 1280, 800, 60)!!
        assertEquals("1440x810x120", composed.getString("display_mode"))
        assertTrue(composed.getBoolean("safe_target_fps_relaxed"))
    }

    @Test
    fun missingBlobFallsBackToTheSettingsMode() {
        val composed = NovaLaunchStreamOverride.compose(null, null, 90, 1280, 800, 60)!!
        assertEquals("1280x800x90", composed.getString("display_mode"))

        val pinless = NovaLaunchStreamOverride.compose(JSONObject(), choice("x-bad-mode"), null, 1280, 800, 60)!!
        assertEquals("1280x800x60", pinless.getString("display_mode"))
    }

    @Test
    fun composingNeverMutatesTheInputBlob() {
        val raw = recoveryBlob()
        val before = raw.toString()
        NovaLaunchStreamOverride.compose(raw, choice("1440x810x60"), 120, 1280, 800, 60)
        assertEquals(before, raw.toString())
    }

    @Test
    fun highFpsPinComesOnlyFromTheHighFpsPreference() {
        assertEquals(120, NovaLaunchStreamOverride.highFpsPin("high_fps", 120f))
        assertEquals(60, NovaLaunchStreamOverride.highFpsPin(" HIGH_FPS ", 59.94f))
        assertNull(NovaLaunchStreamOverride.highFpsPin("auto", 120f))
        assertNull(NovaLaunchStreamOverride.highFpsPin("quality", 120f))
        assertNull(NovaLaunchStreamOverride.highFpsPin("stability", 120f))
        assertNull(NovaLaunchStreamOverride.highFpsPin("high_fps", 0f))
    }
}
