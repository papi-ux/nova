package com.papi.nova.api

import org.json.JSONObject

/** Session-owned conversion inputs. A null split means the formula was not applied. */
data class PolarisBitrateUnits(val requestedKbps: Int, val encoderKbps: Int, val liveEncoderKbps: Int,
    val audioKbps: Int, val fecPercent: Int,
    val splitKbps: Int? = null, val warpFactor: Int = 1,
    val capKbps: Int? = null, val capSource: String? = null) {
    companion object {
        fun parse(json: JSONObject?): PolarisBitrateUnits? {
            if (json == null || json.opt("version") != 1 || json.opt("formula") != "stream_bitrate_v1") return null
            fun integer(key: String, min: Int, max: Int): Int? = (json.opt(key) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in min.toDouble()..max.toDouble() }?.toInt()
            if (listOf("split_kbps", "cap_kbps", "cap_source").any { !json.has(it) }) return null
            val split = if (json.isNull("split_kbps")) null else integer("split_kbps",1,Int.MAX_VALUE) ?: return null
            val cap = if (json.isNull("cap_kbps")) null else integer("cap_kbps",1,Int.MAX_VALUE) ?: return null
            val source = if (json.isNull("cap_source")) null else
                (json.opt("cap_source") as? String)?.takeIf { it.isNotBlank() } ?: return null
            if ((cap == null) != (source == null)) return null
            return PolarisBitrateUnits(integer("requested_kbps",0,Int.MAX_VALUE) ?: return null,
                integer("encoder_kbps",0,Int.MAX_VALUE) ?: return null,
                integer("live_encoder_kbps",0,Int.MAX_VALUE) ?: return null,
                integer("audio_kbps",0,100000) ?: return null, integer("fec_percentage",0,255) ?: return null,
                split, integer("warp_factor",1,Int.MAX_VALUE) ?: return null, cap, source)
        }
    }
}
