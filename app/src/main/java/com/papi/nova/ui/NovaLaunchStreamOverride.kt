package com.papi.nova.ui

import org.json.JSONObject
import kotlin.math.roundToInt

/** Composes explicit Nova launch choices into the deterministic v1 profile envelope. */
object NovaLaunchStreamOverride {

    const val NORMALIZATION_REASON = "nova_play_setup_override"

    /**
     * The client-side fps pin: Tuning = High FPS means the Settings frame rate,
     * guaranteed. The other preferences leave the host in control.
     */
    fun highFpsPin(preference: String, settingsFps: Float): Int? =
        if (preference.trim().lowercase() == "high_fps" && settingsFps > 0f) {
            settingsFps.roundToInt()
        } else {
            null
        }

    fun compose(
        raw: JSONObject?,
        resolution: NovaDisplayResolutionChoice?,
        fpsOverride: Int?,
        fallbackWidth: Int,
        fallbackHeight: Int,
        fallbackFps: Int,
    ): JSONObject? {
        if (resolution == null && fpsOverride == null) {
            return raw
        }

        val trustedRaw = raw?.takeIf(::isTrustedDeterministicEnvelope)
        val composed = trustedRaw?.let { JSONObject(it.toString()) } ?: JSONObject().apply {
            put("source", "nova_explicit_launch_v1")
            put("confidence", "deterministic")
            put("cache_status", "not_applicable")
            put("recommendation_version", 1)
        }
        val profile = composed.optJSONObject("resolved_profile") ?: JSONObject().also {
            composed.put("resolved_profile", it)
        }
        val fields = profile.optJSONObject("fields") ?: JSONObject().also {
            profile.put("fields", it)
        }
        val preset = profile.optString("preset", "").ifBlank {
            if (fpsOverride != null) "high_fps" else "auto"
        }
        profile.put("policy_version", 1)
        profile.put("preset", preset)
        composed.put("preset", preset)

        val rawMode = parseMode(
            fields.optJSONObject("display_mode")?.optString("value", "").orEmpty()
        )
        val chosenMode = parseMode(resolution?.targetMode.orEmpty())

        val width = chosenMode?.width ?: rawMode?.width ?: fallbackWidth
        val height = chosenMode?.height ?: rawMode?.height ?: fallbackHeight
        val fps = fpsOverride ?: chosenMode?.fps ?: rawMode?.fps ?: fallbackFps

        val displayMode = "${width}x${height}x$fps"
        fields.put("display_mode", JSONObject().apply {
            put("value", displayMode)
            put("source", "explicit_launch_request")
            put("reason_code", NORMALIZATION_REASON)
            put("locked", true)
            put("normalized", false)
        })
        // Top-level value is compatibility-only; StreamSync consumes the typed field.
        composed.put("display_mode", displayMode)
        composed.put("normalization_reason", NORMALIZATION_REASON)
        if (resolution != null) {
            composed.put("display_planner_choice", resolution.id)
        }
        return composed
    }

    private fun isTrustedDeterministicEnvelope(raw: JSONObject): Boolean {
        if (raw.optString("source", "").trim().lowercase() !in
            setOf("deterministic_preset_v1", "nova_explicit_launch_v1")
        ) {
            return false
        }
        val profile = raw.optJSONObject("resolved_profile") ?: return false
        return profile.optInt("policy_version", 0) == 1 && profile.optJSONObject("fields") != null
    }

    private data class Mode(val width: Int, val height: Int, val fps: Int)

    private fun parseMode(mode: String): Mode? {
        val parts = mode.trim().split('x', 'X')
        if (parts.size != 3) {
            return null
        }
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        val fps = parts[2].toFloatOrNull()?.roundToInt() ?: return null
        if (width <= 0 || height <= 0 || fps <= 0) {
            return null
        }
        return Mode(width, height, fps)
    }
}
