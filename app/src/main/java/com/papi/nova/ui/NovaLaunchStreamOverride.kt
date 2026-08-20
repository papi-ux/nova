package com.papi.nova.ui

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Composes the one optimization blob a launch goes out with.
 *
 * A pick here used to replace the host's /optimize blob with a synthetic one, which
 * silently dropped the stability block -- and with it the recovery clamp -- as a side
 * effect of choosing a resolution. Composing over a deep copy keeps everything the host
 * said and changes only what was actually chosen:
 *  - a resolution pick pins width x height and leaves the fps clamp standing;
 *  - an fps pin (Tuning = High FPS) pins the rate and releases the safe-target clamp
 *    explicitly, through the safe_target_fps_relaxed field the resolver already honors
 *    -- an informed override rather than an accident of blob replacement.
 *
 * The fps that launches must always be re-derivable from the blob that launches:
 * whoever calls this must hand the SAME composed blob to both the stream-fps
 * resolution and the launch intent, or Game.kt's re-resolution will disagree with
 * the fps it was given.
 */
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

        val composed = raw?.let { JSONObject(it.toString()) } ?: JSONObject()
        val rawMode = parseMode(composed.optString("display_mode", ""))
        val chosenMode = parseMode(resolution?.targetMode.orEmpty())

        val width = chosenMode?.width ?: rawMode?.width ?: fallbackWidth
        val height = chosenMode?.height ?: rawMode?.height ?: fallbackHeight
        val fps = fpsOverride ?: chosenMode?.fps ?: rawMode?.fps ?: fallbackFps

        composed.put("display_mode", "${width}x${height}x$fps")
        composed.put("paired_profile_applied", true)
        composed.put("normalization_reason", NORMALIZATION_REASON)
        if (resolution != null) {
            composed.put("display_planner_choice", resolution.id)
        }
        if (fpsOverride != null) {
            composed.put("safe_target_fps_relaxed", true)
            composed.put("effective_target_fps", fpsOverride.toDouble())
        }
        return composed
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
