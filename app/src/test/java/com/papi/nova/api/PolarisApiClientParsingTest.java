package com.papi.nova.api;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.manager.PolarisProfileSync;

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
                        "\"features\":{\"ai_optimizer\":true,\"cursor_visibility_control\":true,\"client_settings_v1\":true}," +
                        "\"capture\":{\"backend\":\"wayland\",\"codecs\":[\"hevc\"]}}"
        );

        PolarisCapabilities capabilities = PolarisApiClient.parseCapabilitiesResponse(json);

        assertEquals("polaris", capabilities.getServer());
        assertTrue(capabilities.getFeatures().getAiOptimizer());
        assertTrue(capabilities.getFeatures().getCursorVisibilityControl());
        assertTrue(capabilities.getFeatures().getClientSettings());
    }

    @Test
    public void parseClientSettingsResponse_includesBidirectionalSettings() throws Exception {
        JSONObject json = new JSONObject(
                "{\"status\":true,\"client_settings\":{\"version\":1,\"revision\":\"abc\"," +
                        "\"desired\":{\"stream_display_mode\":\"headless_stream\",\"stream_display_mode_label\":\"Headless Stream\"," +
                        "\"display_mode\":\"1920x1080x120\",\"target_bitrate_kbps\":25000," +
                        "\"adaptive_bitrate_enabled\":true,\"ai_optimizer_enabled\":false}," +
                        "\"effective\":{\"stream_display_mode\":\"windowed_stream\",\"stream_display_mode_label\":\"GPU-Native Test\"," +
                        "\"display_mode\":\"1920x1080x120\",\"target_bitrate_kbps\":22000," +
                        "\"adaptive_bitrate_enabled\":true,\"adaptive_target_bitrate_kbps\":22000," +
                        "\"ai_optimizer_enabled\":false,\"capture_path\":\"gpu_native\",\"capture_gpu_native\":true}," +
                        "\"capabilities\":{\"display_mode_override\":true,\"target_bitrate_override\":true," +
                        "\"adaptive_bitrate_control\":true,\"ai_optimizer_control\":true," +
                        "\"modes\":[{\"value\":\"headless_stream\",\"label\":\"Headless Stream\",\"available\":true}]}," +
                        "\"relaunch_required\":true}}"
        );

        PolarisClientSettings settings = PolarisApiClient.parseClientSettingsResponse(json);

        assertEquals("abc", settings.getRevision());
        assertEquals("headless_stream", settings.getDesired().getStreamDisplayMode());
        assertEquals("Headless Stream", settings.getDesiredModeLabel());
        assertEquals(25000, settings.getDesired().getTargetBitrateKbps());
        assertTrue(settings.getDesired().getAdaptiveBitrateEnabled());
        assertFalse(settings.getDesired().getAiOptimizerEnabled());
        assertEquals("windowed_stream", settings.getEffective().getStreamDisplayMode());
        assertEquals("GPU-Native Test", settings.getEffectiveModeLabel());
        assertTrue(settings.getEffective().getAdaptiveBitrateEnabled());
        assertEquals(22000, settings.getEffective().getAdaptiveTargetBitrateKbps());
        assertFalse(settings.getEffective().getAiOptimizerEnabled());
        assertTrue(settings.getEffective().getCaptureGpuNative());
        assertTrue(settings.getCapabilities().getAdaptiveBitrateControl());
        assertTrue(settings.getCapabilities().getAiOptimizerControl());
        assertTrue(settings.getCapabilities().getTargetBitrateOverride());
        assertTrue(settings.getRelaunchRequired());
    }

    @Test
    public void comparePolarisProfile_reportsMatchedDifferentAndUnset() throws Exception {
        PolarisClientSettings matched = PolarisApiClient.parseClientSettingsResponse(new JSONObject(
                "{\"status\":true,\"client_settings\":{\"desired\":{\"display_mode\":\"1920x1080x60\"," +
                        "\"target_bitrate_kbps\":30000,\"adaptive_bitrate_enabled\":false,\"ai_optimizer_enabled\":false}," +
                        "\"effective\":{\"adaptive_bitrate_enabled\":true,\"adaptive_target_bitrate_kbps\":12000," +
                        "\"ai_optimizer_enabled\":true},\"capabilities\":{}}}"
        ));
        PolarisClientSettings different = PolarisApiClient.parseClientSettingsResponse(new JSONObject(
                "{\"status\":true,\"client_settings\":{\"desired\":{\"display_mode\":\"1280x720x60\"," +
                        "\"target_bitrate_kbps\":10000},\"effective\":{},\"capabilities\":{}}}"
        ));
        PolarisClientSettings unset = PolarisApiClient.parseClientSettingsResponse(new JSONObject(
                "{\"status\":true,\"client_settings\":{\"desired\":{},\"effective\":{},\"capabilities\":{}}}"
        ));

        assertEquals(
                PolarisProfileSync.ProfileState.MATCHED,
                PolarisProfileSync.compare("1920x1080x60", 30000, matched)
        );
        assertEquals(
                PolarisProfileSync.ProfileState.DIFFERENT,
                PolarisProfileSync.compare("1920x1080x60", 30000, different)
        );
        assertEquals(
                PolarisProfileSync.ProfileState.POLARIS_UNSET,
                PolarisProfileSync.compare("1920x1080x60", 30000, unset)
        );
        assertEquals(
                PolarisProfileSync.ProfileState.UNAVAILABLE,
                PolarisProfileSync.compare("1920x1080x60", 30000, null)
        );
    }

    @Test
    public void comparePolarisProfile_ignoresLiveHostTuningFields() throws Exception {
        PolarisClientSettings settings = PolarisApiClient.parseClientSettingsResponse(new JSONObject(
                "{\"status\":true,\"client_settings\":{\"desired\":{\"display_mode\":\"1920x1080x60\"," +
                        "\"target_bitrate_kbps\":30000,\"adaptive_bitrate_enabled\":false," +
                        "\"ai_optimizer_enabled\":false},\"effective\":{\"display_mode\":\"1920x1080x60\"," +
                        "\"target_bitrate_kbps\":30000,\"adaptive_bitrate_enabled\":true," +
                        "\"adaptive_target_bitrate_kbps\":12000,\"ai_optimizer_enabled\":true}," +
                        "\"capabilities\":{\"adaptive_bitrate_control\":true,\"ai_optimizer_control\":true}}}"
        ));

        assertEquals(
                PolarisProfileSync.ProfileState.MATCHED,
                PolarisProfileSync.compare("1920x1080x60", 30000, settings)
        );
    }

    @Test
    public void buildClientSettingsUpdateBody_profileDoesNotIncludeHostTuning() {
        JSONObject body = PolarisApiClient.buildClientSettingsUpdateBody(
                null,
                "1920x1080x60",
                false,
                30000,
                false,
                null,
                null,
                null
        );

        assertEquals("1920x1080x60", body.optString("display_mode"));
        assertEquals(30000, body.optInt("target_bitrate_kbps"));
        assertFalse(body.has("adaptive_bitrate_enabled"));
        assertFalse(body.has("ai_optimizer_enabled"));
    }

    @Test
    public void buildClientSettingsUpdateBody_adaptiveOnlyPostsAdaptiveFlag() {
        JSONObject body = PolarisApiClient.buildClientSettingsUpdateBody(
                null,
                null,
                false,
                null,
                false,
                true,
                null,
                null
        );

        assertEquals(1, body.length());
        assertTrue(body.optBoolean("adaptive_bitrate_enabled"));
    }

    @Test
    public void buildClientSettingsUpdateBody_aiOnlyPostsAiFlag() {
        JSONObject body = PolarisApiClient.buildClientSettingsUpdateBody(
                null,
                null,
                false,
                null,
                false,
                null,
                true,
                null
        );

        assertEquals(1, body.length());
        assertTrue(body.optBoolean("ai_optimizer_enabled"));
    }

    @Test
    public void autoSyncPreference_isScopedPerServer() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear().commit();

        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, null));
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, "server-a"));
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, "server-b"));

        PolarisProfileSync.setAutoSyncEnabled(context, "server-a", true);

        assertTrue(PolarisProfileSync.isAutoSyncEnabled(context, "server-a"));
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, "server-b"));

        PolarisProfileSync.setAutoSyncEnabled(context, "server-a", false);
        assertFalse(PolarisProfileSync.isAutoSyncEnabled(context, "server-a"));
    }

    @Test
    public void parseSessionStatusResponse_includesLiveSessionFields() throws Exception {
        JSONObject json = new JSONObject(
                "{\"state\":\"streaming\",\"streaming_active\":true,\"shutdown_requested\":false," +
                        "\"game_id\":123,\"game_uuid\":\"game-uuid\"," +
                        "\"session_token\":\"token-123\",\"owner_unique_id\":\"owner-uuid\"," +
                        "\"owner_device_name\":\"Retroid\",\"client_role\":\"viewer\",\"viewer_count\":2,\"owned_by_client\":true," +
                        "\"cursor_visible\":true,\"dynamic_range\":1,\"mangohud_configured\":true," +
                        "\"controls\":{\"host_tuning_allowed\":false,\"quit_allowed\":false,\"shutdown_in_progress\":false," +
                        "\"client_commands_enabled\":true,\"device_commands_enabled\":true}," +
                        "\"tuning\":{\"adaptive_bitrate_enabled\":true,\"adaptive_target_bitrate_kbps\":18000," +
                        "\"ai_optimizer_enabled\":true,\"mangohud_configured\":true}," +
                        "\"display_mode\":{\"label\":\"Headless\",\"selection\":\"headless\",\"requested\":\"auto\"," +
                        "\"explicit_choice\":false,\"virtual_display\":false,\"requested_headless\":true,\"effective_headless\":true}," +
                        "\"capture\":{\"backend\":\"wayland\",\"resolution\":\"1920x1080\"," +
                        "\"transport\":\"dmabuf\",\"residency\":\"gpu\",\"format\":\"bgra8\"," +
                        "\"path\":\"gpu_native\",\"reason\":\"gpu_native\",\"reason_message\":\"Capture and encoder conversion are GPU-resident.\"," +
                        "\"gpu_native\":true}," +
                        "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0," +
                        "\"requested_client_fps\":60.0,\"session_target_fps\":60.0," +
                        "\"encode_target_fps\":60.0,\"pacing_policy\":\"client_fps_limit\",\"optimization_source\":\"ai_cached\"," +
                        "\"optimization_confidence\":\"medium\",\"optimization_cache_status\":\"hit\"," +
                        "\"optimization_reasoning\":\"Cached AI recommendation remained healthy.\"," +
                        "\"optimization_normalization_reason\":\"Adjusted bitrate to fit host limits.\"," +
                        "\"recommendation_version\":2," +
                        "\"target_device\":\"cuda\"," +
                        "\"target_residency\":\"gpu\",\"target_format\":\"p010\"}," +
                        "\"health\":{\"grade\":\"watch\",\"summary\":\"Network jitter is the most likely source of the hitching.\"," +
                        "\"primary_issue\":\"network_jitter\",\"issues\":[\"network_jitter\",\"frame_pacing\"]," +
                        "\"recommendations\":[\"Lower bitrate or keep Adaptive Bitrate enabled.\"]," +
                        "\"safe_bitrate_kbps\":15000,\"safe_codec\":\"hevc\",\"safe_display_mode\":\"headless\"," +
                        "\"safe_hdr\":false,\"decoder_risk\":\"normal\",\"hdr_risk\":\"normal\",\"network_risk\":\"elevated\"," +
                        "\"relaunch_recommended\":true}," +
                        "\"client_settings\":{\"desired\":{\"stream_display_mode\":\"headless_stream\",\"stream_display_mode_label\":\"Headless Stream\"}," +
                        "\"effective\":{\"stream_display_mode\":\"headless_stream\",\"stream_display_mode_label\":\"Headless Stream\"}}}"
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
        assertEquals(18000, status.getTuning().getAdaptiveTargetBitrateKbps());
        assertEquals("auto", status.getDisplayMode().getRequested());
        assertEquals("ai_cached", status.getEncoder().getOptimizationSource());
        assertEquals("medium", status.getEncoder().getOptimizationConfidence());
        assertEquals("hit", status.getEncoder().getOptimizationCacheStatus());
        assertEquals("Adjusted bitrate to fit host limits.", status.getEncoder().getOptimizationNormalizationReason());
        assertEquals(2, status.getEncoder().getRecommendationVersion());
        assertEquals("1920x1080", status.getCapture().getResolution());
        assertEquals("dmabuf", status.getCapture().getTransport());
        assertEquals("gpu_native", status.getCapture().getPath());
        assertEquals("Capture and encoder conversion are GPU-resident.", status.getCapture().getReasonMessage());
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
        assertEquals("Headless Stream", status.getClientSettings().getDesiredModeLabel());
        assertTrue(status.isViewer());
        assertEquals("Cached AI", status.getOptimizationSourceLabel());
        assertEquals("MEDIUM", status.getOptimizationConfidenceLabel());
    }

    @Test
    public void parseGameResponse_includesLaunchModeContract() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Steam Big Picture\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"host_virtual_display\",\"recommended_mode\":\"headless_stream\"," +
                        "\"allowed_modes\":[\"headless_stream\",\"host_virtual_display\"]," +
                        "\"mode_reason\":\"Headless is recommended because this Polaris host is already configured for headless streaming.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(json);

        assertEquals("game-uuid", game.getId());
        assertEquals("host_virtual_display", game.getLaunchMode().getPreferredMode());
        assertEquals("headless_stream", game.getLaunchMode().getRecommendedMode());
        assertTrue(game.getLaunchMode().getAllowedModes().contains("headless_stream"));
        assertTrue(game.getLaunchMode().getAllowedModes().contains("host_virtual_display"));
    }
}
