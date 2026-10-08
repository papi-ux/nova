package com.papi.nova.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.network.HostConnectionEvidence.Status
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NetworkProfileRulesTest {
    private val home = "11111111-1111-1111-1111-111111111111"
    private val travel = "22222222-2222-2222-2222-222222222222"
    private val gone = "33333333-3333-3333-3333-333333333333"
    private val exists: (String) -> Boolean = { it == home || it == travel }

    private val homeWifi = NetworkSnapshot(LinkTransport.WIFI, metered = false, novaVpn = false, hostConnection = Status.NOT_TAILSCALE)
    private val awayTailnet = NetworkSnapshot(LinkTransport.CELLULAR, metered = true, novaVpn = true, hostConnection = Status.TAILSCALE)
    private val allUnknown = NetworkSnapshot(null, null, null, Status.UNKNOWN)

    private fun rules(vararg rule: NetworkProfileRule, enabled: Boolean = true) = HostNetworkRules(enabled, rule.toList())

    @Test fun rulesAreOffByDefaultAndOffRulesNeverMatch() {
        assertFalse(HostNetworkRules().enabled)
        assertNull(NetworkProfileRuleEngine.evaluate(rules(NetworkProfileRule(home), enabled = false), homeWifi, exists))
        assertNull(NetworkProfileRuleEngine.evaluate(HostNetworkRules.NONE, homeWifi, exists))
    }

    @Test fun theFirstMatchingRuleInVisibleOrderWins() {
        val set = rules(
            NetworkProfileRule(travel, hostConnection = HostConnectionCondition.TAILSCALE_CONFIRMED),
            NetworkProfileRule(home, transport = TransportCondition.WIFI),
            NetworkProfileRule(travel),
        )
        assertEquals(NetworkRuleMatch(1, set.rules[1]), NetworkProfileRuleEngine.evaluate(set, homeWifi, exists))
        assertEquals(0, NetworkProfileRuleEngine.evaluate(set, awayTailnet, exists)?.index)
        // Reordering changes the answer deterministically.
        val reordered = rules(set.rules[2], set.rules[0], set.rules[1])
        assertEquals(0, NetworkProfileRuleEngine.evaluate(reordered, homeWifi, exists)?.index)
    }

    @Test fun unknownSignalsNeverSatisfyASpecificCondition() {
        listOf(
            NetworkProfileRule(home, transport = TransportCondition.WIFI),
            NetworkProfileRule(home, metered = YesNoCondition.NO),
            NetworkProfileRule(home, metered = YesNoCondition.YES),
            NetworkProfileRule(home, novaVpn = YesNoCondition.NO),
            NetworkProfileRule(home, novaVpn = YesNoCondition.YES),
            NetworkProfileRule(home, hostConnection = HostConnectionCondition.TAILSCALE_CONFIRMED),
        ).forEach { rule -> assertNull(rule.toString(), NetworkProfileRuleEngine.evaluate(rules(rule), allUnknown, exists)) }
        // Only an all-Any rule matches unknown, and it says so by matching everything.
        assertEquals(0, NetworkProfileRuleEngine.evaluate(rules(NetworkProfileRule(home)), allUnknown, exists)?.index)
    }

    @Test fun onlyAConfirmedTailscaleAnswerCanBeAHostConnectionCondition() {
        assertEquals(listOf(HostConnectionCondition.ANY, HostConnectionCondition.TAILSCALE_CONFIRMED),
            HostConnectionCondition.entries.toList())
        val tailscaleRule = rules(NetworkProfileRule(travel, hostConnection = HostConnectionCondition.TAILSCALE_CONFIRMED))
        assertNull(NetworkProfileRuleEngine.evaluate(tailscaleRule, homeWifi, exists))
        // A stored "not_tailscale" condition (never written by this version) is dropped, not guessed.
        assertTrue(NetworkProfileRuleStore.decode(
            """{"v":1,"enabled":true,"rules":[{"profile":"$home","transport":"any","metered":"any","vpn":"any","host_connection":"not_tailscale"}]}""",
        ).rules.isEmpty())
    }

    @Test fun aGenericVpnIsNotTailscale() {
        // Nova's traffic through a VPN, but the host has not confirmed Tailscale.
        val vpnOnly = awayTailnet.copy(hostConnection = Status.UNKNOWN)
        val tailscaleRule = rules(NetworkProfileRule(travel, hostConnection = HostConnectionCondition.TAILSCALE_CONFIRMED))
        assertNull(NetworkProfileRuleEngine.evaluate(tailscaleRule, vpnOnly, exists))
        assertEquals(0, NetworkProfileRuleEngine.evaluate(rules(NetworkProfileRule(travel, novaVpn = YesNoCondition.YES)), vpnOnly, exists)?.index)
    }

    @Test fun aRuleWhoseProfileIsGoneIsSkipped() {
        val set = rules(NetworkProfileRule(gone), NetworkProfileRule(home))
        assertEquals(1, NetworkProfileRuleEngine.evaluate(set, homeWifi, exists)?.index)
        assertNull(NetworkProfileRuleEngine.evaluate(rules(NetworkProfileRule(gone)), homeWifi, exists))
    }

    @Test fun storeRoundTripsAndKeepsOnlyProfileIdsAndConditions() {
        val set = rules(
            NetworkProfileRule(home, TransportCondition.WIFI, YesNoCondition.NO, YesNoCondition.NO, HostConnectionCondition.ANY),
            NetworkProfileRule(travel, TransportCondition.CELLULAR, YesNoCondition.YES, YesNoCondition.YES, HostConnectionCondition.TAILSCALE_CONFIRMED),
        )
        val encoded = NetworkProfileRuleStore.encode(set)
        assertEquals(set, NetworkProfileRuleStore.decode(encoded))
        val json = JSONObject(encoded)
        assertEquals(setOf("v", "enabled", "rules"), json.keys().asSequence().toSet())
        val ruleKeys = json.getJSONArray("rules").getJSONObject(0).keys().asSequence().toSet()
        assertEquals(setOf("profile", "transport", "metered", "vpn", "host_connection"), ruleKeys)
    }

    @Test fun unreadableStorageFallsBackToOffAndDropsBadRules() {
        assertEquals(HostNetworkRules.NONE, NetworkProfileRuleStore.decode(null))
        assertEquals(HostNetworkRules.NONE, NetworkProfileRuleStore.decode("{"))
        assertEquals(HostNetworkRules.NONE, NetworkProfileRuleStore.decode("""{"v":2,"enabled":true,"rules":[]}"""))
        assertFalse(NetworkProfileRuleStore.decode("""{"v":1,"enabled":"true","rules":[]}""").enabled)
        val partial = NetworkProfileRuleStore.decode(
            """{"v":1,"enabled":true,"rules":[{"profile":"$home","transport":"wifi","metered":"any","vpn":"any","host_connection":"any"},""" +
                """{"profile":"$travel","transport":"satellite","metered":"any","vpn":"any","host_connection":"any"},""" +
                """{"profile":"","transport":"any","metered":"any","vpn":"any","host_connection":"any"}]}""",
        )
        assertTrue(partial.enabled)
        assertEquals(listOf(NetworkProfileRule(home, transport = TransportCondition.WIFI)), partial.rules)
    }

    @Test fun storageIsCappedAtTheRuleLimit() {
        val many = HostNetworkRules(true, List(NetworkProfileRuleStore.MAX_RULES + 3) { NetworkProfileRule(home) })
        assertEquals(NetworkProfileRuleStore.MAX_RULES, NetworkProfileRuleStore.decode(NetworkProfileRuleStore.encode(many)).rules.size)
    }

    @Test fun rulesArePerHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = NetworkProfileRuleStore(context)
        val hostA = "host-a"
        val hostB = "host-b"
        store.save(hostA, rules(NetworkProfileRule(home)))
        assertEquals(rules(NetworkProfileRule(home)), store.load(hostA))
        assertEquals(HostNetworkRules.NONE, store.load(hostB))
        assertEquals(HostNetworkRules.NONE, store.load(null))
        store.save(hostA, HostNetworkRules.NONE)
        assertEquals(HostNetworkRules.NONE, store.load(hostA))
        assertFalse(context.getSharedPreferences(NetworkProfileRuleStore.PREFS, Context.MODE_PRIVATE).contains("host:$hostA"))
    }

    @Test fun oneLaunchChoicePrefersOverrideThenRuleThenActive() {
        val set = rules(NetworkProfileRule(travel, transport = TransportCondition.WIFI))
        val ruleChoice = NetworkLaunchProfileChoice.resolve(set, homeWifi, null, exists)
        assertEquals(NetworkLaunchProfileChoice.Source.RULE, ruleChoice.source)
        assertEquals(travel, ruleChoice.profileUuid)
        assertEquals(0, ruleChoice.match?.index)

        val overridden = NetworkLaunchProfileChoice.resolve(set, homeWifi, home, exists)
        assertEquals(NetworkLaunchProfileChoice(home, NetworkLaunchProfileChoice.Source.OVERRIDE), overridden)

        val activeOverride = NetworkLaunchProfileChoice.resolve(set, homeWifi, NetworkLaunchProfileChoice.OVERRIDE_ACTIVE_PROFILE, exists)
        assertEquals(NetworkLaunchProfileChoice(null, NetworkLaunchProfileChoice.Source.OVERRIDE_ACTIVE), activeOverride)

        // An override naming a profile that has since gone does not launch with it.
        assertEquals(NetworkLaunchProfileChoice.Source.RULE, NetworkLaunchProfileChoice.resolve(set, homeWifi, gone, exists).source)

        val noMatch = NetworkLaunchProfileChoice.resolve(set, awayTailnet, null, exists)
        assertEquals(NetworkLaunchProfileChoice(null, NetworkLaunchProfileChoice.Source.ACTIVE), noMatch)
        assertEquals(NetworkLaunchProfileChoice(null, NetworkLaunchProfileChoice.Source.ACTIVE),
            NetworkLaunchProfileChoice.resolve(HostNetworkRules.NONE, homeWifi, null, exists))
    }

    @Test fun onlyTheTailscaleAppsOwnSafeLauncherIsOffered() {
        val pkg = NovaTailscaleApp.PACKAGE
        assertTrue(NovaTailscaleApp.isSafeLaunchTarget(pkg, pkg, exported = true, enabled = true, permission = null))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget(pkg, pkg, exported = false, enabled = true, permission = null))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget(pkg, pkg, exported = true, enabled = false, permission = null))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget(pkg, pkg, exported = true, enabled = true, permission = "x.PERM"))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget("other.app", pkg, exported = true, enabled = true, permission = null))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget(pkg, "other.app", exported = true, enabled = true, permission = null))
        assertFalse(NovaTailscaleApp.isSafeLaunchTarget(null, null, exported = true, enabled = true, permission = null))
    }

    @Test fun theAppMissingOffersNoLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(NovaTailscaleApp.launchIntent(context.packageManager))
    }
}
