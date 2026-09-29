package com.papi.nova

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A launch the host refused read as a network fault: Mirror Desktop with PyroWave came back as
 * "Failed to start RTSP handshake (error 503)" with the firewall ports under it, and the startup
 * card ghosted behind the page. A policy refusal floated a Toast cut off by its ellipsis.
 */
class GameLaunchRefusalCopySourceGuardTest {
    private val game = File("src/main/java/com/papi/nova/Game.kt").readText()

    @Test
    fun aHostThatAnsweredIsNotAPortProblem() {
        val stage = game.substringAfter("override fun stageFailed(").substringBefore("showNovaLaunchIssueSheet(dialogText)")
        assertTrue(stage.contains("val hostAnswered = errorCode == RTSP_SERVICE_UNAVAILABLE"))
        assertTrue(stage.contains("dialogText = getResources().getString(R.string.nova_launch_host_refused_stream)"))
        assertTrue(stage.contains("&& !hostAnswered)"))
    }

    @Test
    fun theLaunchIssuePageTakesTheStartupCardDown() {
        val sheet = game.substringAfter("private fun showNovaLaunchIssueSheet(message: String) {").substringBefore("val surfaces = novaSurfaces")
        assertTrue(sheet.contains("novaProgressOverlay?.dismiss()"))
    }

    // Two refusals made before the stream starts still floated a Toast and closed (audit X1): a
    // stale launch handoff, and a trusted profile with no display topology, which also said
    // "Update Polaris" to a host that was current.
    @Test
    fun aRefusalBeforeTheStreamIsTheLaunchIssuePageNotAToast() {
        val handoff = game.substringAfter("if (launchPolicyTokenInvalid)").substringBefore("return\n")
        assertTrue(handoff.contains("showNovaLaunchIssueSheet(getString(R.string.nova_launch_retry))"))
        assertFalse(handoff.contains("Toast.makeText"))
        val topology = game.substringAfter("if (launchResolvedProfileTrusted && expectedLaunchTopology.isBlank())").substringBefore("return\n")
        assertTrue(topology.contains("showNovaLaunchIssueSheet(getString(R.string.nova_launch_profile_not_settled))"))
        assertFalse(topology.contains("Toast.makeText"))
        assertFalse(topology.contains("nova_launch_deterministic_host_required"))
    }

    // A 503 is the host answering, yet the outside connection test still ran and its "your
    // network is blocking Nova" sentence went under the host's refusal (audit XR4).
    @Test
    fun aHostThatAnsweredIsNeverToldTheNetworkBlocksNova() {
        val stage = game.substringAfter("override fun stageFailed(").substringBefore("showNovaLaunchIssueSheet(dialogText)")
        val beforePage = stage.substringBefore("runOnUiThread(")
        assertTrue("the answer is known before the outside test runs", beforePage.contains("val hostAnswered = errorCode == RTSP_SERVICE_UNAVAILABLE"))
        assertTrue(
            "a host that answered skips the outside test",
            beforePage.contains("if (hostAnswered) MoonBridge.ML_TEST_RESULT_INCONCLUSIVE else MoonBridge.testClientConnectivity("),
        )
        val blockedGuard = stage.substringBefore("R.string.nettest_text_blocked").substringAfterLast("if (")
        assertTrue("and never adds the blocking sentence: $blockedGuard", blockedGuard.startsWith("!hostAnswered &&"))
    }

    // The 503 line named capture or encode as the cause, which a bare 503 does not say (XR4).
    @Test
    fun theAnsweredHostsLineGuessesNoCause() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val line = Regex("<string name=\"nova_launch_host_refused_stream\">(.*?)</string>").find(strings)!!.groupValues[1]
        assertFalse(line, line.contains("capture", ignoreCase = true))
        assertFalse(line, line.contains("encode", ignoreCase = true))
    }

    // A stale handoff for a Space launch offered the ordinary Try Again, which relaunches the
    // stream, because the page was built before the launch was known to be a Space (audit X1).
    @Test
    fun aStaleHandoffForASpaceRetriesThroughTheSpace() {
        val handoff = game.substringAfter("if (launchPolicyTokenInvalid)").substringBefore("return\n")
        val known = handoff.indexOf("spaceSession = com.papi.nova.manager.WorkerLaunchContract.isProfileApp(")
        assertTrue("the stale branch says whether the launch was a Space", known >= 0)
        assertTrue("before it builds the page", known < handoff.indexOf("showNovaLaunchIssueSheet("))
    }

    // The same Space-blind Try Again, for a Space refused at the launch policy gate, which also
    // builds its page before the launch is set up (audit X1, follow-up).
    @Test
    fun aSpaceRefusedAtThePolicyGateRetriesThroughTheSpace() {
        // What the refusal does is GameLaunchPolicyGateSpaceTest's; here, that the gate goes there.
        val gate = game.substringAfter("if (launchDecision.policyBlocked)").substringBefore("return@launchRuntimeIo")
        assertTrue("the gate refuses through the function the behaviour test drives", gate.contains("refuseAtLaunchPolicyGate("))
        assertFalse("and builds no page of its own", gate.contains("showNovaLaunchIssueSheet("))
    }

    @Test
    fun aPolicyRefusalIsAStatePageNotAToast() {
        val gate = game.substringAfter("if (launchDecision.policyBlocked)").substringBefore("return@launchRuntimeIo")
        val refusal = game.substringAfter("internal fun refuseAtLaunchPolicyGate(").substringBefore("\n}\n")
        assertTrue(gate.contains("refuseAtLaunchPolicyGate("))
        assertTrue(refusal.contains("showNovaLaunchIssueSheet(message)"))
        assertFalse(gate.contains("Toast.makeText"))
    }
}
