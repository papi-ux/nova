package com.papi.nova.network

import com.papi.nova.network.HostConnectionEvidence.Status
import com.papi.nova.network.NetworkLaunchProfileGate.Decision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plan Play Setup showed, the preflight the host answered and what would launch now must all
 * be one effective profile; anything else stops the launch for the plan to be shown again.
 * Scenarios are built through [NetworkLaunchProfileChoice.resolve] as the activity builds them.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NetworkLaunchProfileGateTest {
    private val home = "11111111-1111-1111-1111-111111111111"
    private val travel = "22222222-2222-2222-2222-222222222222"
    private val now = 1_800_000_000_000L
    /** This device's monotonic clock when [now] was read, and when every answer here arrived. */
    private val elapsed = 5_000_000L

    private fun observed(body: String) = HostConnectionObservation(body, supported = true, receivedAtElapsedMs = elapsed)

    /** Judge an answer [afterMs] after it arrived, both clocks moving together. */
    private fun HostConnectionObservation.at(afterMs: Long) = evaluate(now + afterMs, elapsed + afterMs)

    /** The saved profiles and their settings fingerprints, as ProfilesManager would answer. */
    private val saved = mutableMapOf(home to 1, travel to 7)

    private val rules = HostNetworkRules(true, listOf(
        NetworkProfileRule(travel, hostConnection = HostConnectionCondition.TAILSCALE_CONFIRMED),
        NetworkProfileRule(home, transport = TransportCondition.WIFI),
    ))

    private fun choose(snapshot: NetworkSnapshot, override: String? = null, set: HostNetworkRules = rules) =
        NetworkLaunchProfileChoice.resolve(set, snapshot, override, { it in saved }, { saved[it] })

    private fun snapshot(transport: LinkTransport?, host: Status) = NetworkSnapshot(transport, false, transport == LinkTransport.CELLULAR, host)

    private fun hostAnswer(observedAt: Long, transport: String) = JSONObject()
        .put("schema_version", 1).put("status", "tailscale").put("address_kind", "cgnat")
        .put("tailscale", JSONObject().put("confirmed", true).put("transport", transport).put("scope", "control_connection")
            .put("source", "tailscaled_localapi").put("observed_at_ms", observedAt).put("max_age_ms", 10_000)
            .put("reason", if (transport == "unknown") "transport_unknown" else "observed"))
        .toString()

    @Test fun unchangedPlansLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(shown, shown, choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))))
        // Rules never applying (a Space) and the active profile are the same effective profile.
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(null, null, null))
        val active = choose(snapshot(LinkTransport.ETHERNET, Status.UNKNOWN))
        assertEquals(NetworkLaunchProfileChoice.Source.ACTIVE, active.source)
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(null, null, active))
    }

    @Test fun aConfirmedTailscaleAnswerWithUnknownTransportStillMatches() {
        // Root's settlement: transport is informational; a fresh confirmed answer is enough.
        val evidence = observed(hostAnswer(now - 500L, "unknown")).at(0L)
        assertEquals(Status.TAILSCALE, evidence.status)
        assertEquals(HostConnectionEvidence.Transport.UNKNOWN, evidence.transport)
        val choice = choose(snapshot(LinkTransport.CELLULAR, evidence.status))
        assertEquals(travel, choice.profileUuid)
        // Direct, DERP or peer relay choose exactly the same profile.
        listOf("direct", "derp", "peer_relay").forEach { transport ->
            val status = observed(hostAnswer(now - 500L, transport)).at(0L).status
            assertTrue(NetworkLaunchProfileChoice.sameEffectiveProfile(choice, choose(snapshot(LinkTransport.CELLULAR, status))))
        }
    }

    @Test fun aNetworkChangeAfterThePlanStopsTheLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        assertEquals(home, shown.profileUuid)
        val now = choose(snapshot(LinkTransport.CELLULAR, Status.NOT_TAILSCALE))
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown, now))
    }

    @Test fun hostEvidenceAgingOutStopsALaunchItChose() {
        val observation = observed(hostAnswer(now - 1_000L, "derp"))
        val shown = choose(snapshot(LinkTransport.CELLULAR, observation.at(0L).status))
        assertEquals(travel, shown.profileUuid)
        val later = choose(snapshot(LinkTransport.CELLULAR, observation.at(9_001L).status))
        assertEquals(NetworkLaunchProfileChoice.Source.ACTIVE, later.source)
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown, later))
        // Still inside the window it launches.
        val inside = choose(snapshot(LinkTransport.CELLULAR, observation.at(9_000L).status))
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(shown, shown, inside))
    }

    @Test fun changedHostEvidenceStopsTheLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.TAILSCALE))
        assertEquals(travel, shown.profileUuid)
        val now = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        assertEquals(home, now.profileUuid)
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown, now))
    }

    @Test fun deletingTheChosenProfileStopsTheLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.TAILSCALE))
        saved.remove(travel)
        val now = choose(snapshot(LinkTransport.WIFI, Status.TAILSCALE))
        assertEquals(home, now.profileUuid)
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown, now))
    }

    @Test fun editingTheChosenProfileStopsTheLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        saved[home] = 2
        val now = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        assertEquals(home, now.profileUuid)
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown, now))
    }

    @Test fun anOverrideOrRuleEditAfterThePlanStopsTheLaunch() {
        val shown = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        assertEquals(Decision.PLAN_CHANGED,
            NetworkLaunchProfileGate.decide(shown, shown, choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE), override = travel)))
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shown, shown,
            choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE), set = rules.copy(enabled = false))))
        // Picking the active profile once, when no rule chose another, sends the same settings.
        val active = choose(snapshot(LinkTransport.ETHERNET, Status.UNKNOWN))
        val activeOnce = choose(snapshot(LinkTransport.ETHERNET, Status.UNKNOWN), override = NetworkLaunchProfileChoice.OVERRIDE_ACTIVE_PROFILE)
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(active, active, activeOnce))
    }

    @Test fun aPreflightMadeForAnotherProfileStopsTheLaunchEvenIfThePlanIsCurrent() {
        val preflighted = choose(snapshot(LinkTransport.WIFI, Status.NOT_TAILSCALE))
        val shownAndNow = choose(snapshot(LinkTransport.CELLULAR, Status.TAILSCALE))
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shownAndNow, preflighted, shownAndNow))
        assertEquals(Decision.PLAN_CHANGED, NetworkLaunchProfileGate.decide(shownAndNow, null, shownAndNow))
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(shownAndNow, shownAndNow, shownAndNow))
    }

    @Test fun whichRuleChoseAProfileDoesNotMatterOnlyTheProfile() {
        val twoRoutesHome = HostNetworkRules(true, listOf(
            NetworkProfileRule(home, transport = TransportCondition.WIFI),
            NetworkProfileRule(home, transport = TransportCondition.ETHERNET),
        ))
        val wifi = choose(snapshot(LinkTransport.WIFI, Status.UNKNOWN), set = twoRoutesHome)
        val ethernet = choose(snapshot(LinkTransport.ETHERNET, Status.UNKNOWN), set = twoRoutesHome)
        assertFalse(wifi == ethernet)
        assertEquals(Decision.PROCEED, NetworkLaunchProfileGate.decide(wifi, wifi, ethernet))
    }
}
