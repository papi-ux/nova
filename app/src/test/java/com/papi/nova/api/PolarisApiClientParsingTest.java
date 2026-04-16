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
                "{\"state\":\"streaming\",\"game_id\":123,\"game_uuid\":\"game-uuid\"," +
                        "\"session_token\":\"token-123\",\"owner_unique_id\":\"owner-uuid\"," +
                        "\"owner_device_name\":\"Retroid\",\"owned_by_client\":true," +
                        "\"cursor_visible\":true,\"dynamic_range\":1," +
                        "\"capture\":{\"backend\":\"wayland\",\"resolution\":\"1920x1080\"," +
                        "\"transport\":\"dmabuf\",\"residency\":\"gpu\",\"format\":\"bgra8\"}," +
                        "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0," +
                        "\"requested_client_fps\":60.0,\"session_target_fps\":60.0," +
                        "\"encode_target_fps\":60.0,\"target_device\":\"cuda\"," +
                        "\"target_residency\":\"gpu\",\"target_format\":\"p010\"}}"
        );

        PolarisSessionStatus status = PolarisApiClient.parseSessionStatusResponse(json);

        assertEquals("streaming", status.getState());
        assertEquals(123, status.getGameId());
        assertEquals("game-uuid", status.getGameUuid());
        assertEquals("token-123", status.getSessionToken());
        assertEquals("owner-uuid", status.getOwnerUniqueId());
        assertEquals("Retroid", status.getOwnerDeviceName());
        assertTrue(status.getOwnedByClient());
        assertTrue(status.getCursorVisible());
        assertEquals("1920x1080", status.getCapture().getResolution());
        assertEquals("dmabuf", status.getCapture().getTransport());
        assertEquals("gpu", status.getEncoder().getTargetResidency());
        assertEquals("p010", status.getEncoder().getTargetFormat());
        assertTrue(status.isTenBitActive());
        assertTrue(status.isGpuPath());
    }
}
