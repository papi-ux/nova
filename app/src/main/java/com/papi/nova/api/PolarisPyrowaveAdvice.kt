package com.papi.nova.api

import org.json.JSONObject

/** Host advice is already expressed as requested stream kbps, including link overhead. */
data class PolarisPyrowaveAdvice(val width: Int, val height: Int, val fps: Int,
    val raiseGoalKbps: Int, val capKbps: Int, val limitedBy: String) {
    val hostMaximumKbps: Int? get() = raiseGoalKbps.takeIf { limitedBy == "max_bitrate" }
    companion object {
        fun parse(json: JSONObject?): PolarisPyrowaveAdvice? {
            if (json == null || json.opt("version") != 1 ||
                (json.has("available") && json.opt("available") != true)) return null
            fun positive(key: String, max: Int): Int? = (json.opt(key) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..max.toDouble() }?.toInt()
            return PolarisPyrowaveAdvice(positive("width", 16384) ?: return null,
                positive("height", 16384) ?: return null, positive("fps", 1000) ?: return null,
                positive("raise_goal_kbps", 300000) ?: return null,
                positive("cap_kbps", 300000) ?: return null,
                json.optString("raise_goal_limited_by").takeIf { it in setOf("advice", "cap", "max_bitrate") } ?: return null)
        }
    }
}
