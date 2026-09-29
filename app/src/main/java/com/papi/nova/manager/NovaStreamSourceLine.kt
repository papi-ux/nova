package com.papi.nova.manager

import org.json.JSONObject

enum class NovaStreamSource { DEVICE, HOST_SAVED_COPY, HOST_CAP, HOST_POLICY, SPACE, WATCH, UNKNOWN }
data class NovaStreamSourceLine(val source: NovaStreamSource, val text: String,
    val capKbps: Int? = null) {
    companion object {
        fun fromPreflight(optimization: JSONObject): NovaStreamSourceLine {
            if (optimization.optString("source") == "worker_profile_v1") return space()
            val fields = optimization.optJSONObject("resolved_profile")
                ?.takeIf { it.optInt("policy_version") == 1 }?.optJSONObject("fields")
            val bitrate = fields?.optJSONObject("target_bitrate_kbps")
            val cap = (bitrate?.opt("value") as? Number)?.toInt()?.takeIf { it in 1..300000 }
            if (bitrate?.optString("reason_code") == "host_bitrate_cap" && cap != null)
                return NovaStreamSourceLine(NovaStreamSource.HOST_CAP,
                    "Host bitrate cap · ${com.papi.nova.preferences.NovaBitrateAdvice.text(cap, false)}", cap)
            val width = fields?.optJSONObject("display_width")
            val height = fields?.optJSONObject("display_height")
            if (width?.optString("source") == "paired_client" || height?.optString("source") == "paired_client") {
                val w = width?.optInt("value", 0) ?: 0; val h = height?.optInt("value", 0) ?: 0
                val size = if (w in 1..16384 && h in 1..16384) " · $w×$h" else ""
                return NovaStreamSourceLine(NovaStreamSource.HOST_SAVED_COPY, "Host's saved copy$size")
            }
            if (bitrate?.optString("source") == "paired_client" && cap != null)
                return NovaStreamSourceLine(NovaStreamSource.HOST_SAVED_COPY,
                    "Host's saved copy · ${com.papi.nova.preferences.NovaBitrateAdvice.text(cap, false)}", cap)
            val sources = fields?.keys()?.asSequence()?.mapNotNull { fields.optJSONObject(it)?.optString("source") }?.toSet().orEmpty()
            if (sources.any { it in setOf("client_launch_request", "explicit_launch_request") })
                return NovaStreamSourceLine(NovaStreamSource.DEVICE, "This device's request")
            if (sources.any { it in setOf("preset", "host_capability", "host_configuration", "capability_validation") })
                return NovaStreamSourceLine(NovaStreamSource.HOST_POLICY, "Host launch policy")
            return NovaStreamSourceLine(NovaStreamSource.UNKNOWN, "Stream source unavailable")
        }

        fun space() = NovaStreamSourceLine(NovaStreamSource.SPACE, "This Space · H.264 up to 8 Mbps", 8000)
        fun watch(width: Int, height: Int, fps: Int): NovaStreamSourceLine {
            val detail = if (width in 1..16384 && height in 1..16384 && fps in 1..1000)
                " · ${width}×$height at $fps fps" else ""
            return NovaStreamSourceLine(NovaStreamSource.WATCH, "Watching$detail")
        }
    }
}
