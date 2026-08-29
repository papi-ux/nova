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

    data class RecoveryLaunchProfile(
        val runId: String,
        val streamDisplayMode: String,
        val width: Int,
        val height: Int,
        val targetFps: Float,
        val targetBitrateKbps: Int,
        val preferredCodec: String,
        val hdr: Boolean,
        val requiresFreshLaunch: Boolean
    ) {
        val virtualDisplay get() = streamDisplayMode == "host_virtual_display"
        val mirrorDesktop get() = streamDisplayMode == "desktop_display"
    }

    companion object {
        const val SYNC_MODE_AUTO_SAFE: String = "auto_safe"

        @JvmStatic
        fun maxSupportedRefreshRate(display: Display?): Float {
            if (display == null) return 0f
            var maximum = display.refreshRate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display.supportedModes.forEach { mode ->
                    maximum = maxOf(maximum, mode.refreshRate)
                }
            } else {
                @Suppress("DEPRECATION")
                display.supportedRefreshRates.forEach { rate ->
                    maximum = maxOf(maximum, rate)
                }
            }
            return maximum
        }

        @JvmStatic
        fun resolveProfileProvenance(
            optimization: JSONObject?,
            manualOverride: Boolean
        ): ClientProfileProvenance = ClientProfileProvenance.fromOptimization(
            optimization = optimization,
            manualOverride = manualOverride
        )

        @JvmStatic
        fun resolveAutoSafeBitrateKbps(configuredBitrateKbps: Int, optimization: JSONObject?): Int {
            val target = resolvedField(optimization, "target_bitrate_kbps") as? Number
            val resolved = target?.toInt()?.takeIf { it > 0 } ?: return configuredBitrateKbps
            // Metered and other per-launch limits are sent to /optimize as
            // explicit locks. Once the authenticated resolver returns a valid
            // envelope, Nova must consume that exact value without rewriting it.
            return resolved
        }

        @JvmStatic
        fun resolveAutoSafeResolution(
            configuredWidth: Int,
            configuredHeight: Int,
            optimization: JSONObject?
        ): StreamResolution {
            val configured = StreamResolution(configuredWidth, configuredHeight)
            val width = strictIntegralValue(resolvedField(optimization, "display_width"))
            val height = strictIntegralValue(resolvedField(optimization, "display_height"))
            val resolved = StreamResolution(width ?: 0, height ?: 0)
            return resolved.takeIf { it.isValid() } ?: configured
        }

        @JvmStatic
        fun resolveAutoSafeTargetFps(configuredFps: Float, optimization: JSONObject?): Float {
            if (configuredFps <= 0f) return configuredFps
            val resolved = (resolvedField(optimization, "target_fps") as? Number)?.toFloat() ?: 0f
            return resolved.takeIf { it > 0f && it.isFinite() } ?: configuredFps
        }

        @JvmStatic
        fun resolveAutoSafeHdr(configuredHdr: Boolean, optimization: JSONObject?): Boolean {
            val resolved = resolvedHdrValue(optimization) ?: return configuredHdr
            return resolved
        }

        @JvmStatic
        fun resolvedHdrValue(optimization: JSONObject?): Boolean? =
            resolvedField(optimization, "hdr") as? Boolean

        @JvmStatic
        fun resolvedFieldIsLocked(optimization: JSONObject?, name: String): Boolean? =
            validResolvedField(
                resolvedProfile(optimization)
                    ?.optJSONObject("fields")
                    ?.optJSONObject(name)
            )?.opt("locked") as? Boolean

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
            return resolvedPreset(optimization) == "stability"
        }

        @JvmStatic
        fun shouldForceFreshLaunch(optimization: JSONObject?): Boolean {
            // Preset selection is part of an ordinary explicit launch. Legacy
            // optimizer/recovery payloads cannot force a stop-and-relaunch.
            return false
        }

        @JvmStatic
        fun requiresLaunchPreflightReview(optimization: JSONObject?): Boolean {
            if (optimization == null) {
                return false
            }

            if (hasMaterialFpsOverride(optimization)) {
                return true
            }

            val profileState = optimization.optJSONObject("profile_state")
            val preference = normalized(
                profileState?.optString("preference", "")?.takeIf { it.isNotBlank() }
                    ?: optimization.optString("preference", "auto")
            )
            val preferenceApplied =
                if (profileState != null && profileState.has("preference_applied")) {
                    profileState.optBoolean("preference_applied", preference == "auto")
                } else {
                    optimization.optBoolean("preference_applied", preference == "auto")
                }
            if (preference == "high_fps" && !hasMaterialFpsOverride(optimization)) {
                return false
            }

            return preference != "auto" && !preferenceApplied &&
                explicitPreferenceBlockReason(optimization).isNotEmpty()
        }

        @JvmStatic
        fun launchPreflightReviewReason(optimization: JSONObject?): String {
            if (optimization == null) {
                return ""
            }

            val profileState = optimization.optJSONObject("profile_state")
            val preference = normalized(
                profileState?.optString("preference", "")?.takeIf { it.isNotBlank() }
                    ?: optimization.optString("preference", "auto")
            )
            if (preference == "high_fps" && !hasMaterialFpsOverride(optimization)) {
                return ""
            }

            explicitPreferenceBlockReason(optimization).takeIf { it.isNotEmpty() }?.let {
                return it
            }

            if (hasMaterialFpsOverride(optimization)) {
                return "fps_override"
            }

            return ""
        }

        private fun hasMaterialFpsOverride(optimization: JSONObject): Boolean {
            val requestedFps = optimization.optDouble("requested_target_fps", 0.0)
            val effectiveFps = optimization.optDouble("effective_target_fps", 0.0)
            return requestedFps > 0.0 && effectiveFps > 0.0 &&
                kotlin.math.abs(requestedFps - effectiveFps) > 0.5
        }

        private fun explicitPreferenceBlockReason(optimization: JSONObject): String {
            val profileState = optimization.optJSONObject("profile_state")
            val stateReason = normalized(profileState?.optString("preference_blocked_reason", "none"))
            if (stateReason.isNotEmpty() && stateReason != "none") {
                return stateReason
            }

            val topLevelReason = normalized(optimization.optString("preference_blocked_reason", "none"))
            if (topLevelReason.isNotEmpty() && topLevelReason != "none") {
                return topLevelReason
            }

            return ""
        }

        @JvmStatic
        fun shouldPreferStableRefreshMultiple(optimization: JSONObject?, targetFps: Float): Boolean {
            return targetFps in 1f..45f && resolvedPreset(optimization) == "stability"
        }

        private fun normalized(value: String?): String = value?.trim()?.lowercase(Locale.US) ?: ""

        private val resolvedFieldSources = setOf(
            "explicit_launch_request",
            "client_launch_request",
            "paired_client",
            "client_profile",
            "device_profile_v1",
            "capability_validation",
            "composed_display_components"
        )

        private fun strictIntegralValue(value: Any?): Int? {
            val number = value as? Number ?: return null
            val doubleValue = number.toDouble()
            if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0 ||
                doubleValue < Int.MIN_VALUE || doubleValue > Int.MAX_VALUE) {
                return null
            }
            return doubleValue.toInt()
        }

        private fun resolvedProfile(optimization: JSONObject?): JSONObject? {
            val payload = optimization ?: return null
            val source = payload.opt("source") as? String
            if (normalized(source) != "deterministic_preset_v1") {
                return null
            }
            val profile = payload.opt("resolved_profile") as? JSONObject ?: return null
            if (strictIntegralValue(profile.opt("policy_version")) != 1) return null
            return profile
        }

        @JvmStatic
        fun hasTrustedResolvedProfile(optimization: JSONObject?): Boolean {
            val fields = resolvedProfile(optimization)?.optJSONObject("fields") ?: return false
            val displayMode = validResolvedField(fields.optJSONObject("display_mode"))
                ?.opt("value") as? String ?: return false
            val width = strictIntegralValue(
                validResolvedField(fields.optJSONObject("display_width"))?.opt("value")
            ) ?: return false
            val height = strictIntegralValue(
                validResolvedField(fields.optJSONObject("display_height"))?.opt("value")
            ) ?: return false
            val targetFps = validResolvedField(fields.optJSONObject("target_fps"))
                ?.opt("value") as? Number ?: return false
            val bitrate = validResolvedField(fields.optJSONObject("target_bitrate_kbps"))
                ?.opt("value") as? Number ?: return false
            if (validResolvedField(fields.optJSONObject("hdr"))?.opt("value") !is Boolean) {
                return false
            }
            val modeParts = displayMode.split("x")
            if (modeParts.size != 3) return false
            val modeWidth = modeParts[0].toIntOrNull() ?: return false
            val modeHeight = modeParts[1].toIntOrNull() ?: return false
            val modeFps = modeParts[2].toDoubleOrNull() ?: return false
            val fps = targetFps.toDouble()
            val bitrateKbps = bitrate.toDouble()
            return width in 320..16384 && height in 240..16384 &&
                fps.isFinite() && fps in 15.0..240.0 &&
                modeWidth == width && modeHeight == height && modeFps.isFinite() &&
                kotlin.math.abs(modeFps - fps) <= 0.001 &&
                bitrateKbps.isFinite() && bitrateKbps in 1000.0..300000.0 &&
                bitrateKbps == kotlin.math.floor(bitrateKbps)
        }

        private fun validResolvedField(detail: JSONObject?): JSONObject? {
            detail ?: return null
            val source = detail.opt("source") as? String ?: return null
            val reasonCode = detail.opt("reason_code") as? String ?: return null
            if (source.isBlank() || source !in resolvedFieldSources ||
                reasonCode.isBlank() ||
                detail.opt("locked") !is Boolean ||
                detail.opt("normalized") !is Boolean ||
                !detail.has("value") || detail.isNull("value")
            ) {
                return null
            }
            return detail
        }

        private fun resolvedField(optimization: JSONObject?, name: String): Any? {
            val detail = validResolvedField(resolvedProfile(optimization)
                ?.optJSONObject("fields")
                ?.optJSONObject(name)) ?: return null
            val value = detail.opt("value")
            return value?.takeUnless { it === JSONObject.NULL }
        }

        private fun resolvedPreset(optimization: JSONObject?): String =
            normalized(resolvedProfile(optimization)?.optString("preset", "auto"))

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
            put(json, "metered_network", isMeteredNetwork(context))

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
        @JvmOverloads
        fun buildClientRuntime(
            context: Context,
            renderer: MediaCodecDecoderRenderer?,
            appliedRefreshRateHz: Float,
            displayModeId: Int,
            displayMode: String?,
            framePacing: Int,
            profile: ClientProfileProvenance? = null,
            targetRefreshRateHz: Float = 0f,
            refreshRatePolicy: String = ""
        ): JSONObject {
            val decoderName = renderer?.activeDecoderName ?: ""
            val json = ClientRuntimeSnapshot.fromAppliedStream(
                deviceModel = Build.MODEL ?: "",
                androidSdk = Build.VERSION.SDK_INT,
                decoder = decoderName,
                targetRefreshRateHz = targetRefreshRateHz.toDouble(),
                appliedRefreshRateHz = appliedRefreshRateHz.toDouble(),
                displayMode = displayMode ?: "",
                refreshRatePolicy = refreshRatePolicy,
                profile = profile ?: ClientProfileProvenance(ClientProfileSource.LOCAL_DEFAULT)
            ).toJson()
            put(json, "sync_mode", SYNC_MODE_AUTO_SAFE)
            put(json, "metered_network", isMeteredNetwork(context))
            put(json, "frame_pacing", framePacing)
            if (displayModeId > 0) put(json, "display_mode_id", displayModeId)
            if (renderer != null) {
                put(json, "active_decoder", renderer.activeDecoderName)
                put(json, "active_video_format", renderer.activeVideoFormat)
            }
            return json
        }

        @JvmStatic
        fun buildClientRuntimeSnapshotForTest(
            deviceModel: String,
            androidSdk: Int,
            decoder: String,
            targetRefreshRateHz: Double,
            appliedRefreshRateHz: Double,
            displayMode: String,
            refreshRatePolicy: String,
            profile: ClientProfileProvenance
        ): JSONObject = ClientRuntimeSnapshot.fromAppliedStream(
            deviceModel = deviceModel,
            androidSdk = androidSdk,
            decoder = decoder,
            targetRefreshRateHz = targetRefreshRateHz,
            appliedRefreshRateHz = appliedRefreshRateHz,
            displayMode = displayMode,
            refreshRatePolicy = refreshRatePolicy,
            profile = profile
        ).toJson()

        @JvmStatic
        @JvmOverloads
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
            displayModeExplicit: Boolean,
            preferredCodecOverride: String = "",
            streamDisplayMode: String = ""
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
            put(
                json,
                "preferred_codec",
                preferredCodecOverride.takeIf { it in setOf("h264", "hevc", "av1") }
                    ?: preferredCodec(videoFormat, supportedVideoFormats)
            )
            if (streamDisplayMode.isNotBlank()) put(json, "stream_display_mode", streamDisplayMode)
            return json
        }

        @JvmStatic
        fun recoveryLaunchProfile(optimization: JSONObject?): RecoveryLaunchProfile? {
            // Kept for binary/source compatibility with the v1 client model.
            // Recovery receipts remain visible and cancellable, but never form
            // a launch profile in Nova v1.3.9.
            return null
        }

        @JvmStatic
        fun restrictVideoFormatsForRecovery(
            supportedVideoFormats: Int,
            recovery: RecoveryLaunchProfile?
        ): Int = supportedVideoFormats

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

        @JvmStatic
        fun isMeteredNetwork(context: Context): Boolean {
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
