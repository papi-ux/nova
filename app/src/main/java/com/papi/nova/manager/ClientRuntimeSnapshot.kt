package com.papi.nova.manager

import org.json.JSONObject
import java.util.Locale

/**
 * Compact client-side truth Nova can report to Polaris and show in debug UI.
 */
enum class ClientProfileSource(val wireValue: String, val displayLabel: String) {
    LOCAL_DEFAULT("local_default", "Local defaults"),
    DETERMINISTIC_PRESET("deterministic_preset_v1", "Deterministic preset"),
    EXPLICIT_LAUNCH("nova_explicit_launch_v1", "Explicit launch"),
    POLARIS_LIVE("polaris_live", "Polaris live"),
    POLARIS_CACHED("polaris_cached", "Polaris cached"),
    HISTORY_SAFE("history_safe", "History safe"),
    MANUAL_OVERRIDE("manual_override", "Manual override"),
    UNKNOWN("unknown", "Unknown")
}

data class ClientProfileProvenance(
    val source: ClientProfileSource,
    val sourceValue: String = source.wireValue,
    val version: Int = 0,
    val hash: String = "",
    val confidence: String = "",
    val cacheStatus: String = "",
    val manualOverride: Boolean = false
) {
    val displayLabel: String
        get() = source.displayLabel

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source.wireValue)
        put("source_value", sourceValue.ifBlank { source.wireValue })
        put("manual_override", manualOverride)
        if (version > 0) put("version", version)
        if (hash.isNotBlank()) put("hash", hash)
        if (confidence.isNotBlank()) put("confidence", confidence)
        if (cacheStatus.isNotBlank()) put("cache_status", cacheStatus)
        put("display_label", displayLabel)
    }

    companion object {
        @JvmStatic
        fun fromOptimization(optimization: JSONObject?, manualOverride: Boolean): ClientProfileProvenance {
            val rawSource = optimization?.optString("source", "")?.trim().orEmpty()
            val sourceValue = rawSource.ifBlank {
                if (manualOverride) ClientProfileSource.MANUAL_OVERRIDE.wireValue else ClientProfileSource.LOCAL_DEFAULT.wireValue
            }
            val mappedSource = if (manualOverride) {
                ClientProfileSource.MANUAL_OVERRIDE
            } else {
                sourceFromWireValue(sourceValue)
            }

            return ClientProfileProvenance(
                source = mappedSource,
                sourceValue = sourceValue,
                version = optimization?.optInt("recommendation_version", 0) ?: 0,
                hash = firstNonBlank(
                    optimization?.optString("recommendation_hash", ""),
                    optimization?.optString("profile_hash", ""),
                    optimization?.optString("hash", "")
                ),
                confidence = optimization?.optString("confidence", "")?.trim().orEmpty(),
                cacheStatus = optimization?.optString("cache_status", "")?.trim().orEmpty(),
                manualOverride = manualOverride
            )
        }

        @JvmStatic
        fun sourceFromWireValue(value: String?): ClientProfileSource {
            val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
            return when {
                normalized.isEmpty() || normalized == "local" || normalized == "local_default" ->
                    ClientProfileSource.LOCAL_DEFAULT
                normalized == "deterministic_preset_v1" -> ClientProfileSource.DETERMINISTIC_PRESET
                normalized == "nova_explicit_launch_v1" -> ClientProfileSource.EXPLICIT_LAUNCH
                normalized == "polaris_live" || normalized == "ai_live" || normalized == "live" ||
                    normalized == "ai_optimizer" || normalized == "optimizer" ->
                    ClientProfileSource.POLARIS_LIVE
                normalized == "polaris_cached" || normalized == "ai_cached" || normalized == "cached" ->
                    ClientProfileSource.POLARIS_CACHED
                normalized.contains("history_safe") || normalized == "recovery" || normalized == "safe" ->
                    ClientProfileSource.HISTORY_SAFE
                normalized == "manual_override" || normalized == "manual" ->
                    ClientProfileSource.MANUAL_OVERRIDE
                else -> ClientProfileSource.UNKNOWN
            }
        }

        private fun firstNonBlank(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }
}

data class ClientRuntimeSnapshot(
    val deviceModel: String,
    val androidSdk: Int,
    val decoder: String = "",
    val targetRefreshRateHz: Double = 0.0,
    val appliedRefreshRateHz: Double = 0.0,
    val displayMode: String = "",
    val refreshRatePolicy: String = "",
    val profile: ClientProfileProvenance = ClientProfileProvenance(ClientProfileSource.LOCAL_DEFAULT)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_model", deviceModel)
        if (androidSdk > 0) put("android_sdk", androidSdk)
        if (decoder.isNotBlank()) put("decoder", decoder)
        if (targetRefreshRateHz > 0.0) put("target_refresh_rate_hz", targetRefreshRateHz)
        if (appliedRefreshRateHz > 0.0) put("applied_refresh_rate_hz", appliedRefreshRateHz)
        if (displayMode.isNotBlank()) put("display_mode", displayMode)
        if (refreshRatePolicy.isNotBlank()) put("refresh_rate_policy", refreshRatePolicy)
        put("profile", profile.toJson())
    }

    companion object {
        @JvmStatic
        fun fromAppliedStream(
            deviceModel: String,
            androidSdk: Int,
            decoder: String = "",
            targetRefreshRateHz: Double = 0.0,
            appliedRefreshRateHz: Double = 0.0,
            displayMode: String = "",
            refreshRatePolicy: String = "",
            profile: ClientProfileProvenance = ClientProfileProvenance(ClientProfileSource.LOCAL_DEFAULT)
        ): ClientRuntimeSnapshot = ClientRuntimeSnapshot(
            deviceModel = deviceModel,
            androidSdk = androidSdk,
            decoder = decoder,
            targetRefreshRateHz = targetRefreshRateHz,
            appliedRefreshRateHz = appliedRefreshRateHz,
            displayMode = displayMode,
            refreshRatePolicy = refreshRatePolicy,
            profile = profile
        )
    }
}
