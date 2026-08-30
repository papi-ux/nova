package com.papi.nova.manager

import com.papi.nova.nvstream.jni.MoonBridge
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

    private fun deterministicOptimization(
        preset: String = "auto",
        displayMode: String? = "1920x1080x60",
        bitrateKbps: Int? = 30_000,
        fieldSource: String = "explicit_launch_request"
    ): JSONObject {
        val fields = JSONObject()
        displayMode?.let {
            val mode = it.split("x")
            fields.put(
                "display_mode",
                JSONObject()
                    .put("value", it)
                    .put("source", fieldSource)
                    .put("reason_code", "test_explicit_setting")
                    .put("locked", true)
                    .put("normalized", false)
            )
            if (mode.size == 3) {
                fields.put(
                    "display_width",
                    JSONObject()
                        .put("value", mode[0].toIntOrNull() ?: 0)
                        .put("source", fieldSource)
                        .put("reason_code", "test_explicit_setting")
                        .put("locked", true)
                        .put("normalized", false)
                )
                fields.put(
                    "display_height",
                    JSONObject()
                        .put("value", mode[1].toIntOrNull() ?: 0)
                        .put("source", fieldSource)
                        .put("reason_code", "test_explicit_setting")
                        .put("locked", true)
                        .put("normalized", false)
                )
                fields.put(
                    "target_fps",
                    JSONObject()
                        .put("value", mode[2].toDoubleOrNull() ?: 0.0)
                        .put("source", fieldSource)
                        .put("reason_code", "test_explicit_setting")
                        .put("locked", true)
                        .put("normalized", false)
                )
            }
        }
        bitrateKbps?.let {
            fields.put(
                "target_bitrate_kbps",
                JSONObject()
                    .put("value", it)
                    .put("source", fieldSource)
                    .put("reason_code", "test_explicit_setting")
                    .put("locked", true)
                    .put("normalized", false)
                )
        }
        fields.put(
            "hdr",
            JSONObject()
                .put("value", false)
                .put("source", fieldSource)
                .put("reason_code", "test_hdr_setting")
                .put("locked", true)
                .put("normalized", false)
        )
        return JSONObject()
            .put("source", "deterministic_preset_v1")
            .put(
                "resolved_profile",
                JSONObject()
                    .put("policy_version", 1)
                    .put("preset", preset)
                    .put("fields", fields)
            )
    }

    @Test
    fun resolvedFieldLockComesOnlyFromValidatedDeterministicProvenance() {
        val optimization = deterministicOptimization(bitrateKbps = 10_000)

        assertEquals(
            true,
            StreamSyncManager.resolvedFieldIsLocked(optimization, "target_bitrate_kbps")
        )

        optimization.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("target_bitrate_kbps")
            .put("locked", false)
        assertEquals(
            false,
            StreamSyncManager.resolvedFieldIsLocked(optimization, "target_bitrate_kbps")
        )

        optimization.put("source", "history_safe")
        assertEquals(
            null,
            StreamSyncManager.resolvedFieldIsLocked(optimization, "target_bitrate_kbps")
        )
    }

    @Test
    fun resolvedFieldNormalizationComesOnlyFromValidatedDeterministicProvenance() {
        val optimization = deterministicOptimization()
        val hdr = optimization.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("hdr")
        hdr.put("normalized", true)

        assertEquals(true, StreamSyncManager.resolvedFieldIsNormalized(optimization, "hdr"))
        hdr.put("normalized", "true")
        assertEquals(null, StreamSyncManager.resolvedFieldIsNormalized(optimization, "hdr"))
    }

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
    fun resolveAutoSafeResolution_ignoresConfirmedRecoveryResolution() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1280x720x60\",\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"}}"
        )

        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization)

        assertEquals(1920, resolution.width)
        assertEquals(1080, resolution.height)
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
        val optimization = deterministicOptimization(
            displayMode = "2560x1440x120",
            fieldSource = "paired_client"
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
    fun resolveAutoSafeBitrate_ignoresHighConfidenceOptimizerTarget() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                "\"safe_profile\":{\"target_bitrate_kbps\":16000}}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(16000, optimization)

        assertEquals(16000, bitrate)
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
    fun resolveAutoSafeBitrate_consumesTrustedResolvedValueExactly() {
        val optimization = deterministicOptimization(
            bitrateKbps = 80_000,
            fieldSource = "paired_client"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(30000, optimization)

        assertEquals(80_000, bitrate)
    }

    @Test
    fun resolveAutoSafeBitrate_doesNotRewriteTrustedEnvelope() {
        val optimization = deterministicOptimization(
            bitrateKbps = 40_000,
            fieldSource = "paired_client"
        )

        assertEquals(40_000, StreamSyncManager.resolveAutoSafeBitrateKbps(10_000, optimization))
    }

    @Test
    fun resolvedFieldsWithoutCompleteProvenanceAreIgnored() {
        val missingNormalized = deterministicOptimization(bitrateKbps = 12_000)
        missingNormalized.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("target_bitrate_kbps")
            .remove("normalized")
        val stringLocked = deterministicOptimization(displayMode = "1280x720x60")
        stringLocked.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("display_width")
            .put("locked", "true")

        assertEquals(30_000, StreamSyncManager.resolveAutoSafeBitrateKbps(30_000, missingNormalized))
        val resolution = StreamSyncManager.resolveAutoSafeResolution(1920, 1080, stringLocked)
        assertEquals(1920, resolution.width)
        assertEquals(1080, resolution.height)
    }

    @Test
    fun trustedLaunchEnvelopeRequiresEveryConsumedFieldAndItsProvenance() {
        val complete = deterministicOptimization(
            displayMode = "1920x1080x120",
            bitrateKbps = 30_000
        )
        val missingBitrateReason = JSONObject(complete.toString())
        missingBitrateReason.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("target_bitrate_kbps")
            .remove("reason_code")
        val missingHdr = JSONObject(complete.toString())
        missingHdr.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .remove("hdr")

        assertTrue(StreamSyncManager.hasTrustedResolvedProfile(complete))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(missingBitrateReason))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(missingHdr))
    }

    @Test
    fun trustedLaunchEnvelopeRequiresHostAuthorityAndBoundedExactValues() {
        val clientAuthored = deterministicOptimization(
            displayMode = "1920x1080x120",
            bitrateKbps = 30_000
        ).put("source", "nova_explicit_launch_v1")
        val malformedMode = deterministicOptimization(
            displayMode = "1920x1080x120xignored",
            bitrateKbps = 30_000
        )
        val oversizedMode = deterministicOptimization(
            displayMode = "20000x1080x120",
            bitrateKbps = 30_000
        )
        val fractionalBitrate = deterministicOptimization(
            displayMode = "1920x1080x120",
            bitrateKbps = 30_000
        )
        fractionalBitrate.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("target_bitrate_kbps")
            .put("value", 30_000.5)

        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(clientAuthored))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(malformedMode))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(oversizedMode))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(fractionalBitrate))
    }

    @Test
    fun trustedLaunchEnvelopeRejectsCoercedAndNonPolicyProvenance() {
        val numericSource = deterministicOptimization()
        numericSource.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("target_fps")
            .put("source", 7)
        val historicalSource = deterministicOptimization()
        historicalSource.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("display_width")
            .put("source", "history_safe")
        val stringPolicy = deterministicOptimization()
        stringPolicy.getJSONObject("resolved_profile").put("policy_version", "1")
        val mismatchedComponent = deterministicOptimization()
        mismatchedComponent.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("display_width")
            .put("value", 1280)

        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(numericSource))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(historicalSource))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(stringPolicy))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(mismatchedComponent))
    }

    @Test
    fun resolvedHdrComesOnlyFromATrustedTypedField() {
        val enabled = deterministicOptimization()
        enabled.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("hdr")
            .put("value", true)
        val malformed = JSONObject(enabled.toString())
        malformed.getJSONObject("resolved_profile")
            .getJSONObject("fields")
            .getJSONObject("hdr")
            .put("value", "true")

        assertEquals(true, StreamSyncManager.resolvedHdrValue(enabled))
        assertEquals(null, StreamSyncManager.resolvedHdrValue(malformed))
        assertFalse(StreamSyncManager.hasTrustedResolvedProfile(malformed))
    }

    @Test
    fun resolveAutoSafeBitrate_ignoresConfirmedRecoveryClamp() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\",\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_bitrate_kbps\":12000}}}"
        )

        val bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(28000, optimization)

        assertEquals(28000, bitrate)
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
        val optimization = deterministicOptimization(
            displayMode = "2560x1440x120",
            fieldSource = "paired_client"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(60f, optimization)

        assertEquals(120f, targetFps, 0.01f)
        assertEquals(120f, StreamSyncManager.resolvedTargetFpsValue(optimization) ?: 0f, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_ignoresAiDisplayModeCap() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1280x720x60\",\"source\":\"ai_cached\"," +
                "\"confidence\":\"medium\"," +
                "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_ignoresConfirmedRecoveryCap() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
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
    fun resolveAutoSafeTargetFps_legacyPairedOverrideCannotReenableRecoveryClamp() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1440x810x60\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
                "\"paired_profile_applied\":true," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization)

        assertEquals(120f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeTargetFps_legacyRelaxedFlagCannotSupplyLaunchFps() {
        val optimization = JSONObject(
            "{\"display_mode\":\"1440x810x120\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
                "\"paired_profile_applied\":true,\"safe_target_fps_relaxed\":true," +
                "\"effective_target_fps\":120," +
                "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                "\"safe_profile\":{\"target_fps\":30}}}"
        )

        val targetFps = StreamSyncManager.resolveAutoSafeTargetFps(60f, optimization)

        assertEquals(60f, targetFps, 0.01f)
    }

    @Test
    fun resolveAutoSafeBitrateKbps_legacyPairedOverrideCannotReenableRecoveryClamp() {
        val optimization = JSONObject(
            "{\"target_bitrate_kbps\":40000,\"source\":\"history_safe\"," +
                "\"paired_profile_applied\":true," +
                "\"stability\":{\"mode\":\"stability_first\"," +
                "\"safe_profile\":{\"target_bitrate_kbps\":8000}}}"
        )

        assertEquals(20000, StreamSyncManager.resolveAutoSafeBitrateKbps(20000, optimization))
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
    fun stabilityDecoder_ignoresConfirmedRecoveryProfile() {
        val optimization = JSONObject(
            "{\"source\":\"history_safe\",\"stability\":{\"mode\":\"stability_first\"," +
                "\"auto_action\":\"apply_recovery\",\"safe_profile\":{\"target_fps\":30}}}"
        )

        assertFalse(StreamSyncManager.shouldPreferStabilityDecoder(optimization))
    }

    @Test
    fun exactQueuedRecoveryProfileCannotControlAnyLaunchPreflightField() {
        val optimization = JSONObject()
            .put("recovery_state", "queued")
            .put("recovery_run_id", "run-a")
            .put("display_mode", "1280x720x120")
            .put("target_bitrate_kbps", 80_000)
            .put(
                "recovery_profile",
                JSONObject()
                    .put("stream_display_mode", "host_virtual_display")
                    .put("width", 1920)
                    .put("height", 1080)
                    .put("target_fps", 60)
                    .put("target_bitrate_kbps", 16_000)
                    .put("preferred_codec", "hevc")
                    .put("hdr", true)
                    .put("preserve_paired_resolution", true)
                    .put("requires_fresh_launch", true)
            )

        val recovery = StreamSyncManager.recoveryLaunchProfile(optimization)
        assertEquals(null, recovery)
        assertEquals(50_000, StreamSyncManager.resolveAutoSafeBitrateKbps(50_000, optimization))
        assertEquals(120f, StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization), 0.01f)
        val resolution = StreamSyncManager.resolveAutoSafeResolution(2560, 1440, optimization)
        assertEquals(2560, resolution.width)
        assertEquals(1440, resolution.height)
        assertFalse(StreamSyncManager.shouldForceFreshLaunch(optimization))
        assertEquals(
            MoonBridge.VIDEO_FORMAT_H264 or MoonBridge.VIDEO_FORMAT_H265 or MoonBridge.VIDEO_FORMAT_H265_MAIN10,
            StreamSyncManager.restrictVideoFormatsForRecovery(
                MoonBridge.VIDEO_FORMAT_H264 or MoonBridge.VIDEO_FORMAT_H265 or MoonBridge.VIDEO_FORMAT_H265_MAIN10,
                recovery
            )
        )
    }

    @Test
    fun allLegacyRecoveryProfilesAreNonApplicable() {
        fun optimization(state: String = "queued", runId: String = "run-a") = JSONObject()
            .put("recovery_state", state)
            .put("recovery_run_id", runId)
            .put(
                "recovery_profile",
                JSONObject()
                    .put("stream_display_mode", "host_virtual_display")
                    .put("width", 1920)
                    .put("height", 1080)
                    .put("target_fps", 60)
                    .put("target_bitrate_kbps", 16_000)
                    .put("preferred_codec", "hevc")
                    .put("hdr", false)
                    .put("preserve_paired_resolution", true)
                    .put("requires_fresh_launch", true)
            )

        assertEquals(null, StreamSyncManager.recoveryLaunchProfile(optimization()))
        assertEquals(null, StreamSyncManager.recoveryLaunchProfile(optimization(state = "applied")))
        assertEquals(null, StreamSyncManager.recoveryLaunchProfile(optimization(runId = "")))
        assertEquals(
            null,
            StreamSyncManager.recoveryLaunchProfile(
                optimization().apply {
                    getJSONObject("recovery_profile").put("preferred_codec", "vp9")
                }
            )
        )
        assertEquals(
            null,
            StreamSyncManager.recoveryLaunchProfile(
                optimization().apply {
                    getJSONObject("recovery_profile").put("requires_fresh_launch", false)
                }
            )
        )
        assertEquals(
            null,
            StreamSyncManager.recoveryLaunchProfile(
                optimization().apply {
                    getJSONObject("recovery_profile").remove("preserve_paired_resolution")
                }
            )
        )
        assertEquals(
            null,
            StreamSyncManager.recoveryLaunchProfile(
                optimization().apply {
                    getJSONObject("recovery_profile").put("target_bitrate_kbps", 999)
                }
            )
        )
        assertEquals(
            MoonBridge.VIDEO_FORMAT_H264,
            StreamSyncManager.restrictVideoFormatsForRecovery(
                MoonBridge.VIDEO_FORMAT_H264,
                StreamSyncManager.recoveryLaunchProfile(optimization())
            )
        )
        assertEquals(
            null,
            StreamSyncManager.recoveryLaunchProfile(
                optimization().apply {
                    getJSONObject("recovery_profile").put("stream_display_mode", "unknown")
                }
            )
        )
    }
}
