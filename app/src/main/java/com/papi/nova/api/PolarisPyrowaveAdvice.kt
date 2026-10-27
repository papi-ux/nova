package com.papi.nova.api

import com.papi.nova.shared.polaris.contract.PolarisV1
import org.json.JSONObject
import com.papi.nova.preferences.NovaBitrateAdvice

/** Host advice is already expressed as requested stream kbps, including link overhead. */
data class PolarisPyrowaveAdvice(val width: Int, val height: Int, val fps: Int,
    val raiseGoalKbps: Int, val capKbps: Int, val limitedBy: String,
    val fecPercent: Int = 10, val audioKbps: Int = 512, val assumptionsKnown: Boolean = true,
    val manualMaximumKbps: Int? = null) {
    val hostMaximumKbps: Int? get() = manualMaximumKbps ?: raiseGoalKbps.takeIf { limitedBy == "max_bitrate" }
    companion object {
        fun parse(json: JSONObject?): PolarisPyrowaveAdvice? {
            if (json == null || json.opt(PolarisV1.PyrowaveAdvice.VERSION) != 1 ||
                (json.has(PolarisV1.PyrowaveAdvice.AVAILABLE) && json.opt(PolarisV1.PyrowaveAdvice.AVAILABLE) != true)) return null
            fun positive(key: String, max: Int): Int? = (json.opt(key) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..max.toDouble() }?.toInt()
            val assumes = json.optJSONObject(PolarisV1.PyrowaveAdvice.ASSUMES)
            fun assumption(key: String, default: Int, max: Int): Int? {
                if (assumes == null || !assumes.has(key)) return default
                val value=assumes.opt(key) as? Number ?: return null
                return value.toDouble().takeIf { it.isFinite() && it%1.0==0.0 && it in 0.0..max.toDouble() }?.toInt()
            }
            val goal = positive(PolarisV1.PyrowaveAdvice.RAISE_GOAL_KBPS, NovaBitrateAdvice.MANUAL_MAX_KBPS) ?: return null
            val cap = positive(PolarisV1.PyrowaveAdvice.CAP_KBPS, NovaBitrateAdvice.MANUAL_MAX_KBPS) ?: return null
            val limited = json.optString(PolarisV1.PyrowaveAdvice.RAISE_GOAL_LIMITED_BY)
                .takeIf { it in setOf("advice", "cap", "max_bitrate") } ?: return null
            return PolarisPyrowaveAdvice(positive(PolarisV1.PyrowaveAdvice.WIDTH, 16384) ?: return null,
                positive(PolarisV1.PyrowaveAdvice.HEIGHT, 16384) ?: return null, positive(PolarisV1.PyrowaveAdvice.FPS, 1000) ?: return null,
                minOf(goal, cap, NovaBitrateAdvice.AUTOMATIC_MAX_KBPS), cap, limited,
                assumption("fec_percentage",10,255) ?: return null,
                assumption("audio_kbps",512,100000) ?: return null,
                assumes?.has("audio_kbps") == true && assumes.has("fec_percentage"),
                goal.takeIf { limited == "max_bitrate" })
        }
    }
}
