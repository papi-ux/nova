package com.papi.nova.network

import org.json.JSONObject

/**
 * What the paired Polaris host says about the TLS control connection Nova used to ask it
 * (`GET /polaris/v1/network/connection`, schema 1). It describes that host connection only:
 * never the UDP video/audio path, which stays unknown.
 *
 * Only a fresh, supported, internally consistent answer can say "Tailscale". A generic Android
 * VPN, a shared-range address, a MagicDNS-looking name or the Tailscale app being installed are
 * never evidence here.
 */
data class HostConnectionEvidence(
    val status: Status,
    /** Meaningful only when [status] is [Status.TAILSCALE]; otherwise always [Transport.UNKNOWN]. */
    val transport: Transport,
    /** A fixed safe reason; never a raw error, address or identity. */
    val reason: Reason,
) {
    enum class Status { UNKNOWN, TAILSCALE, NOT_TAILSCALE }

    enum class Transport { UNKNOWN, DIRECT, DERP, PEER_RELAY }

    enum class Reason {
        // Host-reported reasons, from the frozen contract's enum.
        UNSUPPORTED_PLATFORM, LOCALAPI_UNAVAILABLE, MALFORMED_STATUS, PEER_NOT_CONFIRMED, PEER_IDLE,
        TRANSPORT_UNKNOWN, OBSERVED,
        // Client-side reasons for falling back to unknown.
        HOST_UNSUPPORTED, REQUEST_FAILED, UNSUPPORTED_SCHEMA, INCONSISTENT, STALE, FROM_THE_FUTURE,
        UNRECOGNIZED,
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val SCOPE_CONTROL_CONNECTION = "control_connection"
        /** The contract's freshness window; a longer declared window is not trusted. */
        const val MAX_AGE_MS = 10_000L
        /** How far ahead of this device's wall clock an observation may be before it is refused. */
        const val MAX_FUTURE_SKEW_MS = 2_000L
        /** Answers larger than this are not parsed. */
        const val MAX_BODY_CHARS = 16 * 1024

        @JvmStatic
        fun unknown(reason: Reason) = HostConnectionEvidence(Status.UNKNOWN, Transport.UNKNOWN, reason)

        private val hostReasons = mapOf(
            "unsupported_platform" to Reason.UNSUPPORTED_PLATFORM,
            "localapi_unavailable" to Reason.LOCALAPI_UNAVAILABLE,
            "malformed_status" to Reason.MALFORMED_STATUS,
            "peer_not_confirmed" to Reason.PEER_NOT_CONFIRMED,
            "peer_idle" to Reason.PEER_IDLE,
            "transport_unknown" to Reason.TRANSPORT_UNKNOWN,
            "observed" to Reason.OBSERVED,
        )

        private val transports = mapOf(
            "unknown" to Transport.UNKNOWN,
            "direct" to Transport.DIRECT,
            "derp" to Transport.DERP,
            "peer_relay" to Transport.PEER_RELAY,
        )

        /** Parse a response body; anything unreadable or oversized is unknown, never an exception. */
        @JvmStatic
        fun parseBody(body: String?, nowWallMs: Long): HostConnectionEvidence {
            if (body == null || body.length > MAX_BODY_CHARS) return unknown(Reason.REQUEST_FAILED)
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return unknown(Reason.UNSUPPORTED_SCHEMA)
            }
            return parse(json, nowWallMs)
        }

        /**
         * Validate schema 1 strictly and decide what may be shown. [nowWallMs] is this device's wall
         * clock, which the host's `observed_at_ms` (also wall clock) is compared with.
         */
        @JvmStatic
        fun parse(json: JSONObject, nowWallMs: Long): HostConnectionEvidence {
            val schema = json.opt("schema_version")
            if (schema !is Number || schema.toDouble() != SCHEMA_VERSION.toDouble()) return unknown(Reason.UNSUPPORTED_SCHEMA)
            val status = when (json.opt("status")) {
                "unknown" -> Status.UNKNOWN
                "tailscale" -> Status.TAILSCALE
                "not_tailscale" -> Status.NOT_TAILSCALE
                else -> return unknown(Reason.UNSUPPORTED_SCHEMA)
            }
            val tailscale = json.optJSONObject("tailscale") ?: return unknown(Reason.UNSUPPORTED_SCHEMA)
            val hostReason = (tailscale.opt("reason") as? String)?.let { hostReasons[it] } ?: Reason.UNRECOGNIZED
            if (status == Status.UNKNOWN) return unknown(hostReason)

            val confirmed = tailscale.opt("confirmed") as? Boolean ?: return unknown(Reason.UNSUPPORTED_SCHEMA)
            if (tailscale.opt("scope") != SCOPE_CONTROL_CONNECTION) return unknown(Reason.UNSUPPORTED_SCHEMA)
            val transport = (tailscale.opt("transport") as? String)?.let { transports[it] }
                ?: return unknown(Reason.UNSUPPORTED_SCHEMA)
            val observedAt = (tailscale.opt("observed_at_ms") as? Number)?.toLong() ?: return unknown(Reason.UNSUPPORTED_SCHEMA)
            val maxAge = (tailscale.opt("max_age_ms") as? Number)?.toLong() ?: return unknown(Reason.UNSUPPORTED_SCHEMA)
            if (observedAt <= 0L || maxAge <= 0L) return unknown(Reason.UNSUPPORTED_SCHEMA)

            // Only "tailscale" with confirmed=true, or "not_tailscale" with confirmed=false, is consistent.
            if ((status == Status.TAILSCALE) != confirmed) return unknown(Reason.INCONSISTENT)

            val window = minOf(maxAge, MAX_AGE_MS)
            if (observedAt > nowWallMs) {
                if (observedAt - nowWallMs > MAX_FUTURE_SKEW_MS) return unknown(Reason.FROM_THE_FUTURE)
            } else if (nowWallMs - observedAt > window) {
                return unknown(Reason.STALE)
            }
            return if (status == Status.TAILSCALE) {
                HostConnectionEvidence(Status.TAILSCALE, transport, hostReason)
            } else {
                HostConnectionEvidence(Status.NOT_TAILSCALE, Transport.UNKNOWN, hostReason)
            }
        }
    }
}

/**
 * A raw answer kept so it can be judged again later (before launch) without fetching: the
 * host's observation must still be inside its freshness window at that later moment.
 */
data class HostConnectionObservation(val body: String?, val supported: Boolean) {
    fun evaluate(nowWallMs: Long): HostConnectionEvidence =
        if (!supported) HostConnectionEvidence.unknown(HostConnectionEvidence.Reason.HOST_UNSUPPORTED)
        else HostConnectionEvidence.parseBody(body, nowWallMs)
}
