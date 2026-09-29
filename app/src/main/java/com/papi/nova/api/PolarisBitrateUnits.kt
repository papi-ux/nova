package com.papi.nova.api

import org.json.JSONObject

/** Session-owned conversion inputs, advertised by bitrate_units_v1. */
data class PolarisBitrateUnits(val requestedKbps: Int, val encoderKbps: Int, val liveEncoderKbps: Int,
    val audioKbps: Int, val fecPercent: Int) {
    companion object {
        fun parse(json: JSONObject?): PolarisBitrateUnits? {
            if (json == null || json.opt("version") != 1 || json.opt("formula") != "stream_bitrate_v1") return null
            fun integer(key: String, min: Int, max: Int): Int? = (json.opt(key) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in min.toDouble()..max.toDouble() }?.toInt()
            return PolarisBitrateUnits(integer("requested_kbps",1,Int.MAX_VALUE) ?: return null,
                integer("encoder_kbps",1,Int.MAX_VALUE) ?: return null,
                integer("live_encoder_kbps",1,Int.MAX_VALUE) ?: return null,
                integer("audio_kbps",0,100000) ?: return null, integer("fec_percentage",0,255) ?: return null)
        }
    }
}
