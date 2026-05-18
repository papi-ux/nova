package com.papi.nova.manager

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display
import com.papi.nova.binding.video.MediaCodecDecoderRenderer
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.preferences.PreferenceConfiguration
import java.util.Locale
import org.json.JSONObject

class StreamSyncManager private constructor() {
    class StreamResolution(@JvmField val width: Int, @JvmField val height: Int) {
        fun isValid(): Boolean = width > 0 && height > 0

        fun pixels(): Long = width.toLong() * height.toLong()
    }

    companion object {
        const val SYNC_MODE_AUTO_SAFE: String = "auto_safe"

        @JvmStatic
        fun resolveAutoSafeBitrateKbps(configuredBitrateKbps: Int, optimization: JSONObject?): Int {
            if (optimization == null) {
                return configuredBitrateKbps
            }

            val target = optimization.optInt("target_bitrate_kbps", 0)
            val stability = optimization.optJSONObject("stability")
            val safeProfile = stability?.optJSONObject("safe_profile")
            val safeTarget = safeProfile?.optInt("target_bitrate_kbps", 0) ?: 0
            val confirmedRecovery = isConfirmedRecoveryPolicy(optimization, stability)

            if (target > 0 && hasPairedLaunchProfileOverride(optimization)) {
                return if (confirmedRecovery && safeTarget > 0) minOf(target, safeTarget) else target
            }

            var selected = configuredBitrateKbps
            if (target > 0 && shouldHonorOptimizerTarget(optimization, stability)) {
                selected = target
            } else if (selected <= 0 && target > 0) {
                selected = target
            } else if (target > 0 && selected > 0) {
                selected = minOf(selected, target)
            }
            if (confirmedRecovery && safeTarget > 0 && selected > 0) {
                selected = minOf(selected, safeTarget)
            }

            return if (selected > 0) selected else configuredBitrateKbps
        }

        @JvmStatic
        fun resolveAutoSafeResolution(
            configuredWidth: Int,
            configuredHeight: Int,
            optimization: JSONObject?
        ): StreamResolution {
            val configured = StreamResolution(configuredWidth, configuredHeight)
            if (optimization == null) {
                return configured
            }

            val optimized = parseDisplayModeResolution(optimization.optString("display_mode", ""))
            if (!optimized.isValid()) {
                return configured
            }

            if (hasPairedLaunchProfileOverride(optimization)) {
                return optimized
            }

            if (configured.isValid() && optimized.pixels() > configured.pixels()) {
                return configured
            }

            return optimized
        }

        @JvmStatic
        fun resolveAutoSafeTargetFps(configuredFps: Float, optimization: JSONObject?): Float {
            if (optimization == null || configuredFps <= 0f) {
                return configuredFps
            }

            var selected = configuredFps
            val optimizedFps = parseDisplayModeFps(optimization.optString("display_mode", ""))
            val stability = optimization.optJSONObject("stability")
            val safeProfile = stability?.optJSONObject("safe_profile")
            val confirmedRecovery = isConfirmedRecoveryPolicy(optimization, stability)
            val safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability)
            val safeTarget =
                if (confirmedRecovery && !safeTargetRelaxed && safeProfile != null) {
                    safeProfile.optDouble("target_fps", 0.0)
                } else {
                    0.0
                }
            val topLevelSafeTarget =
                if (confirmedRecovery && !safeTargetRelaxed) {
                    optimization.optDouble("safe_target_fps", 0.0)
                } else {
                    0.0
                }

            if (optimizedFps > 0f && hasPairedLaunchProfileOverride(optimization)) {
                selected = optimizedFps
            } else if (
                optimizedFps > 0f &&
                (optimizedFps >= selected || shouldHonorOptimizerFpsTarget(optimization, stability))
            ) {
                selected = minOf(selected, optimizedFps)
            }
            if (safeTarget > 0.0) {
                selected = minOf(selected, safeTarget.toFloat())
            }
            if (topLevelSafeTarget > 0.0) {
                selected = minOf(selected, topLevelSafeTarget.toFloat())
            }

            return if (selected > 0f) selected else configuredFps
        }

        @JvmStatic
        fun resolveDisplayCompatibleAutoSafeTargetFps(
            targetFps: Float,
            maxAllowedRefreshRateHz: Float,
            supportedRefreshRatesHz: FloatArray?
        ): Float {
            if (targetFps <= 0f || supportedRefreshRatesHz == null || supportedRefreshRatesHz.isEmpty()) {
                return targetFps
            }

            if (hasSupportedWholeRefreshMultiple(targetFps, maxAllowedRefreshRateHz, supportedRefreshRatesHz)) {
                return targetFps
            }

            val fallbackTargets = floatArrayOf(60f, 50f, 45f, 40f, 30f, 24f)
            for (fallbackTarget in fallbackTargets) {
                if (fallbackTarget > targetFps + 0.5f) {
                    continue
                }
                if (hasSupportedWholeRefreshMultiple(fallbackTarget, maxAllowedRefreshRateHz, supportedRefreshRatesHz)) {
                    return fallbackTarget
                }
            }

            return targetFps
        }

        private fun hasSupportedWholeRefreshMultiple(
            targetFps: Float,
            maxAllowedRefreshRateHz: Float,
            supportedRefreshRatesHz: FloatArray
        ): Boolean {
            for (refreshRateHz in supportedRefreshRatesHz) {
                if (refreshRateHz <= 0f) {
                    continue
                }
                if (maxAllowedRefreshRateHz > 0f && refreshRateHz > maxAllowedRefreshRateHz + 0.5f) {
                    continue
                }
                if (isWholeRefreshMultiple(refreshRateHz, targetFps)) {
                    return true
                }
            }
            return false
        }

        private fun isWholeRefreshMultiple(refreshRateHz: Float, targetFps: Float): Boolean {
            if (refreshRateHz <= 0f || targetFps <= 0f || refreshRateHz + 0.5f < targetFps) {
                return false
            }

            val ratio = refreshRateHz / targetFps.toDouble()
            val nearestWhole = Math.rint(ratio)
            return nearestWhole >= 1.0 && kotlin.math.abs(ratio - nearestWhole) <= 0.05
        }

        private fun parseDisplayModeResolution(displayMode: String?): StreamResolution {
            if (displayMode.isNullOrEmpty()) {
                return StreamResolution(0, 0)
            }
            val parts = displayMode.split("x")
            if (parts.size < 2) {
                return StreamResolution(0, 0)
            }
            return try {
                StreamResolution(parts[0].toInt(), parts[1].toInt())
            } catch (_: NumberFormatException) {
                StreamResolution(0, 0)
            }
        }

        @JvmStatic
        fun shouldPreferStabilityDecoder(optimization: JSONObject?): Boolean {
            if (optimization == null) {
                return false
            }
            val stability = optimization.optJSONObject("stability")
            if (!isConfirmedRecoveryPolicy(optimization, stability)) {
                return false
            }
            val safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability)

            val source = optimization.optString("source", "")
            if (!safeTargetRelaxed && source.lowercase(Locale.US).contains("history_safe")) {
                return true
            }

            if (!safeTargetRelaxed && optimization.optDouble("safe_target_fps", 0.0) > 0.0) {
                return true
            }

            if (stability == null) {
                return false
            }

            val mode = stability.optString("mode", "")
            if ("stability_first".equals(mode, ignoreCase = true)) {
                return true
            }

            val safeProfile = stability.optJSONObject("safe_profile")
            return !safeTargetRelaxed && safeProfile != null && safeProfile.optDouble("target_fps", 0.0) > 0.0
        }

        @JvmStatic
        fun shouldForceFreshLaunch(optimization: JSONObject?): Boolean {
            if (optimization == null) {
                return false
            }

            if (optimization.optBoolean("relaunch_required", false)) {
                return true
            }

            val stability = optimization.optJSONObject("stability")
            return stability != null && stability.optBoolean("relaunch_required", false)
        }

        @JvmStatic
        fun shouldPreferStableRefreshMultiple(optimization: JSONObject?, targetFps: Float): Boolean {
            if (optimization == null || targetFps <= 0f || targetFps > 45f) {
                return false
            }
            val stability = optimization.optJSONObject("stability")
            if (!isConfirmedRecoveryPolicy(optimization, stability)) {
                return false
            }
            val safeTargetRelaxed = isSafeTargetFpsRelaxed(optimization, stability)

            val source = optimization.optString("source", "")
            if (!safeTargetRelaxed && source.lowercase(Locale.US).contains("history_safe")) {
                return true
            }

            if (!safeTargetRelaxed && optimization.optDouble("safe_target_fps", 0.0) > 0.0) {
                return true
            }

            if (stability == null) {
                return false
            }

            if ("stability_first".equals(stability.optString("mode", ""), ignoreCase = true)) {
                return true
            }

            val safeProfile = stability.optJSONObject("safe_profile")
            return !safeTargetRelaxed && safeProfile != null && safeProfile.optDouble("target_fps", 0.0) > 0.0
        }

        private fun isSafeTargetFpsRelaxed(optimization: JSONObject, stability: JSONObject?): Boolean =
            optimization.optBoolean("safe_target_fps_relaxed", false) ||
                (stability != null && stability.optBoolean("safe_target_fps_relaxed", false))

        private fun shouldHonorOptimizerTarget(optimization: JSONObject, stability: JSONObject?): Boolean {
            if (isConfirmedRecoveryPolicy(optimization, stability)) {
                return false
            }

            return "high" == normalized(optimization.optString("confidence", ""))
        }

        private fun shouldHonorOptimizerFpsTarget(optimization: JSONObject, stability: JSONObject?): Boolean {
            if (isConfirmedRecoveryPolicy(optimization, stability)) {
                return true
            }

            val source = normalized(optimization.optString("source", ""))
            val confidence = normalized(optimization.optString("confidence", ""))
            return source.contains("ai") ||
                source.contains("optimizer") ||
                ("high" == confidence && !source.contains("client_profile"))
        }

        private fun isConfirmedRecoveryPolicy(optimization: JSONObject, stability: JSONObject?): Boolean {
            val source = normalized(optimization.optString("source", ""))
            val autoAction = normalized(optimization.optString("auto_action", ""))
            val mode = if (stability != null) normalized(stability.optString("mode", "")) else ""
            val stabilityAction = if (stability != null) normalized(stability.optString("auto_action", "")) else ""

            return source.contains("history_safe") ||
                "apply_recovery" == autoAction ||
                "apply_recovery" == stabilityAction ||
                "ai_recovery" == mode ||
                "stability_first" == mode
        }

        private fun normalized(value: String?): String = value?.trim()?.lowercase(Locale.US) ?: ""

        private fun hasPairedLaunchProfileOverride(optimization: JSONObject): Boolean {
            if (optimization.optBoolean("paired_profile_applied", false)) {
                return true
            }

            return normalized(optimization.optString("normalization_reason", ""))
                .contains("paired client profile")
        }

        @JvmStatic
        fun buildDeviceCapabilities(
            context: Context,
            display: Display?,
            renderer: MediaCodecDecoderRenderer?,
            supportedVideoFormats: Int,
            displaySupportsHdr10: Boolean,
            externalDisplay: Boolean
        ): JSONObject {
            val json = JSONObject()
            put(json, "manufacturer", Build.MANUFACTURER)
            put(json, "brand", Build.BRAND)
            put(json, "model", Build.MODEL)
            put(json, "device", Build.DEVICE)
            put(json, "sdk_int", Build.VERSION.SDK_INT)
            put(json, "external_display", externalDisplay)
            put(json, "metered_network", isMetered(context))

            if (display != null) {
                put(json, "display_id", display.displayId)
                put(json, "refresh_rate_hz", display.refreshRate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val mode = display.mode
                    put(json, "display_mode", "${mode.physicalWidth}x${mode.physicalHeight}x${mode.refreshRate}")
                }
            }

            put(json, "supports_h264", supportedVideoFormats and MoonBridge.VIDEO_FORMAT_H264 != 0)
            put(json, "supports_hevc", supportedVideoFormats and MoonBridge.VIDEO_FORMAT_H265 != 0)
            put(json, "supports_hevc_main10", supportedVideoFormats and MoonBridge.VIDEO_FORMAT_H265_MAIN10 != 0)
            put(json, "supports_av1", supportedVideoFormats and MoonBridge.VIDEO_FORMAT_AV1_MAIN8 != 0)
            put(json, "supports_av1_main10", supportedVideoFormats and MoonBridge.VIDEO_FORMAT_AV1_MAIN10 != 0)
            put(json, "supports_hdr10_display", displaySupportsHdr10)

            if (renderer != null) {
                put(json, "active_decoder", renderer.activeDecoderName)
                put(json, "hevc_decoder", renderer.isHevcSupported)
                put(json, "av1_decoder", renderer.isAv1Supported)
                put(json, "hevc_main10_hdr10_decoder", renderer.isHevcMain10Hdr10Supported)
                put(json, "av1_main10_decoder", renderer.isAv1Main10Supported)
            }

            return json
        }

        @JvmStatic
        fun buildClientRuntime(
            context: Context,
            renderer: MediaCodecDecoderRenderer?,
            appliedRefreshRateHz: Float,
            displayModeId: Int,
            displayMode: String?,
            framePacing: Int
        ): JSONObject {
            val json = JSONObject()
            put(json, "sync_mode", SYNC_MODE_AUTO_SAFE)
            put(json, "metered_network", isMetered(context))
            put(json, "frame_pacing", framePacing)
            if (appliedRefreshRateHz > 0f) put(json, "applied_refresh_rate_hz", appliedRefreshRateHz)
            if (displayModeId > 0) put(json, "display_mode_id", displayModeId)
            if (!displayMode.isNullOrEmpty()) put(json, "display_mode", displayMode)
            if (renderer != null) {
                put(json, "active_decoder", renderer.activeDecoderName)
                put(json, "active_video_format", renderer.activeVideoFormat)
            }
            return json
        }

        @JvmStatic
        fun buildAppliedStreamSettings(
            bitrateKbps: Int,
            width: Int,
            height: Int,
            launchRefreshRate: Float,
            renderRefreshRate: Float,
            virtualDisplay: Boolean,
            hdr: Boolean,
            supportedVideoFormats: Int,
            videoFormat: PreferenceConfiguration.FormatOption?,
            displayModeExplicit: Boolean
        ): JSONObject {
            val json = JSONObject()
            put(json, "target_bitrate_kbps", bitrateKbps)
            put(json, "display_mode", "${width}x${height}x${Math.round(launchRefreshRate)}")
            put(json, "width", width)
            put(json, "height", height)
            put(json, "launch_refresh_rate_hz", launchRefreshRate)
            put(json, "render_refresh_rate_hz", renderRefreshRate)
            put(json, "virtual_display", virtualDisplay)
            put(json, "hdr", hdr)
            put(json, "display_mode_explicit", displayModeExplicit)
            put(json, "preferred_codec", preferredCodec(videoFormat, supportedVideoFormats))
            return json
        }

        private fun preferredCodec(
            videoFormat: PreferenceConfiguration.FormatOption?,
            supportedVideoFormats: Int
        ): String {
            if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) return "av1"
            if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) return "hevc"
            if (videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) return "h264"
            if (supportedVideoFormats and MoonBridge.VIDEO_FORMAT_AV1_MAIN8 != 0) return "av1"
            if (supportedVideoFormats and MoonBridge.VIDEO_FORMAT_H265 != 0) return "hevc"
            return "h264"
        }

        private fun parseDisplayModeFps(displayMode: String?): Float {
            if (displayMode.isNullOrEmpty()) {
                return 0f
            }
            val parts = displayMode.split("x")
            if (parts.size < 3) {
                return 0f
            }
            return try {
                parts[2].toFloat()
            } catch (_: NumberFormatException) {
                0f
            }
        }

        private fun isMetered(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            return manager != null && manager.isActiveNetworkMetered
        }

        private fun put(json: JSONObject, key: String, value: Any?) {
            try {
                json.put(key, value)
            } catch (_: Exception) {
            }
        }
    }
}
