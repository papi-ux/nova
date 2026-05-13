package com.papi.nova.manager;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.view.Display;

import com.papi.nova.binding.video.MediaCodecDecoderRenderer;
import com.papi.nova.nvstream.jni.MoonBridge;
import com.papi.nova.preferences.PreferenceConfiguration;

import org.json.JSONObject;

import java.util.Locale;

public final class StreamSyncManager {
    public static final String SYNC_MODE_AUTO_SAFE = "auto_safe";

    public static final class StreamResolution {
        public final int width;
        public final int height;

        public StreamResolution(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public boolean isValid() {
            return width > 0 && height > 0;
        }

        private long pixels() {
            return (long) width * (long) height;
        }
    }

    private StreamSyncManager() {
    }

    public static int resolveAutoSafeBitrateKbps(int configuredBitrateKbps, JSONObject optimization) {
        if (optimization == null) {
            return configuredBitrateKbps;
        }

        int target = optimization.optInt("target_bitrate_kbps", 0);
        JSONObject stability = optimization.optJSONObject("stability");
        JSONObject safeProfile = stability != null ? stability.optJSONObject("safe_profile") : null;
        int safeTarget = safeProfile != null ? safeProfile.optInt("target_bitrate_kbps", 0) : 0;
        boolean confirmedRecovery = isConfirmedRecoveryPolicy(optimization, stability);

        int selected = configuredBitrateKbps;
        if (target > 0 && shouldHonorOptimizerTarget(optimization, stability)) {
            selected = target;
        } else if (selected <= 0 && target > 0) {
            selected = target;
        } else if (target > 0 && selected > 0) {
            selected = Math.min(selected, target);
        }
        if (confirmedRecovery && safeTarget > 0 && selected > 0) {
            selected = Math.min(selected, safeTarget);
        }

        return selected > 0 ? selected : configuredBitrateKbps;
    }

    public static StreamResolution resolveAutoSafeResolution(int configuredWidth,
                                                             int configuredHeight,
                                                             JSONObject optimization) {
        StreamResolution configured = new StreamResolution(configuredWidth, configuredHeight);
        if (optimization == null) {
            return configured;
        }

        StreamResolution optimized = parseDisplayModeResolution(optimization.optString("display_mode", ""));
        if (!optimized.isValid()) {
            return configured;
        }

        if (configured.isValid() && optimized.pixels() > configured.pixels()) {
            return configured;
        }

        return optimized;
    }

    public static float resolveAutoSafeTargetFps(float configuredFps, JSONObject optimization) {
        if (optimization == null || configuredFps <= 0f) {
            return configuredFps;
        }

        float selected = configuredFps;
        float optimizedFps = parseDisplayModeFps(optimization.optString("display_mode", ""));
        JSONObject stability = optimization.optJSONObject("stability");
        JSONObject safeProfile = stability != null ? stability.optJSONObject("safe_profile") : null;
        boolean confirmedRecovery = isConfirmedRecoveryPolicy(optimization, stability);
        boolean safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability);
        double safeTarget = confirmedRecovery && !safeTargetRelaxed && safeProfile != null ?
                safeProfile.optDouble("target_fps", 0.0) :
                0.0;
        double topLevelSafeTarget = confirmedRecovery && !safeTargetRelaxed ?
                optimization.optDouble("safe_target_fps", 0.0) :
                0.0;

        if (optimizedFps > 0f &&
                (optimizedFps >= selected ||
                        shouldHonorOptimizerFpsTarget(optimization, stability))) {
            selected = Math.min(selected, optimizedFps);
        }
        if (safeTarget > 0.0) {
            selected = Math.min(selected, (float) safeTarget);
        }
        if (topLevelSafeTarget > 0.0) {
            selected = Math.min(selected, (float) topLevelSafeTarget);
        }

        return selected > 0f ? selected : configuredFps;
    }

    public static float resolveDisplayCompatibleAutoSafeTargetFps(float targetFps,
                                                                 float maxAllowedRefreshRateHz,
                                                                 float[] supportedRefreshRatesHz) {
        if (targetFps <= 0f || supportedRefreshRatesHz == null || supportedRefreshRatesHz.length == 0) {
            return targetFps;
        }

        if (hasSupportedWholeRefreshMultiple(targetFps, maxAllowedRefreshRateHz, supportedRefreshRatesHz)) {
            return targetFps;
        }

        float[] fallbackTargets = new float[] { 60f, 50f, 45f, 40f, 30f, 24f };
        for (float fallbackTarget : fallbackTargets) {
            if (fallbackTarget > targetFps + 0.5f) {
                continue;
            }
            if (hasSupportedWholeRefreshMultiple(fallbackTarget, maxAllowedRefreshRateHz, supportedRefreshRatesHz)) {
                return fallbackTarget;
            }
        }

        return targetFps;
    }

    private static boolean hasSupportedWholeRefreshMultiple(float targetFps,
                                                            float maxAllowedRefreshRateHz,
                                                            float[] supportedRefreshRatesHz) {
        for (float refreshRateHz : supportedRefreshRatesHz) {
            if (refreshRateHz <= 0f) {
                continue;
            }
            if (maxAllowedRefreshRateHz > 0f && refreshRateHz > maxAllowedRefreshRateHz + 0.5f) {
                continue;
            }
            if (isWholeRefreshMultiple(refreshRateHz, targetFps)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWholeRefreshMultiple(float refreshRateHz, float targetFps) {
        if (refreshRateHz <= 0f || targetFps <= 0f || refreshRateHz + 0.5f < targetFps) {
            return false;
        }

        double ratio = refreshRateHz / targetFps;
        double nearestWhole = Math.rint(ratio);
        return nearestWhole >= 1.0 && Math.abs(ratio - nearestWhole) <= 0.05;
    }

    private static StreamResolution parseDisplayModeResolution(String displayMode) {
        if (displayMode == null || displayMode.isEmpty()) {
            return new StreamResolution(0, 0);
        }
        String[] parts = displayMode.split("x");
        if (parts.length < 2) {
            return new StreamResolution(0, 0);
        }
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            return new StreamResolution(width, height);
        } catch (NumberFormatException e) {
            return new StreamResolution(0, 0);
        }
    }

    public static boolean shouldPreferStabilityDecoder(JSONObject optimization) {
        if (optimization == null) {
            return false;
        }
        JSONObject stability = optimization.optJSONObject("stability");
        if (!isConfirmedRecoveryPolicy(optimization, stability)) {
            return false;
        }
        boolean safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability);

        String source = optimization.optString("source", "");
        if (!safeTargetRelaxed && source.toLowerCase(java.util.Locale.US).contains("history_safe")) {
            return true;
        }

        if (!safeTargetRelaxed && optimization.optDouble("safe_target_fps", 0.0) > 0.0) {
            return true;
        }

        if (stability == null) {
            return false;
        }

        String mode = stability.optString("mode", "");
        if ("stability_first".equalsIgnoreCase(mode)) {
            return true;
        }

        JSONObject safeProfile = stability.optJSONObject("safe_profile");
        return !safeTargetRelaxed && safeProfile != null && safeProfile.optDouble("target_fps", 0.0) > 0.0;
    }

    public static boolean shouldForceFreshLaunch(JSONObject optimization) {
        if (optimization == null) {
            return false;
        }

        if (optimization.optBoolean("relaunch_required", false)) {
            return true;
        }

        JSONObject stability = optimization.optJSONObject("stability");
        return stability != null && stability.optBoolean("relaunch_required", false);
    }

    public static boolean shouldPreferStableRefreshMultiple(JSONObject optimization, float targetFps) {
        if (optimization == null || targetFps <= 0f || targetFps > 45f) {
            return false;
        }
        JSONObject stability = optimization.optJSONObject("stability");
        if (!isConfirmedRecoveryPolicy(optimization, stability)) {
            return false;
        }
        boolean safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability);

        String source = optimization.optString("source", "");
        if (!safeTargetRelaxed && source.toLowerCase(java.util.Locale.US).contains("history_safe")) {
            return true;
        }

        if (!safeTargetRelaxed && optimization.optDouble("safe_target_fps", 0.0) > 0.0) {
            return true;
        }

        if (stability == null) {
            return false;
        }

        if ("stability_first".equalsIgnoreCase(stability.optString("mode", ""))) {
            return true;
        }

        JSONObject safeProfile = stability.optJSONObject("safe_profile");
        return !safeTargetRelaxed && safeProfile != null && safeProfile.optDouble("target_fps", 0.0) > 0.0;
    }

    private static boolean isSafeTargetFpsRelaxed(JSONObject optimization, JSONObject stability) {
        return optimization.optBoolean("safe_target_fps_relaxed", false) ||
                (stability != null && stability.optBoolean("safe_target_fps_relaxed", false));
    }

    private static boolean shouldHonorOptimizerTarget(JSONObject optimization, JSONObject stability) {
        if (isConfirmedRecoveryPolicy(optimization, stability)) {
            return false;
        }

        return "high".equals(normalized(optimization.optString("confidence", "")));
    }

    private static boolean shouldHonorOptimizerFpsTarget(JSONObject optimization, JSONObject stability) {
        if (isConfirmedRecoveryPolicy(optimization, stability)) {
            return true;
        }

        String source = normalized(optimization.optString("source", ""));
        String confidence = normalized(optimization.optString("confidence", ""));
        return source.contains("ai") ||
                source.contains("optimizer") ||
                ("high".equals(confidence) && !source.contains("client_profile"));
    }

    private static boolean isConfirmedRecoveryPolicy(JSONObject optimization, JSONObject stability) {
        String source = normalized(optimization.optString("source", ""));
        String autoAction = normalized(optimization.optString("auto_action", ""));
        String mode = stability != null ? normalized(stability.optString("mode", "")) : "";
        String stabilityAction = stability != null ? normalized(stability.optString("auto_action", "")) : "";

        return source.contains("history_safe") ||
                "apply_recovery".equals(autoAction) ||
                "apply_recovery".equals(stabilityAction) ||
                "ai_recovery".equals(mode) ||
                "stability_first".equals(mode);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    public static JSONObject buildDeviceCapabilities(Context context,
                                                     Display display,
                                                     MediaCodecDecoderRenderer renderer,
                                                     int supportedVideoFormats,
                                                     boolean displaySupportsHdr10,
                                                     boolean externalDisplay) {
        JSONObject json = new JSONObject();
        put(json, "manufacturer", Build.MANUFACTURER);
        put(json, "brand", Build.BRAND);
        put(json, "model", Build.MODEL);
        put(json, "device", Build.DEVICE);
        put(json, "sdk_int", Build.VERSION.SDK_INT);
        put(json, "external_display", externalDisplay);
        put(json, "metered_network", isMetered(context));

        if (display != null) {
            put(json, "display_id", display.getDisplayId());
            put(json, "refresh_rate_hz", display.getRefreshRate());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode mode = display.getMode();
                put(json, "display_mode", mode.getPhysicalWidth() + "x" +
                        mode.getPhysicalHeight() + "x" + mode.getRefreshRate());
            }
        }

        put(json, "supports_h264", (supportedVideoFormats & MoonBridge.VIDEO_FORMAT_H264) != 0);
        put(json, "supports_hevc", (supportedVideoFormats & MoonBridge.VIDEO_FORMAT_H265) != 0);
        put(json, "supports_hevc_main10", (supportedVideoFormats & MoonBridge.VIDEO_FORMAT_H265_MAIN10) != 0);
        put(json, "supports_av1", (supportedVideoFormats & MoonBridge.VIDEO_FORMAT_AV1_MAIN8) != 0);
        put(json, "supports_av1_main10", (supportedVideoFormats & MoonBridge.VIDEO_FORMAT_AV1_MAIN10) != 0);
        put(json, "supports_hdr10_display", displaySupportsHdr10);

        if (renderer != null) {
            put(json, "active_decoder", renderer.getActiveDecoderName());
            put(json, "hevc_decoder", renderer.isHevcSupported());
            put(json, "av1_decoder", renderer.isAv1Supported());
            put(json, "hevc_main10_hdr10_decoder", renderer.isHevcMain10Hdr10Supported());
            put(json, "av1_main10_decoder", renderer.isAv1Main10Supported());
        }

        return json;
    }

    public static JSONObject buildClientRuntime(Context context,
                                                MediaCodecDecoderRenderer renderer,
                                                float appliedRefreshRateHz,
                                                int displayModeId,
                                                String displayMode,
                                                int framePacing) {
        JSONObject json = new JSONObject();
        put(json, "sync_mode", SYNC_MODE_AUTO_SAFE);
        put(json, "metered_network", isMetered(context));
        put(json, "frame_pacing", framePacing);
        if (appliedRefreshRateHz > 0f) put(json, "applied_refresh_rate_hz", appliedRefreshRateHz);
        if (displayModeId > 0) put(json, "display_mode_id", displayModeId);
        if (displayMode != null && !displayMode.isEmpty()) put(json, "display_mode", displayMode);
        if (renderer != null) {
            put(json, "active_decoder", renderer.getActiveDecoderName());
            put(json, "active_video_format", renderer.getActiveVideoFormat());
        }
        return json;
    }

    public static JSONObject buildAppliedStreamSettings(int bitrateKbps,
                                                        int width,
                                                        int height,
                                                        float launchRefreshRate,
                                                        float renderRefreshRate,
                                                        boolean virtualDisplay,
                                                        boolean hdr,
                                                        int supportedVideoFormats,
                                                        PreferenceConfiguration.FormatOption videoFormat,
                                                        boolean displayModeExplicit) {
        JSONObject json = new JSONObject();
        put(json, "target_bitrate_kbps", bitrateKbps);
        put(json, "display_mode", width + "x" + height + "x" + Math.round(launchRefreshRate));
        put(json, "width", width);
        put(json, "height", height);
        put(json, "launch_refresh_rate_hz", launchRefreshRate);
        put(json, "render_refresh_rate_hz", renderRefreshRate);
        put(json, "virtual_display", virtualDisplay);
        put(json, "hdr", hdr);
        put(json, "display_mode_explicit", displayModeExplicit);
        put(json, "preferred_codec", preferredCodec(videoFormat, supportedVideoFormats));
        return json;
    }

    private static String preferredCodec(PreferenceConfiguration.FormatOption videoFormat, int supportedVideoFormats) {
        if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) return "av1";
        if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) return "hevc";
        if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) return "h264";
        if ((supportedVideoFormats & MoonBridge.VIDEO_FORMAT_AV1_MAIN8) != 0) return "av1";
        if ((supportedVideoFormats & MoonBridge.VIDEO_FORMAT_H265) != 0) return "hevc";
        return "h264";
    }

    private static float parseDisplayModeFps(String displayMode) {
        if (displayMode == null || displayMode.isEmpty()) {
            return 0f;
        }
        String[] parts = displayMode.split("x");
        if (parts.length < 3) {
            return 0f;
        }
        try {
            return Float.parseFloat(parts[2]);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static boolean isMetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager != null && manager.isActiveNetworkMetered();
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
