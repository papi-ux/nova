package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class StreamSyncManagerTest {

    @Test
    fun resolveAutoSafeResolution_appliesLowerOptimizerResolution() {
        val optimization = JSONObject("{\"display_mode\":\"1280x720x60\"}")

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization)

        assertEquals(1280, resolution.width)
        assertEquals(720, resolution.height)
    }

    @Test
    fun resolveAutoSafeResolution_doesNotUpscaleConfiguredResolution() {
        val optimization = JSONObject("{\"display_mode\":\"1920x1080x60\"}")

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1280, 720, optimization)

        assertEquals(1280, resolution.width)
        assertEquals(720, resolution.height)
    }

    @Test
    fun resolveAutoSafeResolution_ignoresInvalidDisplayMode() {
        val optimization = JSONObject("{\"display_mode\":\"headless\"}")

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization)

        assertEquals(1920, resolution.width)
        assertEquals(1080, resolution.height)
    }

    @Test
    fun resolveAutoSafeBitrate_honorsHighConfidenceOptimizerTarget() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                "\"safe_profile\":{\"target_bitrate_kbps\":16000}}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(16000, optimization)

        assertEquals(50000, bitrate)
    }

    @Test
    fun resolveAutoSafeBitrate_keepsConfiguredForNonHighConfidenceTarget() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":50000,\"confidence\":\"medium\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(16000, optimization)

        assertEquals(16000, bitrate)
    }

    @Test
    fun resolveAutoSafeBitrate_clampsOnlyForConfirmedRecovery() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\",\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_bitrate_kbps\":12000}}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(28000, optimization)

        assertEquals(12000, bitrate)
    }

    @Test
    fun resolveAutoSafeTargetFps_ignoresSafeProfileWithoutRecovery() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_ignoresHostRenderRecoveryProfileWithoutRecoveryAction() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30," +
                "\"recovery_profile\":\"host_render_limited\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_ignoresPlainClientProfileDisplayModeCap() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1280x720x30\",\"source\":\"client_profile\"," +
                "\"confidence\":\"medium\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_appliesAiDisplayModeCap() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1280x720x60\",\"source\":\"ai_cached\"," +
                "\"confidence\":\"medium\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(60f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_appliesConfirmedRecoveryCap() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(30f, targetFps, 0.01f)
    }

    @Test
    fun resolveDisplayCompatibleAutoSafeTargetFps_keepsFortyWhenOneTwentyAllowed() {
        val selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
            40f,
            120f,
            floatArrayOf(60f, 120f)
        )

        assertEquals(40f, selected, 0.01f)
    }

    @Test
    fun resolveDisplayCompatibleAutoSafeTargetFps_fallsBackToThirtyWhenFortyIsCappedAtSixty() {
        val selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
            40f,
            60f,
            floatArrayOf(60f, 120f)
        )

        assertEquals(30f, selected, 0.01f)
    }

    @Test
    fun resolveDisplayCompatibleAutoSafeTargetFps_treatsMissingCapAsUnrestricted() {
        val selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
            40f,
            0f,
            floatArrayOf(60f, 120f)
        )

        assertEquals(40f, selected, 0.01f)
    }

    @Test
    fun stabilityDecoder_ignoresBalancedSafeProfile() {
        val optimization = JSONObject(
            "{\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        assertFalse(StreamSyncManager.shouldPreferStabilityDecoder(optimization))
    }

    @Test
    fun stabilityDecoder_appliesConfirmedRecoveryProfile() {
        val optimization = JSONObject(
            "{\"source\":\"history_safe\",\"stability\":{\"mode\":\"stability_first\"," +
                "\"auto_action\":\"apply_recovery\",\"safe_profile\":{\"target_fps\":30}}}"
        )

        assertTrue(StreamSyncManager.shouldPreferStabilityDecoder(optimization))
    }
}
