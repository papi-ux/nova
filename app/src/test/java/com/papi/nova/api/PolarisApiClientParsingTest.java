package com.papi.nova.api;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PolarisApiClientParsingTest {

    @Test
    public void parseCapabilitiesResponse_includesCursorVisibilityControl() throws Exception {
        JSONObject json = new JSONObject(
                "{\"server\":\"polaris\",\"version\":\"1.0.0\"," +
                        "\"features\":{\"ai_optimizer\":true,\"cursor_visibility_control\":true}," +
                        "\"capture\":{\"backend\":\"wayland\",\"codecs\":[\"hevc\"]}}"
        );

        PolarisCapabilities capabilities = PolarisApiClient.parseCapabilitiesResponse(json);

        assertEquals("polaris", capabilities.getServer());
        assertTrue(capabilities.getFeatures().getAiOptimizer());
        assertTrue(capabilities.getFeatures().getCursorVisibilityControl());
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
                        "\"transport\":\"dmabuf\",\"residency\":\"gpu\",\"format\":\"bgra8\"}," +
                        "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0," +
                        "\"requested_client_fps\":60.0,\"session_target_fps\":60.0," +
                        "\"encode_target_fps\":60.0,\"pacing_policy\":\"client_fps_limit\",\"optimization_source\":\"device_db\"," +
                        "\"target_device\":\"cuda\"," +
                        "\"target_residency\":\"gpu\",\"target_format\":\"p010\"}}"
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
        assertEquals("device_db", status.getEncoder().getOptimizationSource());
        assertEquals("1920x1080", status.getCapture().getResolution());
        assertEquals("dmabuf", status.getCapture().getTransport());
        assertEquals("gpu", status.getEncoder().getTargetResidency());
        assertEquals("p010", status.getEncoder().getTargetFormat());
        assertTrue(status.isTenBitActive());
        assertTrue(status.isGpuPath());
        assertTrue(status.isViewer());
    }

    @Test
    public void parseGameResponse_includesLaunchModeContract() throws Exception {
        JSONObject json = new JSONObject(
                "{\"id\":\"game-uuid\",\"app_id\":42,\"name\":\"Steam Big Picture\"," +
                        "\"launch_mode\":{\"preferred_mode\":\"virtual_display\",\"recommended_mode\":\"headless\"," +
                        "\"allowed_modes\":[\"headless\",\"virtual_display\"]," +
                        "\"mode_reason\":\"Headless is recommended because this Polaris host is already configured for headless streaming.\"}}"
        );

        PolarisGame game = PolarisGame.Companion.fromJson(json);

        assertEquals("game-uuid", game.getId());
        assertEquals("virtual_display", game.getLaunchMode().getPreferredMode());
        assertEquals("headless", game.getLaunchMode().getRecommendedMode());
        assertTrue(game.getLaunchMode().getAllowedModes().contains("headless"));
        assertTrue(game.getLaunchMode().getAllowedModes().contains("virtual_display"));
    }
}
