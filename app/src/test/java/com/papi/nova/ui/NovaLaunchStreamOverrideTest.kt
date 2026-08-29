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

    private fun deterministicBlob(): JSONObject = JSONObject(
        "{\"source\":\"deterministic_preset_v1\",\"resolved_profile\":{" +
            "\"policy_version\":1,\"preset\":\"quality\",\"fields\":{" +
            "\"display_mode\":{\"value\":\"1920x1080x60\",\"source\":\"paired_client\"," +
            "\"reason_code\":\"paired_display_setting\",\"locked\":false,\"normalized\":false}," +
            "\"display_width\":{\"value\":1920,\"source\":\"paired_client\"," +
            "\"reason_code\":\"paired_display_setting\",\"locked\":false,\"normalized\":false}," +
            "\"display_height\":{\"value\":1080,\"source\":\"paired_client\"," +
            "\"reason_code\":\"paired_display_setting\",\"locked\":false,\"normalized\":false}," +
            "\"target_fps\":{\"value\":60,\"source\":\"paired_client\"," +
            "\"reason_code\":\"paired_display_setting\",\"locked\":false,\"normalized\":false}," +
            "\"target_bitrate_kbps\":{\"value\":40000,\"source\":\"paired_client\"," +
            "\"reason_code\":\"paired_bitrate_setting\",\"locked\":false,\"normalized\":false}," +
            "\"hdr\":{\"value\":false,\"source\":\"explicit_launch_request\"," +
            "\"reason_code\":\"requested_hdr_setting\",\"locked\":false,\"normalized\":false}}}}"
    )

    private fun legacyRecoveryBlob(): JSONObject = JSONObject(
        "{\"display_mode\":\"1920x1080x30\",\"safe_target_fps\":30," +
            "\"source\":\"history_safe\",\"stability\":{\"mode\":\"stability_first\"}}"
    )

    @Test
    fun nothingChosenReturnsTheRawBlobUntouched() {
        val raw = deterministicBlob()
        assertSame(raw, NovaLaunchStreamOverride.compose(raw, null, null, 1920, 1080, 60))
        assertNull(NovaLaunchStreamOverride.compose(null, null, null, 1920, 1080, 60))
    }

    @Test
    fun resolutionPickPreservesOnlyTypedDeterministicFields() {
        val composed = NovaLaunchStreamOverride.compose(
            deterministicBlob(), choice("1440x810x60"), null, 1920, 1080, 120
        )!!
        val fields = composed.getJSONObject("resolved_profile").getJSONObject("fields")
        val display = fields.getJSONObject("display_mode")

        assertEquals("1440x810x60", composed.getString("display_mode"))
        assertEquals("1440x810x60", display.getString("value"))
        assertEquals("explicit_launch_request", display.getString("source"))
        assertEquals(NovaLaunchStreamOverride.NORMALIZATION_REASON, display.getString("reason_code"))
        assertTrue(display.getBoolean("locked"))
        assertEquals(1440, fields.getJSONObject("display_width").getInt("value"))
        assertEquals(810, fields.getJSONObject("display_height").getInt("value"))
        assertEquals(60.0, fields.getJSONObject("target_fps").getDouble("value"), 0.001)
        assertTrue(fields.getJSONObject("display_width").getBoolean("locked"))
        assertTrue(fields.getJSONObject("display_height").getBoolean("locked"))
        assertTrue(fields.getJSONObject("target_fps").getBoolean("locked"))
        assertEquals("balanced", composed.getString("display_planner_choice"))
        assertEquals(40000, fields.getJSONObject("target_bitrate_kbps").getInt("value"))
        assertFalse(composed.has("paired_profile_applied"))
        assertFalse(composed.has("safe_target_fps_relaxed"))
    }

    @Test
    fun fpsPinIsAnExplicitDisplayFieldRatherThanARecoveryRelease() {
        val composed = NovaLaunchStreamOverride.compose(
            deterministicBlob(), null, 120, 1280, 800, 60
        )!!

        assertEquals("1920x1080x120", composed.getString("display_mode"))
        assertEquals(
            "1920x1080x120",
            composed.getJSONObject("resolved_profile").getJSONObject("fields")
                .getJSONObject("display_mode").getString("value")
        )
        val fields = composed.getJSONObject("resolved_profile").getJSONObject("fields")
        assertEquals("paired_client", fields.getJSONObject("display_width").getString("source"))
        assertEquals("paired_client", fields.getJSONObject("display_height").getString("source"))
        assertFalse(fields.getJSONObject("display_width").getBoolean("locked"))
        assertFalse(fields.getJSONObject("display_height").getBoolean("locked"))
        assertEquals("explicit_launch_request", fields.getJSONObject("target_fps").getString("source"))
        assertTrue(fields.getJSONObject("target_fps").getBoolean("locked"))
        assertFalse(composed.has("safe_target_fps_relaxed"))
        assertFalse(composed.has("effective_target_fps"))
    }

    @Test
    fun resolutionPickAndFpsPinComposeIntoOneMode() {
        val composed = NovaLaunchStreamOverride.compose(
            deterministicBlob(), choice("1440x810x60"), 120, 1280, 800, 60
        )!!
        assertEquals("1440x810x120", composed.getString("display_mode"))
    }

    @Test
    fun legacyRecoveryBlobCannotBeRelabelledAsATrustedExplicitContract() {
        val composed = NovaLaunchStreamOverride.compose(
            legacyRecoveryBlob(), choice("1440x810x60"), null, 1280, 800, 60
        )!!

        assertEquals("nova_explicit_launch_unverified_v1", composed.getString("source"))
        assertFalse(composed.has("safe_target_fps"))
        assertFalse(composed.has("stability"))
        assertEquals(1, composed.getJSONObject("resolved_profile").getInt("policy_version"))
    }

    @Test
    fun missingBlobFallsBackToTheSettingsMode() {
        val composed = NovaLaunchStreamOverride.compose(null, null, 90, 1280, 800, 60)!!
        assertEquals("1280x800x90", composed.getString("display_mode"))
        assertEquals("nova_explicit_launch_unverified_v1", composed.getString("source"))

        val pinless = NovaLaunchStreamOverride.compose(
            JSONObject(), choice("x-bad-mode"), null, 1280, 800, 60
        )!!
        assertEquals("1280x800x60", pinless.getString("display_mode"))
    }

    @Test
    fun composingNeverMutatesTheInputBlob() {
        val raw = deterministicBlob()
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
