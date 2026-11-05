package com.papi.nova.api

import org.json.JSONObject

/**
 * The host's own variable-refresh record for this device's stream: the optional `vrr` object of
 * GET /polaris/v1/session/status, contract objects session_vrr and session_vrr_host_pacing
 * (Polaris PR 625, still a draft when this was written).
 *
 * Everything here is the host speaking about its own side. effective means a host source-time
 * sample at most two seconds old; host_permission is permission, not proof; and the host never
 * claims the client presented anything or that a display adapted. Absent, unreadable and present
 * stay three different answers: an older host, or a stream without a record, says nothing, and
 * nothing is defaulted in its place.
 *
 * parse and parseHostPacing are the contract's declared Nova Android readers: they read exactly
 * the fields the contract lists for them, and Polaris's contract check holds them to that.
 */
data class PolarisSessionVrr(
    val schema: Int,
    val sessionGeneration: Long,
    /** The admitted request: true, false, malformed or absent. */
    val requested: String,
    /** launch or resume. */
    val requestedFrom: String,
    val hostPermission: Boolean,
    val effective: Boolean,
    /** fixed_grid, source_time_pending, unverified or source_time. */
    val pacingMode: String,
    /** Null exactly when effective, by the contract. */
    val fallbackReason: String?,
    val capturePacing: String,
    val hostPacing: HostPacing?,
) {
    /** The host's latest pacing receipt: waiting, source_time, latched_fallback or unsupported. */
    data class HostPacing(val fresh: Boolean, val state: String)

    /** What one status response said about the record. */
    sealed interface Reading {
        /** No vrr key: an older host, or no record for this stream. Unknown, not off. */
        object Absent : Reading

        /** A vrr value that is not a readable record. Unknown, and said to be unreadable. */
        data class Malformed(val why: String) : Reading

        data class Present(val record: PolarisSessionVrr) : Reading
    }

    /**
     * The host is source-pacing this stream now, by its own fresh receipt. This is the host's
     * side only: it never says the client presented on that cadence or that a display adapted.
     */
    val hostSourcePaced: Boolean
        get() = effective && fallbackReason == null && pacingMode == PACING_SOURCE_TIME &&
            hostPacing?.fresh == true && hostPacing.state == PACING_SOURCE_TIME

    companion object {
        const val SCHEMA = 1
        const val PACING_SOURCE_TIME = "source_time"

        /** Classify the raw `vrr` value of a status response (JSONObject.opt result). */
        @JvmStatic
        fun reading(value: Any?): Reading = when (value) {
            null -> Reading.Absent
            is JSONObject -> parse(value)?.let { Reading.Present(it) } ?: Reading.Malformed("unreadable vrr record")
            JSONObject.NULL -> Reading.Malformed("vrr is null")
            else -> Reading.Malformed("vrr is not an object")
        }

        /** One session_vrr object, or null when it does not have the contract's shape. */
        @JvmStatic
        fun parse(json: JSONObject?): PolarisSessionVrr? {
            if (json == null) return null
            val schema = json.opt("schema") as? Number ?: return null
            if (schema.toDouble() != SCHEMA.toDouble()) return null
            val generation = json.opt("session_generation") as? Number ?: return null
            if (generation.toDouble() != generation.toLong().toDouble() || generation.toLong() < 0) return null
            val requested = json.opt("requested") as? String ?: return null
            val requestedFrom = json.opt("requested_from") as? String ?: return null
            val hostPermission = json.opt("host_permission") as? Boolean ?: return null
            val effective = json.opt("effective") as? Boolean ?: return null
            val pacingMode = json.opt("pacing_mode") as? String ?: return null
            val capturePacing = json.opt("capture_pacing") as? String ?: return null
            val fallbackReason = when (val raw = json.opt("fallback_reason")) {
                JSONObject.NULL -> null
                is String -> raw
                else -> return null
            }
            val hostPacing = when (val raw = json.opt("host_pacing")) {
                null -> null
                is JSONObject -> parseHostPacing(raw) ?: return null
                else -> return null
            }
            return PolarisSessionVrr(
                schema = SCHEMA,
                sessionGeneration = generation.toLong(),
                requested = requested,
                requestedFrom = requestedFrom,
                hostPermission = hostPermission,
                effective = effective,
                pacingMode = pacingMode,
                fallbackReason = fallbackReason,
                capturePacing = capturePacing,
                hostPacing = hostPacing,
            )
        }

        /** One session_vrr_host_pacing object; only freshness and state are read. */
        @JvmStatic
        fun parseHostPacing(json: JSONObject?): HostPacing? {
            if (json == null) return null
            val fresh = json.opt("fresh") as? Boolean ?: return null
            val state = json.opt("state") as? String ?: return null
            return HostPacing(fresh, state)
        }
    }
}
