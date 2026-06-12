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
    fun resolveProfileProvenance_marksManualOverride() {
        val optimization = JSONObject("{\"source\":\"ai_cached\",\"recommendation_version\":2}")

        val provenance = StreamSyncManager.resolveProfileProvenance(optimization, manualOverride = true)

        assertEquals(ClientProfileSource.MANUAL_OVERRIDE, provenance.source)
        assertTrue(provenance.manualOverride)
        assertEquals(2, provenance.version)
    }

    @Test
    fun resolveProfileProvenance_preservesHistorySafeRecoverySource() {
        val optimization = JSONObject(
            "{\"source\":\"history_safe\",\"confidence\":\"medium\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"}}"
        )

        val provenance = StreamSyncManager.resolveProfileProvenance(optimization, manualOverride = false)

        assertEquals(ClientProfileSource.HISTORY_SAFE, provenance.source)
        assertEquals("medium", provenance.confidence)
    }

    @Test
    fun buildClientRuntime_includesProfileProvenance() {
        val profile = ClientProfileProvenance(
            source = ClientProfileSource.POLARIS_CACHED,
            version = 4,
            confidence = "high",
            cacheStatus = "hit",
            manualOverride = false
        )

        val runtime = StreamSyncManager.buildClientRuntimeSnapshotForTest(
            deviceModel = "Retroid Pocket",
            androidSdk = 35,
            decoder = "c2.qti.hevc.decoder.low_latency",
            targetRefreshRateHz = 60.0,
            appliedRefreshRateHz = 120.0,
            displayMode = "1920x1080x120",
            refreshRatePolicy = "whole_multiple",
            profile = profile
        )

        assertEquals("Retroid Pocket", runtime.getString("device_model"))
        assertEquals("polaris_cached", runtime.getJSONObject("profile").getString("source"))
        assertEquals(4, runtime.getJSONObject("profile").getInt("version"))
    }

    @Test
    fun resolveAutoSafeResolution_keepsBalancedFloorForCached720Profile() {
        val optimization = JSONObject("{\"display_mode\":\"1280x720x60\",\"source\":\"ai_cached\"}")

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization)

        assertEquals(1920, resolution.width)
        assertEquals(1080, resolution.height)
    }

    @Test
    fun resolveAutoSafeResolution_appliesConfirmedRecoveryResolution() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1280x720x60\",\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"}}"
        )

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
    fun resolveAutoSafeResolution_honorsPairedLaunchProfileUpscale() {
        val optimization = JSONObject(
            "{\"display_mode\":\"2560x1440x120\"," +
                "\"normalization_reason\":\"Aligned launch optimization display mode to the paired client profile.\"}"
        )

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization)

        assertEquals(2560, resolution.width)
        assertEquals(1440, resolution.height)
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
    fun resolveAutoSafeBitrate_honorsPairedLaunchProfileTarget() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":80000,\"confidence\":\"medium\"," +
                "\"normalization_reason\":\"Aligned launch optimization bitrate to the paired client profile.\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(30000, optimization)

        assertEquals(80000, bitrate)
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
    fun resolveAutoSafeTargetFps_honorsPairedLaunchProfileTarget() {
        val optimization = JSONObject(
            "{\"display_mode\":\"2560x1440x120\",\"source\":\"client_profile\"," +
                "\"normalization_reason\":\"Aligned launch optimization display mode to the paired client profile.\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(60f, optimization)

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
    fun resolveAutoSafeTargetFps_highFpsTrialBypassesConfirmedRecoveryCapOnce() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":60," +
                "\"source\":\"history_safe\",\"trial_profile\":true,\"trial_kind\":\"high_fps\"," +
                "\"profile_state\":{\"preference\":\"high_fps\",\"trial_profile\":true," +
                "\"trial_kind\":\"high_fps\"}," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_fps\":60}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun requiresLaunchPreflightReview_ignoresMatchingRequestedAndEffectiveFps() {
        val optimization = JSONObject(
            "{\"requested_target_fps\":120,\"effective_target_fps\":120," +
                "\"profile_state\":{\"preference\":\"high_fps\",\"preference_applied\":true}}"
        )

        assertFalse(StreamSyncManager.requiresLaunchPreflightReview(optimization))
    }

    @Test
    fun requiresLaunchPreflightReview_ignoresUnappliedPreferenceWithoutMaterialOverride() {
        val optimization = JSONObject(
            "{\"requested_target_fps\":120,\"effective_target_fps\":120," +
                "\"preference\":\"high_fps\",\"preference_applied\":false," +
                "\"preference_blocked_reason\":\"none\"," +
                "\"profile_state\":{\"preference\":\"high_fps\",\"preference_applied\":false," +
                "\"preference_blocked_reason\":\"none\"}}"
        )

        assertFalse(StreamSyncManager.requiresLaunchPreflightReview(optimization))
        assertEquals(
            "",
            StreamSyncManager.launchPreflightReviewReason(optimization)
        )
    }

    @Test
    fun requiresLaunchPreflightReview_ignoresHighFpsBlockReasonWhenTargetMatches() {
        val optimization = JSONObject(
            "{\"requested_target_fps\":120,\"effective_target_fps\":120," +
                "\"preference\":\"high_fps\",\"preference_applied\":false," +
                "\"preference_blocked_reason\":\"host_render_limited\"," +
                "\"profile_state\":{\"preference\":\"high_fps\",\"preference_applied\":false," +
                "\"preference_blocked_reason\":\"host_render_limited\"}}"
        )

        assertFalse(StreamSyncManager.requiresLaunchPreflightReview(optimization))
        assertEquals(
            "",
            StreamSyncManager.launchPreflightReviewReason(optimization)
        )
    }

    @Test
    fun requiresLaunchPreflightReview_flagsMaterialFpsOverride() {
        val optimization = JSONObject(
            "{\"requested_target_fps\":120,\"effective_target_fps\":40," +
                "\"preference_blocked_reason\":\"optimizer_selected_lower_fps\"," +
                "\"profile_state\":{\"preference\":\"high_fps\",\"preference_applied\":false}}"
        )

        assertTrue(StreamSyncManager.requiresLaunchPreflightReview(optimization))
        assertEquals(
            "optimizer_selected_lower_fps",
            StreamSyncManager.launchPreflightReviewReason(optimization)
        )
    }

    @Test
    fun requiresLaunchPreflightReview_flagsExplicitlyBlockedNonAutoPreference() {
        val optimization = JSONObject(
            "{\"requested_target_fps\":120,\"effective_target_fps\":120," +
                "\"profile_state\":{\"preference\":\"quality\",\"preference_applied\":false," +
                "\"preference_blocked_reason\":\"recent_degraded_session\"}}"
        )

        assertTrue(StreamSyncManager.requiresLaunchPreflightReview(optimization))
        assertEquals(
            "recent_degraded_session",
            StreamSyncManager.launchPreflightReviewReason(optimization)
        )
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
