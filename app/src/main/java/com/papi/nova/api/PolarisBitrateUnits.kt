package com.papi.nova.api

import com.papi.nova.shared.polaris.contract.PolarisV1
import org.json.JSONObject

/** Session-owned conversion inputs. A null split means the formula was not applied. */
data class PolarisBitrateUnits(val requestedKbps: Int, val encoderKbps: Int, val liveEncoderKbps: Int,
    val audioKbps: Int, val fecPercent: Int,
    val splitKbps: Int? = null, val warpFactor: Int = 1,
    val capKbps: Int? = null, val capSource: String? = null) {
    companion object {
        fun parse(json: JSONObject?): PolarisBitrateUnits? {
            if (json == null || json.opt(PolarisV1.BitrateUnits.VERSION) != 1 || json.opt(PolarisV1.BitrateUnits.FORMULA) != "stream_bitrate_v1") return null
            fun integer(key: String, min: Int, max: Int): Int? = (json.opt(key) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in min.toDouble()..max.toDouble() }?.toInt()
            if (listOf(PolarisV1.BitrateUnits.SPLIT_KBPS, PolarisV1.BitrateUnits.CAP_KBPS, PolarisV1.BitrateUnits.CAP_SOURCE).any { !json.has(it) }) return null
            val split = if (json.isNull(PolarisV1.BitrateUnits.SPLIT_KBPS)) null else integer(PolarisV1.BitrateUnits.SPLIT_KBPS,1,Int.MAX_VALUE) ?: return null
            val cap = if (json.isNull(PolarisV1.BitrateUnits.CAP_KBPS)) null else integer(PolarisV1.BitrateUnits.CAP_KBPS,1,Int.MAX_VALUE) ?: return null
            val source = if (json.isNull(PolarisV1.BitrateUnits.CAP_SOURCE)) null else
                (json.opt(PolarisV1.BitrateUnits.CAP_SOURCE) as? String)?.takeIf { it.isNotBlank() } ?: return null
            if ((cap == null) != (source == null)) return null
            return PolarisBitrateUnits(integer(PolarisV1.BitrateUnits.REQUESTED_KBPS,0,Int.MAX_VALUE) ?: return null,
                integer(PolarisV1.BitrateUnits.ENCODER_KBPS,0,Int.MAX_VALUE) ?: return null,
                integer(PolarisV1.BitrateUnits.LIVE_ENCODER_KBPS,0,Int.MAX_VALUE) ?: return null,
                integer(PolarisV1.BitrateUnits.AUDIO_KBPS,0,100000) ?: return null, integer(PolarisV1.BitrateUnits.FEC_PERCENTAGE,0,255) ?: return null,
                split, integer(PolarisV1.BitrateUnits.WARP_FACTOR,1,Int.MAX_VALUE) ?: return null, cap, source)
        }
    }
}
