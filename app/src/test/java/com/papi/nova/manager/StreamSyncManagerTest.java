package com.papi.nova.manager;

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
public class StreamSyncManagerTest {

    @Test
    public void resolveAutoSafeResolution_appliesLowerOptimizerResolution() throws Exception {
        JSONObject optimization = new JSONObject("{\"display_mode\":\"1280x720x60\"}");

        StreamSyncManager.StreamResolution resolution =
                StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization);

        assertEquals(1280, resolution.width);
        assertEquals(720, resolution.height);
    }

    @Test
    public void resolveAutoSafeResolution_doesNotUpscaleConfiguredResolution() throws Exception {
        JSONObject optimization = new JSONObject("{\"display_mode\":\"1920x1080x60\"}");

        StreamSyncManager.StreamResolution resolution =
                StreamSyncManager.resolveAutoSafeResolution(1280, 720, optimization);

        assertEquals(1280, resolution.width);
        assertEquals(720, resolution.height);
    }

    @Test
    public void resolveAutoSafeResolution_ignoresInvalidDisplayMode() throws Exception {
        JSONObject optimization = new JSONObject("{\"display_mode\":\"headless\"}");

        StreamSyncManager.StreamResolution resolution =
                StreamSyncManager.resolveAutoSafeResolution(1920, 1080, optimization);

        assertEquals(1920, resolution.width);
        assertEquals(1080, resolution.height);
    }

    @Test
    public void resolveAutoSafeBitrate_honorsHighConfidenceOptimizerTarget() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\"," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                        "\"safe_profile\":{\"target_bitrate_kbps\":16000}}}"
        );

        int bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(16000, optimization);

        assertEquals(50000, bitrate);
    }

    @Test
    public void resolveAutoSafeBitrate_keepsConfiguredForNonHighConfidenceTarget() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"target_bitrate_kbps\":50000,\"confidence\":\"medium\"," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        );

        int bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(16000, optimization);

        assertEquals(16000, bitrate);
    }

    @Test
    public void resolveAutoSafeBitrate_clampsOnlyForConfirmedRecovery() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"target_bitrate_kbps\":50000,\"confidence\":\"high\",\"source\":\"history_safe\"," +
                        "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                        "\"safe_profile\":{\"target_bitrate_kbps\":12000}}}"
        );

        int bitrate = StreamSyncManager.resolveAutoSafeBitrateKbps(28000, optimization);

        assertEquals(12000, bitrate);
    }

    @Test
    public void resolveAutoSafeTargetFps_ignoresSafeProfileWithoutRecovery() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                        "\"safe_profile\":{\"target_fps\":30}}}"
        );

        float targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization);

        assertEquals(120f, targetFps, 0.01f);
    }

    @Test
    public void resolveAutoSafeTargetFps_ignoresHostRenderRecoveryProfileWithoutRecoveryAction() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30," +
                        "\"recovery_profile\":\"host_render_limited\"," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                        "\"safe_profile\":{\"target_fps\":30}}}"
        );

        float targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization);

        assertEquals(120f, targetFps, 0.01f);
    }

    @Test
    public void resolveAutoSafeTargetFps_ignoresPlainClientProfileDisplayModeCap() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"display_mode\":\"1280x720x30\",\"source\":\"client_profile\"," +
                        "\"confidence\":\"medium\"," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        );

        float targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization);

        assertEquals(120f, targetFps, 0.01f);
    }

    @Test
    public void resolveAutoSafeTargetFps_appliesAiDisplayModeCap() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"display_mode\":\"1280x720x60\",\"source\":\"ai_cached\"," +
                        "\"confidence\":\"medium\"," +
                        "\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"}}"
        );

        float targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization);

        assertEquals(60f, targetFps, 0.01f);
    }

    @Test
    public void resolveAutoSafeTargetFps_appliesConfirmedRecoveryCap() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"display_mode\":\"1920x1080x120\",\"safe_target_fps\":30,\"source\":\"history_safe\"," +
                        "\"stability\":{\"mode\":\"stability_first\",\"auto_action\":\"apply_recovery\"," +
                        "\"safe_profile\":{\"target_fps\":30}}}"
        );

        float targetFps = StreamSyncManager.resolveAutoSafeTargetFps(120f, optimization);

        assertEquals(30f, targetFps, 0.01f);
    }

    @Test
    public void resolveDisplayCompatibleAutoSafeTargetFps_keepsFortyWhenOneTwentyAllowed() {
        float selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
                40f,
                120f,
                new float[] { 60f, 120f }
        );

        assertEquals(40f, selected, 0.01f);
    }

    @Test
    public void resolveDisplayCompatibleAutoSafeTargetFps_fallsBackToThirtyWhenFortyIsCappedAtSixty() {
        float selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
                40f,
                60f,
                new float[] { 60f, 120f }
        );

        assertEquals(30f, selected, 0.01f);
    }

    @Test
    public void resolveDisplayCompatibleAutoSafeTargetFps_treatsMissingCapAsUnrestricted() {
        float selected = StreamSyncManager.resolveDisplayCompatibleAutoSafeTargetFps(
                40f,
                0f,
                new float[] { 60f, 120f }
        );

        assertEquals(40f, selected, 0.01f);
    }

    @Test
    public void stabilityDecoder_ignoresBalancedSafeProfile() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"stability\":{\"mode\":\"auto\",\"auto_action\":\"none\"," +
                        "\"safe_profile\":{\"target_fps\":30}}}"
        );

        assertFalse(StreamSyncManager.shouldPreferStabilityDecoder(optimization));
    }

    @Test
    public void stabilityDecoder_appliesConfirmedRecoveryProfile() throws Exception {
        JSONObject optimization = new JSONObject(
                "{\"source\":\"history_safe\",\"stability\":{\"mode\":\"stability_first\"," +
                        "\"auto_action\":\"apply_recovery\",\"safe_profile\":{\"target_fps\":30}}}"
        );

        assertTrue(StreamSyncManager.shouldPreferStabilityDecoder(optimization));
    }
}
