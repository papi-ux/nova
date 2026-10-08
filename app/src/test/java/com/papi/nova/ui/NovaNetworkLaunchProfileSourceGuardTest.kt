package com.papi.nova.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The game detail window's wiring of the network launch profile: the plan on screen is one
 * re-checked answer, every change that can move it re-checks it, and the single launch exit refuses
 * a launch whose effective profile is no longer the one shown and preflighted.
 * NetworkLaunchProfileGateTest pins the decision itself.
 */
class NovaNetworkLaunchProfileSourceGuardTest {
    private val detail = String(
        Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")), StandardCharsets.UTF_8,
    )

    private fun section(start: String, end: String): String {
        val from = detail.indexOf(start)
        require(from >= 0) { "Missing start marker: $start" }
        val to = detail.indexOf(end, from)
        require(to >= 0) { "Missing end marker: $end" }
        return detail.substring(from, to)
    }

    @Test fun theLaunchExitRefusesAChangedProfileAndAsksForPlayAgain() {
        val confirmed = section("fun launchConfirmed(", "fun attemptLaunch(")
        val gate = confirmed.indexOf("NetworkLaunchProfileGate.decide(")
        assertTrue("the gate must run before the launch is handed back", gate >= 0 && gate < confirmed.indexOf("onLaunch?.invoke("))
        assertTrue(confirmed.contains("shownLaunchChoice, preflightedLaunchChoice, networkLaunchChoice(),"))
        val refused = confirmed.substring(gate).substringBefore("launchProfileChangedNotice = false")
        assertTrue("a refused launch must not be held or replayed", refused.contains("pendingLaunch = false"))
        assertTrue("the Steam choice must be asked again through Play", refused.contains("steamDecision = null"))
        assertTrue("the plan must be shown again", refused.contains("revalidateLaunchProfile()") &&
            refused.contains("openPlaySetup(returnTo = playButtonFocus)"))
        assertTrue("a preflight made for another profile is redone", refused.contains("retryPreflight()"))
        assertTrue(refused.contains("return\n"))
        assertFalse("a refused launch must not launch", refused.contains("onLaunch?.invoke("))
        // The Space guard still comes first, so a delivered Space is never re-gated into a second launch.
        assertTrue(confirmed.indexOf("if (spaceGame != null && spaceLaunchDelivered) return") < gate)
    }

    @Test fun theLaunchCarriesTheProfileThePlanShowed() {
        assertTrue(detail.contains(".put(RESULT_KEY_LAUNCH_PROFILE, displayedLaunchChoice?.profileUuid ?: JSONObject.NULL)"))
        assertFalse(detail.contains(".put(RESULT_KEY_LAUNCH_PROFILE, networkLaunchChoice()"))
    }

    @Test fun thePreflightIsMadeForTheShownProfileAndFencedWhenFreshEvidenceMovesIt() {
        val optimization = section("fun loadOptimization(", "retryPreflight = { loadOptimization(profilePreference) }")
        val fetch = optimization.indexOf("apiClient.getNetworkConnection(observedCapabilities)")
        val revalidate = optimization.indexOf("revalidateLaunchProfile()", fetch)
        val fence = optimization.indexOf("throw CancellationException(\"launch profile changed\")", revalidate)
        val choice = optimization.indexOf("preflightChoice = displayedLaunchChoice", fence)
        val ask = optimization.indexOf("apiClient.getOptimization(", choice)
        assertTrue("fetch, re-check, fence, then plan for the shown profile",
            fetch >= 0 && revalidate > fetch && fence > revalidate && choice > fence && ask > choice)
        assertTrue(optimization.contains("preflightChoice?.profileUuid?.let {"))
        val publish = optimization.indexOf("optimizationState = nextOptimizationState")
        val stamped = optimization.indexOf("if (launchCanReplay) preflightedLaunchChoice = preflightChoice")
        assertTrue("only a published, replayable preflight records its profile",
            publish >= 0 && stamped > publish && stamped < optimization.indexOf("if (pendingLaunch)", publish))
    }

    @Test fun aChangedEffectiveProfileRetiresThePreflightAndAnyHeldPress() {
        val hook = section("onEffectiveLaunchProfileChanged = {", "onPlaySetupClosed = {")
        assertTrue(hook.contains("if (pendingLaunch) {") && hook.contains("pendingLaunch = false") &&
            hook.contains("launchProfileChangedNotice = true") && hook.contains("loadOptimization(profilePreference)"))
        val revalidate = section("private fun revalidateLaunchProfile()", "private fun scheduleHostConnectionFreshness(")
        assertTrue(revalidate.contains("displayedLaunchChoice = next") &&
            revalidate.contains("if (!com.papi.nova.network.NetworkLaunchProfileChoice.sameEffectiveProfile(previous, next)) onEffectiveLaunchProfileChanged()"))
    }

    @Test fun everySourceOfChangeReChecksThePlan() {
        val resume = section("override fun onResume()", "override fun onPause()")
        assertTrue("rules and profiles edited elsewhere", resume.contains("NetworkProfileRuleStore(this).load(serverUuid)") &&
            resume.contains("addListener(launchProfilesListener)") && resume.contains("revalidateLaunchProfile()"))
        assertTrue("network", resume.contains("registerNetworkChangeCallback()") &&
            section("private fun registerNetworkChangeCallback()", "private fun unregisterNetworkChangeCallback()")
                .contains("runOnUiThread { revalidateLaunchProfile() }"))
        val pause = section("override fun onPause()", "super.onPause()")
        assertTrue(pause.contains("removeListener(launchProfilesListener)") && pause.contains("unregisterNetworkChangeCallback()"))
        assertTrue("profiles", detail.contains("ProfileChangeListener { revalidateLaunchProfile() }"))
        assertTrue("override", section("private fun chooseLaunchProfileOverride(", "}").contains("launchProfileOverride = value") &&
            section("private fun chooseLaunchProfileOverride(", "\n    }").contains("revalidateLaunchProfile()"))
        val row = section("private fun launchProfileRow()", "private fun openNetworkRules()")
        assertFalse("overrides go through the re-check", row.contains("launchProfileOverride = "))
        assertTrue("host evidence arriving", section("private fun refreshHostConnection()", "private fun revalidateLaunchProfile()")
            .contains("revalidateLaunchProfile()"))
        val freshness = section("private fun scheduleHostConnectionFreshness(", "private fun registerNetworkChangeCallback()")
        assertTrue("host evidence ageing out", freshness.contains("freshForMs(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())") &&
            freshness.contains("refreshHostConnection()") && freshness.contains("revalidateLaunchProfile()"))
    }

    @Test fun playSetupDrawsTheReCheckedPlanNotAFreshGuess() {
        val row = section("private fun launchProfileRow()", "private fun openNetworkRules()")
        assertTrue(row.contains("val choice = displayedLaunchChoice ?: return null"))
        assertFalse(row.contains("networkLaunchChoice()"))
        val facts = section("private fun hostConnectionFacts()", "private fun launchProfileRow()")
        assertTrue(facts.contains("val evidence = displayedHostEvidence") &&
            facts.contains("R.string.nova_launch_profile_changed_notice"))
        assertFalse(facts.contains("hostConnectionEvidence()"))
    }
}
