package com.papi.nova.api;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PolarisApiClientParsingTest {

    @Test
    public void parseCapabilitiesResponse_includesCursorVisibilityControl() throws Exception {
        JSONObject json = new JSONObject(
                "{\"server\":\"polaris\",\"version\":\"1.0.0\"," +
                        "\"features\":{\"ai_optimizer\":true,\"ai_optimizer_control\":true,\"cursor_visibility_control\":true," +
                        "\"stream_policy_v1\":true,\"client_settings_v1\":true,\"optimizer_sync_v1\":true}," +
                        "\"capture\":{\"backend\":\"wayland\",\"codecs\":[\"hevc\"]}}"
        );

        PolarisCapabilities capabilities = PolarisApiClient.parseCapabilitiesResponse(json);

        assertEquals("polaris", capabilities.getServer());
        assertTrue(capabilities.getFeatures().getAiOptimizer());
        assertTrue(capabilities.getFeatures().getAiAutoQuality());
        assertTrue(capabilities.getFeatures().getAiAutoQualityControl());
        assertTrue(capabilities.getFeatures().getCursorVisibilityControl());
        assertTrue(capabilities.getFeatures().getStreamPolicy());
        assertTrue(capabilities.getFeatures().getClientSettings());
        assertTrue(capabilities.getFeatures().getOptimizerSync());
    }

    @Test
    public void parseSessionStatusResponse_includesLiveSessionFields() throws Exception {
        JSONObject json = new JSONObject(
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
        );

        PolarisSessionStatus status = PolarisApiClient.parseSessionStatusResponse(json);

        assertEquals("streaming", status.getState());
        assertEquals(123, status.getGameId());
        assertEquals("game-uuid", status.getGameUuid());
        assertEquals("token-123", status.getSessionToken());
        assertEquals("owner-uuid", status.getOwnerUniqueId());
        assertEquals("Retroid", status.getOwnerDeviceName());
        assertEquals("viewer", status.getClientRole());
        assertEquals(2, status.getViewerCount());
        assertTrue(status.getOwnedByClient());
        assertTrue(status.getStreamingActive());
        assertTrue(status.getCursorVisible());
        assertTrue(status.getMangohudConfigured());
        assertEquals(false, status.getControls().getShutdownInProgress());
        assertTrue(status.getTuning().getAdaptiveBitrateEnabled());
        assertTrue(status.getAiAutoQualityEnabled());
        assertTrue(status.getTuning().getAiAutoQualityEnabled());
        assertEquals(18000, status.getTuning().getAdaptiveTargetBitrateKbps());
        assertEquals(20000, status.getTuning().getAdaptiveBaseBitrateKbps());
        assertEquals(2000, status.getTuning().getAdaptiveMinBitrateKbps());
        assertEquals(30000, status.getTuning().getAdaptiveMaxBitrateKbps());
        assertEquals("network_pressure", status.getTuning().getAdaptiveBitrateState());
        assertEquals("packet_loss", status.getTuning().getAdaptiveBitrateReason());
        assertEquals("auto", status.getDisplayMode().getRequested());
        assertEquals("ai_cached", status.getEncoder().getOptimizationSource());
        assertEquals("medium", status.getEncoder().getOptimizationConfidence());
        assertEquals("hit", status.getEncoder().getOptimizationCacheStatus());
        assertEquals("Adjusted bitrate to fit host limits.", status.getEncoder().getOptimizationNormalizationReason());
        assertEquals(2, status.getEncoder().getRecommendationVersion());
        assertEquals("1920x1080", status.getCapture().getResolution());
        assertEquals("dmabuf", status.getCapture().getTransport());
        assertEquals("gpu", status.getEncoder().getTargetResidency());
        assertEquals("p010", status.getEncoder().getTargetFormat());
        assertEquals("watch", status.getHealth().getGrade());
        assertEquals("network_jitter", status.getHealth().getPrimaryIssue());
        assertTrue(status.getHealth().getRecommendations().contains("Lower bitrate or keep Adaptive Bitrate enabled."));
        assertEquals(15000, status.getHealth().getSafeBitrateKbps());
        assertEquals("hevc", status.getHealth().getSafeCodec());
        assertEquals("headless", status.getHealth().getSafeDisplayMode());
        assertEquals(Boolean.FALSE, status.getHealth().getSafeHdr());
        assertTrue(status.getHasHealthConcerns());
        assertTrue(status.isTenBitActive());
        assertTrue(status.isGpuPath());
        assertTrue(status.isViewer());
        assertEquals("Cached AI", status.getOptimizationSourceLabel());
        assertEquals("MEDIUM", status.getOptimizationConfidenceLabel());
        assertEquals(60.0, status.getPresentationPolicy().getTargetRefreshRateHz(), 0.01);
        assertEquals("exact_match_internal", status.getPresentationPolicy().getRefreshRatePolicy());
        assertTrue(status.getPresentationPolicy().getAllowDisplayModeChange());
        assertTrue(status.isClientPresentationSynced());
        assertEquals("synced", status.getClientPresentation().getStatus());
        assertEquals(60.0, status.getClientPresentation().getAppliedRefreshRateHz(), 0.01);
        assertEquals("c2.qti.hevc.decoder.low_latency", status.getClientPresentation().getDecoder());
        assertTrue(status.getHasOptimizerSync());
        assertEquals("synced", status.getSyncStatus().getState());
        assertEquals("adaptive_active", status.getSyncStatus().getLegacyState());
        assertEquals("auto_safe", status.getSyncStatus().getSyncMode());
        assertTrue(status.getSyncStatus().isSynced());
        assertEquals("Synced", status.getSyncStatus().getLabel());
        assertEquals(6000, status.getSyncStatus().getEffective().getTargetBitrateKbps());
        assertEquals(5200, status.getSyncStatus().getEffective().getAdaptiveTargetBitrateKbps());
        assertEquals("1280x720x60", status.getSyncStatus().getApplied().getDisplayMode());
        assertEquals("hevc", status.getSyncStatus().getApplied().getPreferredCodec());
        assertEquals("stable", status.getProfileState().getState());
        assertEquals("Stable", status.getProfileState().getLabel());
        assertEquals("Prefer High FPS", status.getProfileState().getPreferenceLabel());
        assertFalse(status.getProfileState().getPreferenceApplied());
        assertEquals("1280x720x120", status.getProfileState().getCurrentProfile().getDisplayMode());
        assertEquals(60000, status.getProfileState().getCurrentProfile().getTargetBitrateKbps());
        assertEquals(120.0, status.getProfileState().getCurrentProfile().getTargetFps(), 0.01);
        assertEquals("hevc", status.getProfileState().getCurrentProfile().getPreferredCodec());
        assertEquals(Boolean.FALSE, status.getProfileState().getCurrentProfile().getHdr());
        assertEquals("A", status.getProfileState().getLastResult().getGrade());
        assertEquals(3, status.getProfileState().getLastResult().getSessionCount());
        assertEquals(118.0, status.getProfileState().getLastResult().getDeliveredFps(), 0.01);
        assertTrue(status.getProfileState().getActions().getCanReset());
    }

    @Test
    public void parseSessionStatusResponse_includesHostRenderLimitedHealth() throws Exception {
        JSONObject json = new JSONObject(
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
        );

        PolarisSessionStatus status = PolarisApiClient.parseSessionStatusResponse(json);

        assertTrue(status.isHostRenderLimited());
        assertEquals("Host render", status.getHealthToneLabel());
        assertEquals("host_render_limited", status.getHealth().getPrimaryIssue());
        assertEquals(4.0, status.getHealth().getRenderFpsGap(), 0.01);
        assertTrue(status.getHealth().getAutoMode());
        assertEquals("host_render", status.getHealth().getLimitingFactor());
        assertEquals("lower_render_profile", status.getHealth().getAutoAction());
        assertEquals("host_render_limited", status.getHealth().getRecoveryProfile());
        assertEquals(30.0, status.getHealth().getSafeTargetFps(), 0.01);
        assertTrue(status.getHealth().getRelaunchRecommended());
        assertEquals("recovery_queued", status.getAutoQuality().getState());
        assertEquals("none", status.getAutoQuality().getBlockedReason());
        assertEquals(30.0, status.getAutoQuality().getSuggestedTargetFps(), 0.01);
        assertEquals(4707, status.getAutoQuality().getLiveBitrateKbps());
        assertEquals(50000, status.getAutoQuality().getQualityCapKbps());
        assertTrue(status.getAutoQuality().getEnabled());
        assertTrue(status.getAutoQuality().isRecoveryQueued());
    }

    @Test
    public void buildClientSettingsUpdateBody_mapsAiAutoQualityToLegacyFields() throws Exception {
        JSONObject body = PolarisApiClient.buildClientSettingsUpdateBody(
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                true,
                null
        );

        assertTrue(body.getBoolean("ai_auto_quality_enabled"));
        assertTrue(body.getBoolean("ai_optimizer_enabled"));
        assertTrue(body.getBoolean("adaptive_bitrate_enabled"));
    }

    @Test
    public void buildOptimizerProfileClearBody_includesDeviceAndGame() throws Exception {
        JSONObject body = PolarisApiClient.buildOptimizerProfileClearBody(
                "Retroid Pocket 6",
                "Black Myth: Wukong"
        );

        assertEquals("Retroid Pocket 6", body.getString("device"));
        assertEquals("Black Myth: Wukong", body.getString("game"));
    }

    @Test
    public void parseGameResponse_includesLaunchModeContract() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Steam Big Picture\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"headless_stream\"," +
                        "\"allowed_modes\":[\"headless_stream\",\"desktop_display\",\"windowed_stream\",\"host_virtual_display\"]," +
                        "\"mode_reason\":\"Headless is recommended because this Polaris host is already configured for headless streaming.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(json);

        assertEquals("game-uuid", game.getId());
        assertEquals("virtual_display", game.getLaunchMode().getPreferredMode());
        assertEquals("headless", game.getLaunchMode().getRecommendedMode());
        assertTrue(game.getLaunchMode().getAllowedModes().contains("headless"));
        assertTrue(game.getLaunchMode().getAllowedModes().contains("virtual_display"));
    }

    @Test
    public void parseGameResponse_emptyAllowedLaunchModesDefaultsAvailable() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"headless_stream\",\"recommended_mode\":\"headless_stream\"," +
                        "\"allowed_modes\":[],\"mode_reason\":\"Default launch mode.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(json);

        assertEquals("headless", game.getLaunchMode().getPreferredMode());
        assertTrue(game.getLaunchMode().getAllowedModes().contains("headless"));
        assertTrue(game.getLaunchMode().getAllowedModes().contains("virtual_display"));
    }

    @Test
    public void launchModeChoice_forcesHeadlessWhenVirtualDisplayUnavailable() throws Exception {
        JSONObject settingsJson = new JSONObject(
                "{\"version\":1,\"desired\":{},\"effective\":{},\"capabilities\":{\"modes\":[" +
                        "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                        "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":false," +
                        "\"reason\":\"Virtual display output is not configured.\"}]}}"
        );
        PolarisClientSettings settings = PolarisApiClient.parseClientSettingsResponse(settingsJson);
        JSONObject gameJson = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                        "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                        "\"mode_reason\":\"Virtual display preferred.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(gameJson);
        PolarisGame.LaunchModeChoice choice = game.resolveLaunchModeChoice(true, settings);

        assertEquals("headless", choice.getPreferredMode());
        assertEquals("headless", choice.getRecommendedMode());
        assertTrue(choice.getHeadlessAllowed());
        assertFalse(choice.getVirtualDisplayAllowed());
        assertTrue(choice.getVirtualDisplayUnavailable());
        assertEquals("Virtual display output is not configured.", choice.getVirtualDisplayUnavailableReason());
    }

    @Test
    public void launchModeChoice_usesHostStreamModeOverGameVirtualPreference() throws Exception {
        JSONObject settingsJson = new JSONObject(
                "{\"version\":1," +
                        "\"desired\":{\"stream_display_mode\":\"headless_stream\"," +
                        "\"stream_display_mode_reason\":\"Polaris will stream from the private headless compositor runtime.\"}," +
                        "\"effective\":{},\"capabilities\":{\"modes\":[" +
                        "{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}," +
                        "{\"value\":\"host_virtual_display\",\"label\":\"Host Virtual Display\",\"available\":true}]}}"
        );
        PolarisClientSettings settings = PolarisApiClient.parseClientSettingsResponse(settingsJson);
        JSONObject gameJson = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Game\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"host_virtual_display\"," +
                        "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                        "\"mode_reason\":\"This app is configured to prefer a dedicated virtual display on the host.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(gameJson);
        PolarisGame.LaunchModeChoice choice = game.resolveLaunchModeChoice(true, settings);

        assertEquals("virtual_display", choice.getPreferredMode());
        assertEquals("headless", choice.getRecommendedMode());
        assertEquals("headless", choice.getHostDefaultMode());
        assertTrue(choice.getVirtualDisplayAllowed());
        assertFalse(choice.getVirtualDisplayUnavailable());
        assertEquals(
                "Polaris will stream from the private headless compositor runtime.",
                choice.getHostModeReason()
        );
    }
}
