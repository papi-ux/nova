package com.papi.nova.ui

import com.papi.nova.manager.StreamSyncManager
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
            // A legacy/AI/history host response cannot be relabelled as a
            // deterministic launch contract merely because Nova overlays one
            // explicit display choice. Game rejects this marker before launch.
            put(
                "source",
                "nova_explicit_launch_unverified_v1"
            )
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
        // A resolution card owns width and height only. The planner's target_mode
        // includes a trailing rate because Polaris represents modes as WxHxF, but
        // importing that rate here silently turns a resolution choice into an FPS
        // choice. Keep the host-resolved cadence unless Tuning supplied an explicit
        // High FPS pin.
        val fps = fpsOverride ?: rawMode?.fps ?: fallbackFps

        val displayMode = "${width}x${height}x$fps"
        fun putField(name: String, value: Any, source: String, reason: String, locked: Boolean) {
            fields.put(name, JSONObject().apply {
                put("value", value)
                put("source", source)
                put("reason_code", reason)
                put("locked", locked)
                put("normalized", false)
            })
        }
        if (resolution != null) {
            putField("display_width", width, "explicit_launch_request", NORMALIZATION_REASON, true)
            putField("display_height", height, "explicit_launch_request", NORMALIZATION_REASON, true)
        }
        if (fpsOverride != null) {
            // Width/height retain the host's paired/preset provenance. Only the
            // explicitly pinned cadence becomes a client launch lock.
            putField("target_fps", fps.toDouble(), "explicit_launch_request", NORMALIZATION_REASON, true)
        }
        val allDisplayComponentsExplicit = resolution != null && fpsOverride != null
        fields.put("display_mode", JSONObject().apply {
            put("value", displayMode)
            put("source", if (allDisplayComponentsExplicit) "explicit_launch_request" else "composed_display_components")
            put("reason_code", if (allDisplayComponentsExplicit) NORMALIZATION_REASON else "mixed_display_provenance")
            put("locked", allDisplayComponentsExplicit)
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
        return StreamSyncManager.hasTrustedResolvedProfile(raw)
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
