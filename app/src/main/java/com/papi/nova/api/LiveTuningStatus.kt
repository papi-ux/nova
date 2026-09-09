package com.papi.nova.api

import org.json.JSONObject

/** Host-owned preference and encoder acknowledgement. AI readiness is independent. */
data class LiveTuningStatus(
    val enabled: Boolean,
    val state: String,
    val supported: Boolean,
    val reason: String,
    val qualityLimitKbps: Int,
    val requestedBitrateKbps: Int,
    val appliedBitrateKbps: Int,
    val configurationRevision: String,
    val hostInstance: String,
    val sequence: Long,
    val sessionGeneration: Long,
    val appSessionId: String,
) {
    companion object {
        private fun integer(json: JSONObject, key: String, min: Long, max: Long): Boolean {
            val value = json.opt(key)
            return (value is Int || value is Long) && (value as Number).toLong() in min..max
        }
        fun parse(json: JSONObject?): LiveTuningStatus? {
            if (json == null || json.opt("version") != 1 || json.opt("enabled") !is Boolean ||
                json.opt("supported") !is Boolean || json.optString("scope") != "host" ||
                !json.optString("configuration_revision").matches(Regex("[a-fA-F0-9]{64}")) ||
                json.opt("host_instance") !is String || json.optString("host_instance").isBlank() ||
                !integer(json, "sequence", 1, 9007199254740991L) ||
                !integer(json, "session_generation", 0, 9007199254740991L) ||
                listOf("quality_limit_kbps", "requested_bitrate_kbps", "applied_bitrate_kbps").any { !integer(json, it, 0, Int.MAX_VALUE.toLong()) } ||
                json.opt("app_session_id") !is String ||
                json.optString("state") !in setOf("off", "waiting", "unavailable", "applying", "measuring", "adjusting", "stable")) return null
            return LiveTuningStatus(
                json.getBoolean("enabled"), json.getString("state"), json.getBoolean("supported"),
                json.optString("reason"), json.optInt("quality_limit_kbps"),
                json.optInt("requested_bitrate_kbps"), json.optInt("applied_bitrate_kbps"),
                json.getString("configuration_revision"), json.getString("host_instance"),
                json.getLong("sequence"), json.optLong("session_generation"), json.optString("app_session_id")
            )
        }
    }
}

/** One reducer per pinned host client. Discard delayed observations and old host instances. */
class LiveTuningReducer {
    private var current: LiveTuningStatus? = null
    private val retired = mutableSetOf<String>()
    @Synchronized fun accept(next: LiveTuningStatus): LiveTuningStatus? {
        if (next.hostInstance in retired) return null
        val previous = current
        if (previous?.hostInstance == next.hostInstance && next.sequence <= previous.sequence) return null
        if (previous != null && previous.hostInstance != next.hostInstance) retired.add(previous.hostInstance)
        current = next
        return next
    }
}
