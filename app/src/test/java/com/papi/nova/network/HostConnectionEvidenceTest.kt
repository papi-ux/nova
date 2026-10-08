package com.papi.nova.network

import com.papi.nova.network.HostConnectionEvidence.Reason
import com.papi.nova.network.HostConnectionEvidence.Status
import com.papi.nova.network.HostConnectionEvidence.Transport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun theSchemaVersionAndTimestampsMustBeExactIntegers() {
        val unsupported = HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA)
        assertEquals(Status.TAILSCALE, parse(body(schema = 1L)).status)
        assertEquals(unsupported, parse(body(schema = 1.0)))
        assertEquals(unsupported, parse(body(schema = true)))
        assertEquals(unsupported, parse(body(observedAt = (now - 1_000L).toDouble())))
        assertEquals(unsupported, parse(body(maxAge = 10_000.0)))
        assertEquals(unsupported, parse(body(maxAge = "10000")))
        // The same rules hold for the text the host actually sends.
        assertEquals(unsupported, HostConnectionEvidence.parseBody(body().toString().replace("\"schema_version\":1", "\"schema_version\":1.0"), now))
        assertEquals(Status.TAILSCALE, HostConnectionEvidence.parseBody(body().toString(), now).status)
    }

    @Test fun theSourceMustBeTheHostsTailscaledLocalApi() {
        val unsupported = HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA)
        listOf<Any?>(null, "", "client_hint", "TAILSCALED_LOCALAPI", 1).forEach { source ->
            val json = body().also { it.getJSONObject("tailscale").put("source", source ?: JSONObject.NULL) }
            assertEquals(source.toString(), unsupported, parse(json))
        }
        val missing = body().also { it.getJSONObject("tailscale").remove("source") }
        assertEquals(unsupported, parse(missing))
        assertEquals(unsupported, parse(body(status = "not_tailscale", confirmed = false).also {
            it.getJSONObject("tailscale").put("source", "guess")
        }))
    }

    @Test fun aClaimWithAReasonOutsideTheEnumIsNotSchemaOne() {
        assertEquals(HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA), parse(body(reason = "because")))
        assertEquals(HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA), parse(body(reason = null)))
        assertEquals(HostConnectionEvidence.unknown(Reason.UNSUPPORTED_SCHEMA),
            parse(body(status = "not_tailscale", confirmed = false, reason = "because")))
    }

    @Test fun theContractSampleUnknownIsAcceptedAsUnknown() {
        // The frozen sample: unknown, confirmed=false, observed_at_ms 0.
        val sample = body(status = "unknown", confirmed = false, transport = "unknown", observedAt = 0L, reason = "peer_not_confirmed")
        assertEquals(HostConnectionEvidence.unknown(Reason.PEER_NOT_CONFIRMED), parse(sample))
    }

    @Test fun aValidAnswerSaysWhenItExpiresAndAnInvalidOneDoesNot() {
        val observedAt = now - 1_000L
        val fresh = body(observedAt = observedAt).toString()
        assertEquals(observedAt + 10_001L, HostConnectionEvidence.freshUntilWallMs(fresh, now))
        assertEquals(Status.TAILSCALE, HostConnectionEvidence.parseBody(fresh, observedAt + 10_000L).status)
        assertEquals(Status.UNKNOWN, HostConnectionEvidence.parseBody(fresh, observedAt + 10_001L).status)
        // A shorter declared window expires sooner; a longer one is still clamped.
        assertEquals(observedAt + 2_001L, HostConnectionEvidence.freshUntilWallMs(body(observedAt = observedAt, maxAge = 2_000L).toString(), now))
        assertEquals(observedAt + 10_001L, HostConnectionEvidence.freshUntilWallMs(body(observedAt = observedAt, maxAge = 60_000L).toString(), now))
        assertNull(HostConnectionEvidence.freshUntilWallMs(fresh, observedAt + 10_001L))
        assertNull(HostConnectionEvidence.freshUntilWallMs(body(status = "unknown", reason = "peer_idle").toString(), now))
        assertNull(HostConnectionObservation(fresh, supported = false).freshUntilWallMs(now))
        assertEquals(observedAt + 10_001L, HostConnectionObservation(fresh, supported = true).freshUntilWallMs(now))
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
