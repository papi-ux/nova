package com.papi.nova.network

import com.papi.nova.network.HostConnectionEvidence.Reason
import com.papi.nova.network.HostConnectionEvidence.Status
import com.papi.nova.network.HostConnectionEvidence.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The frozen schema-1 contract for GET /polaris/v1/network/connection, judged on this device. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class HostConnectionEvidenceTest {
    private val now = 1_800_000_000_000L

    private fun body(
        status: String = "tailscale",
        confirmed: Any? = true,
        transport: Any? = "direct",
        scope: Any? = "control_connection",
        observedAt: Any? = now - 1_000L,
        maxAge: Any? = 10_000L,
        reason: Any? = "observed",
        schema: Any? = 1,
        extra: (JSONObject) -> Unit = {},
    ): JSONObject {
        val tailscale = JSONObject()
            .put("confirmed", confirmed ?: JSONObject.NULL)
            .put("transport", transport ?: JSONObject.NULL)
            .put("scope", scope ?: JSONObject.NULL)
            .put("source", "tailscaled_localapi")
            .put("observed_at_ms", observedAt ?: JSONObject.NULL)
            .put("max_age_ms", maxAge ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL)
        return JSONObject()
            .put("schema_version", schema ?: JSONObject.NULL)
            .put("status", status)
            .put("address_kind", "cgnat")
            .put("tailscale", tailscale)
            .also(extra)
    }

    private fun parse(json: JSONObject, at: Long = now) = HostConnectionEvidence.parse(json, at)

    @Test fun aFreshConfirmedAnswerShowsEachTransport() {
        assertEquals(HostConnectionEvidence(Status.TAILSCALE, Transport.DIRECT, Reason.OBSERVED), parse(body()))
        assertEquals(Transport.DERP, parse(body(transport = "derp")).transport)
        assertEquals(Transport.PEER_RELAY, parse(body(transport = "peer_relay")).transport)
    }

    @Test fun aConfirmedConnectionMayStillHaveAnUnknownTransport() {
        val evidence = parse(body(transport = "unknown", reason = "transport_unknown"))
        assertEquals(Status.TAILSCALE, evidence.status)
        assertEquals(Transport.UNKNOWN, evidence.transport)
        assertEquals(Reason.TRANSPORT_UNKNOWN, evidence.reason)
    }

    @Test fun notTailscaleIsShownOnlyWhenFreshAndUnconfirmed() {
        val evidence = parse(body(status = "not_tailscale", confirmed = false, transport = "unknown", reason = "peer_not_confirmed"))
        assertEquals(HostConnectionEvidence(Status.NOT_TAILSCALE, Transport.UNKNOWN, Reason.PEER_NOT_CONFIRMED), evidence)
    }

    @Test fun notTailscaleNeverCarriesATransportEvenIfOneIsSent() {
        assertEquals(Transport.UNKNOWN, parse(body(status = "not_tailscale", confirmed = false, transport = "derp")).transport)
    }

    @Test fun theAddressKindAloneNeverConfirmsTailscale() {
        // "cgnat" is sent in every body here; it must not lift unknown or not_tailscale.
        assertEquals(Status.UNKNOWN, parse(body(status = "unknown", confirmed = false, reason = "localapi_unavailable")).status)
        assertEquals(Status.NOT_TAILSCALE, parse(body(status = "not_tailscale", confirmed = false)).status)
    }

    @Test fun unknownCarriesTheHostsReasonAndNeverATransport() {
        val evidence = parse(body(status = "unknown", confirmed = false, transport = "direct", reason = "localapi_unavailable"))
        assertEquals(HostConnectionEvidence(Status.UNKNOWN, Transport.UNKNOWN, Reason.LOCALAPI_UNAVAILABLE), evidence)
        assertEquals(Reason.UNSUPPORTED_PLATFORM, parse(body(status = "unknown", reason = "unsupported_platform")).reason)
        assertEquals(Reason.PEER_IDLE, parse(body(status = "unknown", reason = "peer_idle")).reason)
        assertEquals(Reason.UNRECOGNIZED, parse(body(status = "unknown", reason = "something_new")).reason)
    }

    @Test fun contradictionsFallBackToUnknown() {
        assertEquals(HostConnectionEvidence.unknown(Reason.INCONSISTENT), parse(body(confirmed = false)))
        assertEquals(HostConnectionEvidence.unknown(Reason.INCONSISTENT), parse(body(status = "not_tailscale", confirmed = true)))
    }

    @Test fun anyUnsupportedSchemaOrShapeIsUnknown() {
        val unsupported = HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA)
        assertEquals(unsupported, parse(body(schema = 2)))
        assertEquals(unsupported, parse(body(schema = null)))
        assertEquals(unsupported, parse(body(schema = "1")))
        assertEquals(unsupported, parse(body(status = "TAILSCALE")))
        assertEquals(unsupported, parse(body(scope = "media_path")))
        assertEquals(unsupported, parse(body(scope = null)))
        assertEquals(unsupported, parse(body(transport = "wireguard")))
        assertEquals(unsupported, parse(body(confirmed = "true")))
        assertEquals(unsupported, parse(body(observedAt = null)))
        assertEquals(unsupported, parse(body(observedAt = 0L)))
        assertEquals(unsupported, parse(body(maxAge = 0L)))
        assertEquals(unsupported, parse(body(maxAge = -5L)))
        assertEquals(unsupported, parse(JSONObject().put("schema_version", 1).put("status", "tailscale")))
    }

    @Test fun freshnessUsesTheShorterOfTheDeclaredWindowAndTheContractWindow() {
        assertEquals(Status.TAILSCALE, parse(body(observedAt = now - 10_000L)).status)
        assertEquals(HostConnectionEvidence.unknown(Reason.STALE), parse(body(observedAt = now - 10_001L)))
        // A host declaring a longer window is not trusted past the contract's 10 s.
        assertEquals(HostConnectionEvidence.unknown(Reason.STALE), parse(body(observedAt = now - 20_000L, maxAge = 60_000L)))
        // A shorter declared window is honoured.
        assertEquals(HostConnectionEvidence.unknown(Reason.STALE), parse(body(observedAt = now - 3_000L, maxAge = 2_000L)))
    }

    @Test fun observationsFromTheFutureAreBoundedBySkew() {
        assertEquals(Status.TAILSCALE, parse(body(observedAt = now + 2_000L)).status)
        assertEquals(HostConnectionEvidence.unknown(Reason.FROM_THE_FUTURE), parse(body(observedAt = now + 2_001L)))
    }

    @Test fun aKeptAnswerBecomesUnknownOnceItAges() {
        val observation = HostConnectionObservation(body().toString(), supported = true)
        assertEquals(Status.TAILSCALE, observation.evaluate(now).status)
        assertEquals(HostConnectionEvidence.unknown(Reason.STALE), observation.evaluate(now + 10_000L))
    }

    @Test fun oldHostsMissingCapabilityAndFailuresAreSupportedUnknowns() {
        assertEquals(HostConnectionEvidence.unknown(Reason.HOST_UNSUPPORTED),
            HostConnectionObservation(body().toString(), supported = false).evaluate(now))
        assertEquals(HostConnectionEvidence.unknown(Reason.REQUEST_FAILED),
            HostConnectionObservation(null, supported = true).evaluate(now))
        assertEquals(HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA), HostConnectionEvidence.parseBody("not json", now))
        assertEquals(HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA), HostConnectionEvidence.parseBody("[]", now))
        val oversized = body(extra = { it.put("pad", "x".repeat(HostConnectionEvidence.MAX_BODY_CHARS)) }).toString()
        assertEquals(HostConnectionEvidence.unknown(Reason.REQUEST_FAILED), HostConnectionEvidence.parseBody(oversized, now))
    }

    @Test fun nothingIdentifyingInTheBodyReachesTheResult() {
        val identifying = body(extra = {
            it.put("address", "100.101.102.103").put("hostname", "gaming-pc.tail0000.ts.net")
            it.getJSONObject("tailscale").put("node_id", "nSECRET").put("endpoint", "203.0.113.9:41641").put("relay", "mia")
        })
        val evidence = parse(identifying)
        assertEquals(Status.TAILSCALE, evidence.status)
        val shown = evidence.toString()
        listOf("100.101", "ts.net", "nSECRET", "203.0.113", "mia", "gaming-pc").forEach {
            assertFalse("result must not carry $it", shown.contains(it))
        }
    }
}
