package com.papi.nova.api

import com.papi.nova.shared.polaris.model.PolarisGame
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
class PolarisApiClientParsingTest {

    @Test
    fun parseUnlockResponse_requiresSuccessFlag() {
        assertFalse(PolarisApiClient.parseUnlockResponse(JSONObject("{\"success\":false,\"was_locked\":true}")))
        assertTrue(PolarisApiClient.parseUnlockResponse(JSONObject("{\"success\":true,\"was_locked\":true}")))
        assertFalse(PolarisApiClient.parseUnlockResponse(JSONObject("{\"was_locked\":true}")))
    }

    @Test
    fun parseCapabilitiesResponse_includesCursorVisibilityControl() {
        val json = JSONObject(
            "{\"server\":\"polaris\",\"version\":\"1.0.0\"," +
                "\"features\":{\"ai_optimizer\":true,\"ai_optimizer_control\":true,\"cursor_visibility_control\":true," +
                "\"stream_policy_v1\":true,\"client_settings_v1\":true,\"optimizer_sync_v1\":true}," +
                "\"capture\":{\"backend\":\"wayland\",\"codecs\":[\"hevc\"]}}"
        )

        val capabilities = PolarisApiClient.parseCapabilitiesResponse(json)

        assertEquals("polaris", capabilities.server)
        assertTrue(capabilities.features.aiOptimizer)
        assertTrue(capabilities.features.aiAutoQuality)
        assertTrue(capabilities.features.aiAutoQualityControl)
        assertTrue(capabilities.features.cursorVisibilityControl)
        assertTrue(capabilities.features.streamPolicy)
        assertTrue(capabilities.features.clientSettings)
        assertTrue(capabilities.features.optimizerSync)
    }

    @Test
    fun parseSessionStatusResponse_includesHdrDowngradeTruth() {
        val health = JSONObject()
            .put("primary_issue", "hdr_downgraded")
            .put("safe_hdr", false)
            .put("hdr_effective_mode", "sdr_10bit")
            .put("hdr_downgrade_reason", "headless_hdr_unavailable")
            .put("hdr_downgrade_message", "Private Headless Stream is using a compositor output that does not report HDR.")
            .put("hdr_source", "missing")
        val json = JSONObject()
            .put("state", "streaming")
            .put("streaming_active", true)
            .put("dynamic_range", 1)
            .put("health", health)

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("sdr_10bit", status.health.hdrEffectiveMode)
        assertEquals("headless_hdr_unavailable", status.health.hdrDowngradeReason)
        assertEquals("Private Headless Stream is using a compositor output that does not report HDR.", status.health.hdrDowngradeMessage)
        assertEquals("missing", status.health.hdrSource)
        assertTrue(status.isHdrDowngraded)
        assertTrue(status.isHeadlessHdrUnavailable)
    }

    @Test
    fun parseSessionStatusResponse_includesLiveSessionFields() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true,\"shutdown_requested\":false," +
                "\"game_id\":123,\"game_uuid\":\"game-uuid\"," +
                "\"session_token\":\"token-123\",\"owner_unique_id\":\"owner-uuid\"," +
                "\"owner_device_name\":\"Retroid\",\"client_role\":\"viewer\",\"viewer_count\":2,\"owned_by_client\":true," +
                "\"cursor_visible\":true,\"dynamic_range\":1,\"mangohud_configured\":true,\"ai_auto_quality_enabled\":true," +
                "\"controls\":{\"host_tuning_allowed\":false,\"quit_allowed\":false,\"shutdown_in_progress\":false," +
                "\"client_commands_enabled\":true,\"device_commands_enabled\":true}," +
                "\"tuning\":{\"adaptive_bitrate_enabled\":true,\"adaptive_target_bitrate_kbps\":18000," +
                "\"adaptive_base_bitrate_kbps\":20000,\"adaptive_min_bitrate_kbps\":2000," +
                "\"adaptive_max_bitrate_kbps\":30000,\"adaptive_bitrate_state\":\"network_pressure\"," +
                "\"adaptive_bitrate_reason\":\"packet_loss\"," +
                "\"ai_auto_quality_enabled\":true,\"ai_optimizer_enabled\":true,\"mangohud_configured\":true}," +
                "\"display_mode\":{\"label\":\"Headless\",\"selection\":\"headless\",\"requested\":\"auto\"," +
                "\"explicit_choice\":false,\"virtual_display\":false,\"requested_headless\":true,\"effective_headless\":true}," +
                "\"capture\":{\"backend\":\"wayland\",\"resolution\":\"1920x1080\"," +
                "\"transport\":\"dmabuf\",\"residency\":\"gpu\",\"format\":\"bgra8\"}," +
                "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0," +
                "\"requested_client_fps\":60.0,\"session_target_fps\":60.0," +
                "\"encode_target_fps\":60.0,\"pacing_policy\":\"client_fps_limit\",\"optimization_source\":\"ai_cached\"," +
                "\"optimization_confidence\":\"medium\",\"optimization_cache_status\":\"hit\"," +
                "\"optimization_reasoning\":\"Cached AI recommendation remained healthy.\"," +
                "\"optimization_normalization_reason\":\"Adjusted bitrate to fit host limits.\"," +
                "\"recommendation_version\":2," +
                "\"target_device\":\"cuda\"," +
                "\"target_residency\":\"gpu\",\"target_format\":\"p010\"}," +
                "\"profile_state\":{\"state\":\"stable\",\"label\":\"Stable\",\"reason\":\"Auto Quality is holding the current profile.\"," +
                "\"source\":\"ai_cached\",\"cache_status\":\"hit\",\"confidence\":\"medium\"," +
                "\"preference\":\"high_fps\",\"preference_label\":\"Prefer High FPS\",\"preference_applied\":false," +
                "\"current_profile\":{\"display_mode\":\"1280x720x120\",\"target_bitrate_kbps\":60000," +
                "\"target_fps\":120.0,\"preferred_codec\":\"hevc\",\"hdr\":false}," +
                "\"last_result\":{\"grade\":\"A\",\"session_count\":3,\"delivered_fps\":118.0,\"target_fps\":120.0," +
                "\"low_1_percent_fps\":104.0,\"min_fps\":88.0,\"frame_pacing_bad_pct\":1.5," +
                "\"primary_issue\":\"steady\",\"sample_confidence\":\"high\",\"updated_at\":1770000000}," +
                "\"actions\":{\"can_reset\":true,\"can_retry_quality\":false,\"can_keep_recovery\":false," +
                "\"can_change_preference\":true}}," +
                "\"health\":{\"grade\":\"watch\",\"summary\":\"Network jitter is the most likely source of the hitching.\"," +
                "\"primary_issue\":\"network_jitter\",\"issues\":[\"network_jitter\",\"frame_pacing\"]," +
                "\"recommendations\":[\"Lower bitrate or keep Adaptive Bitrate enabled.\"]," +
                "\"safe_bitrate_kbps\":15000,\"safe_codec\":\"hevc\",\"safe_display_mode\":\"headless\"," +
                "\"safe_hdr\":false,\"decoder_risk\":\"normal\",\"hdr_risk\":\"normal\",\"network_risk\":\"elevated\"," +
                "\"relaunch_recommended\":true}," +
                "\"client_settings\":{\"desired\":{\"sync_mode\":\"auto_safe\",\"target_bitrate_kbps\":6000," +
                "\"adaptive_bitrate_enabled\":true,\"ai_optimizer_enabled\":true}," +
                "\"effective\":{\"target_bitrate_kbps\":6000,\"adaptive_target_bitrate_kbps\":5200," +
                "\"adaptive_bitrate_enabled\":true,\"ai_optimizer_enabled\":true," +
                "\"applied_stream_settings\":{\"target_bitrate_kbps\":6000,\"display_mode\":\"1280x720x60\"," +
                "\"preferred_codec\":\"hevc\",\"hdr\":false}}," +
                "\"sync_status\":{\"available\":true,\"version\":1,\"state\":\"synced\"," +
                "\"legacy_state\":\"adaptive_active\",\"sync_mode\":\"auto_safe\",\"manual_override\":false," +
                "\"message\":\"Auto Safe is active\"," +
                "\"applied_stream_settings\":{\"target_bitrate_kbps\":6000,\"display_mode\":\"1280x720x60\"," +
                "\"preferred_codec\":\"hevc\",\"hdr\":false}," +
                "\"fields\":{\"client_presentation\":{\"status\":\"synced\"," +
                "\"desired\":{\"target_refresh_rate_hz\":60.0,\"refresh_rate_policy\":\"exact_match_internal\"}," +
                "\"effective\":{\"status\":\"synced\",\"applied_refresh_rate_hz\":60.0,\"target_refresh_rate_hz\":60.0," +
                "\"refresh_rate_policy\":\"exact_match_internal\",\"display_mode\":\"refresh_rate:60.0\"," +
                "\"decoder\":\"c2.qti.hevc.decoder.low_latency\",\"frame_pacing_state\":\"steady\"," +
                "\"reason\":\"Nova matched the internal display refresh rate to the stream FPS\"}}}}}," +
                "\"stream_policy\":{\"presentation_policy\":{\"version\":1,\"target_refresh_rate_hz\":60.0," +
                "\"refresh_rate_policy\":\"exact_match_internal\",\"allow_display_mode_change\":true," +
                "\"internal_display_only\":true,\"reason\":\"Match internal handheld displays.\"}}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("streaming", status.state)
        assertEquals(123, status.gameId)
        assertEquals("game-uuid", status.gameUuid)
        assertEquals("token-123", status.sessionToken)
        assertEquals("owner-uuid", status.ownerUniqueId)
        assertEquals("Retroid", status.ownerDeviceName)
        assertEquals("viewer", status.clientRole)
        assertEquals(2, status.viewerCount)
        assertTrue(status.ownedByClient)
        assertTrue(status.streamingActive)
        assertTrue(status.cursorVisible)
        assertTrue(status.mangohudConfigured)
        assertFalse(status.controls.shutdownInProgress)
        assertTrue(status.tuning.adaptiveBitrateEnabled)
        assertTrue(status.aiAutoQualityEnabled)
        assertTrue(status.tuning.aiAutoQualityEnabled)
        assertEquals(18000, status.tuning.adaptiveTargetBitrateKbps)
        assertEquals(20000, status.tuning.adaptiveBaseBitrateKbps)
        assertEquals(2000, status.tuning.adaptiveMinBitrateKbps)
        assertEquals(30000, status.tuning.adaptiveMaxBitrateKbps)
        assertEquals("network_pressure", status.tuning.adaptiveBitrateState)
        assertEquals("packet_loss", status.tuning.adaptiveBitrateReason)
        assertEquals("auto", status.displayMode.requested)
        assertEquals("ai_cached", status.encoder.optimizationSource)
        assertEquals("medium", status.encoder.optimizationConfidence)
        assertEquals("hit", status.encoder.optimizationCacheStatus)
        assertEquals("Adjusted bitrate to fit host limits.", status.encoder.optimizationNormalizationReason)
        assertEquals(2, status.encoder.recommendationVersion)
        assertEquals("1920x1080", status.capture.resolution)
        assertEquals("dmabuf", status.capture.transport)
        assertEquals("gpu", status.encoder.targetResidency)
        assertEquals("p010", status.encoder.targetFormat)
        assertEquals("watch", status.health.grade)
        assertEquals("network_jitter", status.health.primaryIssue)
        assertTrue(status.health.recommendations.contains("Lower bitrate or keep Adaptive Bitrate enabled."))
        assertEquals(15000, status.health.safeBitrateKbps)
        assertEquals("hevc", status.health.safeCodec)
        assertEquals("headless", status.health.safeDisplayMode)
        assertEquals(false, status.health.safeHdr)
        assertTrue(status.hasHealthConcerns)
        assertTrue(status.isTenBitActive)
        assertTrue(status.isGpuPath)
        assertTrue(status.isViewer)
        assertEquals("Cached AI", status.optimizationSourceLabel)
        assertEquals("MEDIUM", status.optimizationConfidenceLabel)
        assertEquals(60.0, status.presentationPolicy.targetRefreshRateHz, 0.01)
        assertEquals("exact_match_internal", status.presentationPolicy.refreshRatePolicy)
        assertTrue(status.presentationPolicy.allowDisplayModeChange)
        assertTrue(status.isClientPresentationSynced)
        assertEquals("synced", status.clientPresentation.status)
        assertEquals(60.0, status.clientPresentation.appliedRefreshRateHz, 0.01)
        assertEquals("c2.qti.hevc.decoder.low_latency", status.clientPresentation.decoder)
        assertTrue(status.hasOptimizerSync)
        assertEquals("synced", status.syncStatus.state)
        assertEquals("adaptive_active", status.syncStatus.legacyState)
        assertEquals("auto_safe", status.syncStatus.syncMode)
        assertTrue(status.syncStatus.isSynced)
        assertEquals("Synced", status.syncStatus.label)
        assertEquals(6000, status.syncStatus.effective.targetBitrateKbps)
        assertEquals(5200, status.syncStatus.effective.adaptiveTargetBitrateKbps)
        assertEquals("1280x720x60", status.syncStatus.applied.displayMode)
        assertEquals("hevc", status.syncStatus.applied.preferredCodec)
        assertEquals("stable", status.profileState.state)
        assertEquals("Stable", status.profileState.label)
        assertEquals("Prefer High FPS", status.profileState.preferenceLabel)
        assertFalse(status.profileState.preferenceApplied)
        assertEquals("1280x720x120", status.profileState.currentProfile.displayMode)
        assertEquals(60000, status.profileState.currentProfile.targetBitrateKbps)
        assertEquals(120.0, status.profileState.currentProfile.targetFps, 0.01)
        assertEquals("hevc", status.profileState.currentProfile.preferredCodec)
        assertEquals(false, status.profileState.currentProfile.hdr)
        assertEquals("A", status.profileState.lastResult.grade)
        assertEquals(3, status.profileState.lastResult.sessionCount)
        assertEquals(118.0, status.profileState.lastResult.deliveredFps, 0.01)
        assertTrue(status.profileState.actions.canReset)
    }

    @Test
    fun parseSessionStatusResponse_includesHostRenderLimitedHealth() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"auto_quality\":{\"enabled\":true,\"state\":\"recovery_queued\",\"blocked_reason\":\"none\"," +
                "\"live_bitrate_kbps\":4707,\"quality_cap_kbps\":50000," +
                "\"relaunch_required\":true,\"suggested_profile\":{\"target_fps\":30}," +
                "\"summary\":\"AI Recovery Profile ready for the next launch.\"}," +
                "\"health\":{\"auto_mode\":true,\"limiting_factor\":\"host_render\",\"auto_action\":\"lower_render_profile\"," +
                "\"grade\":\"watch\",\"summary\":\"Host render is missing the stream FPS target.\"," +
                "\"primary_issue\":\"host_render_limited\",\"issues\":[\"host_render_limited\"]," +
                "\"host_render_limited\":true,\"safe_target_fps\":30,\"recovery_profile\":\"host_render_limited\"," +
                "\"render_fps_gap\":4.0,\"relaunch_recommended\":true}," +
                "\"encoder\":{\"requested_client_fps\":30.0,\"session_target_fps\":30.0,\"encode_target_fps\":30.0}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertTrue(status.isHostRenderLimited)
        assertEquals("Host render", status.healthToneLabel)
        assertEquals("host_render_limited", status.health.primaryIssue)
        assertEquals(4.0, status.health.renderFpsGap, 0.01)
        assertTrue(status.health.autoMode)
        assertEquals("host_render", status.health.limitingFactor)
        assertEquals("lower_render_profile", status.health.autoAction)
        assertEquals("host_render_limited", status.health.recoveryProfile)
        assertEquals(30.0, status.health.safeTargetFps, 0.01)
        assertTrue(status.health.relaunchRecommended)
        assertEquals("recovery_queued", status.autoQuality.state)
        assertEquals("none", status.autoQuality.blockedReason)
        assertEquals(30.0, status.autoQuality.suggestedTargetFps, 0.01)
        assertEquals(4707, status.autoQuality.liveBitrateKbps)
        assertEquals(50000, status.autoQuality.qualityCapKbps)
        assertTrue(status.autoQuality.enabled)
        assertTrue(status.autoQuality.isRecoveryQueued)
    }

    @Test
    fun parseSessionStatusResponse_includesLinuxGpuProfile() {
        val json = JSONObject(
            "{\"state\":\"streaming\",\"streaming_active\":true," +
                "\"capture\":{\"path\":\"shm_cpu_capture\",\"reason\":\"gpu_native_requested_shm_fallback\"," +
                "\"transport\":\"shm\",\"residency\":\"cpu\",\"format\":\"bgra8\"}," +
                "\"encoder\":{\"codec\":\"hevc\",\"target_device\":\"vaapi\",\"target_residency\":\"gpu\"}," +
                "\"linux_gpu_profile\":{\"encoder_api\":\"vaapi\",\"encoder_adapter\":\"/dev/dri/renderD128\"," +
                "\"capture_device\":\"/dev/dri/renderD128\",\"adapter_matches_capture_device\":true," +
                "\"gpu_native_requested\":true,\"gpu_native_attempted\":true,\"gpu_native_succeeded\":false," +
                "\"vaapi_vendor\":\"Mesa Gallium\"}}"
        )

        val status = PolarisApiClient.parseSessionStatusResponse(json)

        assertEquals("vaapi", status.linuxGpuProfile?.encoderApi)
        assertEquals("/dev/dri/renderD128", status.linuxGpuProfile?.encoderAdapter)
        assertEquals("/dev/dri/renderD128", status.linuxGpuProfile?.captureDevice)
        assertTrue(status.linuxGpuProfile?.adapterMatchesCaptureDevice == true)
        assertTrue(status.linuxGpuProfile?.gpuNativeRequested == true)
        assertFalse(status.linuxGpuProfile?.gpuNativeSucceeded ?: true)
        assertEquals("Mesa Gallium", status.linuxGpuProfile?.vaapiVendor)
        assertEquals("VAAPI + SHM fallback", status.hostCaptureTruthLabel)
    }

    @Test
    fun buildClientSettingsUpdateBody_mapsAiAutoQualityToLegacyFields() {
        val body = PolarisApiClient.buildClientSettingsUpdateBody(
            streamDisplayMode = null,
            displayMode = null,
            clearDisplayMode = false,
            targetBitrateKbps = null,
            clearTargetBitrate = false,
            adaptiveBitrateEnabled = null,
            aiOptimizerEnabled = null,
            aiAutoQualityEnabled = true,
            disconnectResumeTimeoutSeconds = null
        )

        assertTrue(body.getBoolean("ai_auto_quality_enabled"))
        assertTrue(body.getBoolean("ai_optimizer_enabled"))
        assertTrue(body.getBoolean("adaptive_bitrate_enabled"))
    }

    @Test
    fun buildOptimizerProfileClearBody_includesDeviceAndGame() {
        val body = PolarisApiClient.buildOptimizerProfileClearBody(
            "Retroid Pocket 6",
            "Black Myth: Wukong"
        )

        assertEquals("Retroid Pocket 6", body.getString("device"))
        assertEquals("Black Myth: Wukong", body.getString("game"))
    }

    @Test
    fun parseSessionStatus_cudaTargetDeviceImpliesGpuPathWhenResidencyMissing() {
        val status = PolarisApiClient.parseSessionStatusResponse(
            JSONObject(
                "{\"state\":\"streaming\",\"streaming_active\":true," +
                    "\"display_mode\":{\"label\":\"Virtual Display\",\"selection\":\"virtual_display\"," +
                    "\"virtual_display\":true,\"effective_headless\":false}," +
                    "\"capture\":{\"transport\":\"dmabuf\"}," +
                    "\"encoder\":{\"codec\":\"hevc_nvenc\",\"target_device\":\"cuda\",\"target_format\":\"p010\"}}"
            )
        )

        assertEquals("cuda", status.encoder.targetDevice)
        assertTrue(status.isGpuPath)
        assertTrue(status.isTenBitActive)
    }

    @Test
    fun parseGameResponse_includesLaunchModeContract() {
        val json = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Steam Big Picture\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"headless_stream\"," +
                "\"allowed_modes\":[\"headless_stream\",\"desktop_display\",\"windowed_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"Headless is recommended because this Polaris host is already configured for headless streaming.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(json)
        val launchMode = game.launchMode!!

        assertEquals("game-uuid", game.id)
        assertEquals("virtual_display", launchMode.preferredMode)
        assertEquals("headless", launchMode.recommendedMode)
        assertTrue(launchMode.allowedModes.contains("headless"))
        assertTrue(launchMode.allowedModes.contains("virtual_display"))
    }

    @Test
    fun parseGameResponse_emptyAllowedLaunchModesDefaultsAvailable() {
        val json = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"headless_stream\",\"recommended_mode\":\"headless_stream\"," +
                "\"allowed_modes\":[],\"mode_reason\":\"Default launch mode.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(json)
        val launchMode = game.launchMode!!

        assertEquals("headless", launchMode.preferredMode)
        assertTrue(launchMode.allowedModes.contains("headless"))
        assertTrue(launchMode.allowedModes.contains("virtual_display"))
    }

    @Test
    fun launchModeChoice_forcesHeadlessWhenVirtualDisplayUnavailable() {
        val settingsJson = JSONObject(
            "{\"version\":1,\"desired\":{},\"effective\":{},\"capabilities\":{\"modes\":[" +
                "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":false," +
                "\"reason\":\"Virtual display output is not configured.\"}]}}"
        )
        val settings = PolarisApiClient.parseClientSettingsResponse(settingsJson)
        val gameJson = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"Virtual display preferred.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(gameJson)
        val choice = game.resolveLaunchModeChoice(true, settings)

        assertEquals("headless", choice.preferredMode)
        assertEquals("headless", choice.recommendedMode)
        assertTrue(choice.headlessAllowed)
        assertFalse(choice.virtualDisplayAllowed)
        assertTrue(choice.virtualDisplayUnavailable)
        assertEquals("Virtual display output is not configured.", choice.virtualDisplayUnavailableReason)
    }

    @Test
    fun launchModeChoice_usesHostStreamModeOverGameVirtualPreference() {
        val settingsJson = JSONObject(
            "{\"version\":1," +
                "\"desired\":{\"stream_display_mode\":\"headless_stream\"," +
                "\"stream_display_mode_reason\":\"Polaris will stream from the private headless compositor runtime.\"}," +
                "\"effective\":{},\"capabilities\":{\"modes\":[" +
                "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":true}]}}"
        )
        val settings = PolarisApiClient.parseClientSettingsResponse(settingsJson)
        val gameJson = JSONObject(
            "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                "\"mode_reason\":\"This app is configured to prefer a dedicated virtual display on the host.\"}}"
        )

        val game = PolarisGameJsonAdapter.fromJson(gameJson)
        val choice = game.resolveLaunchModeChoice(true, settings)

        assertEquals("virtual_display", choice.preferredMode)
        assertEquals("headless", choice.recommendedMode)
        assertEquals("headless", choice.hostDefaultMode)
        assertTrue(choice.virtualDisplayAllowed)
        assertFalse(choice.virtualDisplayUnavailable)
        assertEquals(
            "Polaris will stream from the private headless compositor runtime.",
            choice.hostModeReason
        )
    }

    @Test
    fun parseGame_includesSteamLaunchContract() {
        val game = PolarisGameJsonAdapter.fromJson(
            JSONObject(
                """
                {
                  "id": "game-1",
                  "app_id": 123,
                  "name": "Portal",
                  "source": "steam",
                  "steam_appid": "400",
                  "steam_launch": {
                    "available": true,
                    "mode": "big-picture",
                    "recommended_mode": "direct",
                    "allowed_modes": ["direct", "big-picture"],
                    "mode_reason": "Steam Big Picture compatibility mode may also receive controller input."
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(game.supportsSteamLaunchMode)
        assertEquals("big-picture", game.steamLaunch?.mode)
        assertEquals("direct", game.steamLaunch?.recommendedMode)
        assertEquals(listOf("direct", "big-picture"), game.steamLaunch?.allowedModes)
        assertEquals(
            "Steam Big Picture compatibility mode may also receive controller input.",
            game.steamLaunch?.modeReason
        )
    }

    @Test
    fun buildMangoHudUpdateBody_includesGameAndExplicitOff() {
        val body = PolarisApiClient.buildMangoHudUpdateBody("game-1", false)

        assertEquals("game-1", body.getString("game_id"))
        assertFalse(body.getBoolean("mangohud"))
    }

    @Test
    fun buildSteamLaunchModeUpdateBody_includesGameAndMode() {
        val body = PolarisApiClient.buildSteamLaunchModeUpdateBody("game-1", "big-picture")

        assertEquals("game-1", body.getString("game_id"))
        assertEquals("big-picture", body.getString("mode"))
    }

    @Test
    fun buildOptimizationPath_includesHighFpsTrialWhenRequested() {
        val path = PolarisApiClient.buildOptimizationPath(
            device = "RetroidPocket6",
            game = "Black Myth: Wukong",
            preference = "high_fps",
            trial = "high_fps"
        )

        assertEquals(
            "/optimize?device=RetroidPocket6&game=Black+Myth%3A+Wukong&preference=high_fps&trial=high_fps",
            path
        )
    }
    @Test
    fun buildSteamLaunchModeUpdateBodyNormalizesAliases() {
        val body = PolarisApiClient.buildSteamLaunchModeUpdateBody("game-1", "gamepadui")

        assertEquals("game-1", body.getString("game_id"))
        assertEquals("big-picture", body.getString("mode"))
    }

}
