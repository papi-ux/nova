package com.papi.nova.ui

import android.content.res.Resources
import com.papi.nova.R
import com.papi.nova.manager.StreamSyncManager
import org.json.JSONObject

/**
 * The preset a launch was resolved to, as the HUD's stream line names it: "Stream 120 • Quality
 * preset". The HUD said "Auto profile" through a Quality launch (in-game #11).
 */
object NovaLaunchPresetLabel {
    /**
     * The preset in the host's resolved profile for [optimization], in the words Tuning uses for it,
     * or "" when the launch has none a player chose: Auto, a Space's contract, a preset Nova does not
     * know, or a launch that was not resolved and [trusted]. It reads the profile's preset key,
     * never the host's prose about it, and never a preference that can change after the launch.
     */
    fun resolved(resources: Resources, optimization: JSONObject?, trusted: Boolean): String {
        if (!trusted) return ""
        return when (StreamSyncManager.resolvedLaunchPreset(optimization)) {
            "quality" -> resources.getString(R.string.nova_auto_quality_preference_quality)
            "high_fps" -> resources.getString(R.string.nova_auto_quality_preference_high_fps)
            "stability" -> resources.getString(R.string.nova_auto_quality_preference_stability)
            else -> ""
        }
    }
}
