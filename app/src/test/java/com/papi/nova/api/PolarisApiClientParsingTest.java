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
    public void parseSessionStatusResponse_includesCursorVisible() throws Exception {
        JSONObject json = new JSONObject(
                "{\"state\":\"streaming\",\"cursor_visible\":true," +
                        "\"capture\":{\"backend\":\"wayland\",\"resolution\":\"1920x1080\"}," +
                        "\"encoder\":{\"codec\":\"hevc_nvenc\",\"bitrate_kbps\":20000,\"fps\":60.0}}"
        );

        PolarisSessionStatus status = PolarisApiClient.parseSessionStatusResponse(json);

        assertEquals("streaming", status.getState());
        assertTrue(status.getCursorVisible());
        assertEquals("1920x1080", status.getCapture().getResolution());
    }
}
