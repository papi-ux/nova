package com.papi.nova

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GameClientRuntimeSourceGuardTest {

    @Test
    fun gameResolvesLaunchProfileProvenanceBeforeReportingRuntime() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val launchSetup = game.section(
            "var launchOptimization:JSONObject? = null",
            " // Display a message to the user if HEVC was forced on but we still didn't find a decoder"
        )
        val reportSnapshot = game.section(
            "private fun reportPolarisClientSettingsSnapshot(clientPresentation:JSONObject?):Boolean",
            "private fun updateAppliedStreamSettingsFromStatus("
        )

        assertTrue(
            "Game should derive profile provenance from the actual launch optimization before stream sync reporting",
            game.contains("lastClientProfileProvenance") &&
                launchSetup.contains("StreamSyncManager.resolveProfileProvenance(") &&
                launchSetup.contains("launchOptimization,")
        )
        assertTrue(
            "Resume Stream must not consume a queued next-launch profile and fallback metered queries must carry an explicit bitrate lock",
                launchSetup.contains("watchOnlyRequested || resumeExistingRequested") &&
                game.contains("setResumeExistingOnly(resumeExistingRequested)") &&
                game.contains("private fun loadLaunchOptimization(") &&
                game.contains("bitrateLocked = queryBitrateLocked")
        )
        assertTrue(
            "Game must keep a trusted Play Setup envelope on metered launches and reject legacy launch-policy responses",
                game.contains("if (!launchOptimizationJson.isNullOrBlank())") &&
                game.contains("StreamSyncManager.hasTrustedResolvedProfile(preflight)") &&
                game.contains("preflightHonorsCurrentBitrateLock") &&
                game.contains("StreamSyncManager.resolvedFieldIsLocked(") &&
                game.contains("preflightBitrateKbps in 1..queryBitrateKbps") &&
                game.contains("trustedPreflight = preflight") &&
                game.contains("val optimizationResult = trustedPreflight ?: novaApiClient!!.getOptimization(") &&
                game.contains("Rejecting malformed preflight optimization payload") &&
                game.contains("mode = requestedLaunchTopology()") &&
                game.contains("topologyLocked = displayModeExplicit") &&
                game.contains("else if (vDisplay) \"host_virtual_display\"") &&
                game.contains("private data class LaunchOptimizationDecision(") &&
                game.contains("if (launchDecision.policyBlocked)") &&
                game.contains("identifyLaunchHost()") &&
                game.contains("PolarisLaunchHostKind.CURRENT_POLARIS") &&
                game.contains("PolarisLaunchHostKind.NON_POLARIS") &&
                game.contains("Legacy or unknown host cannot prove deterministic launch authority") &&
                game.contains("launchBounded = true") &&
                game.contains("setResolvedProfile(launchResolvedProfileTrusted)")
        )
        assertTrue(
            "launch identity and optimization must run off the main thread and return through a one-shot bound token",
            launchSetup.contains("launchRuntimeIo(\"NovaLaunchPolicyGate\")") &&
                launchSetup.indexOf("launchRuntimeIo(\"NovaLaunchPolicyGate\")") in
                0 until launchSetup.indexOf("loadLaunchOptimization(") &&
                launchSetup.contains("NovaLaunchPolicyGateStore.consume(") &&
                launchSetup.contains("NovaLaunchPolicyGateStore.issue(") &&
                !game.contains("thread.join(12000)")
        )
        assertTrue(
            "singleTask launch decisions must bind the certificate and every local setting that can rewrite the resolved envelope",
            game.contains("serverCertificatePolicyFingerprint()") &&
                game.contains("displayModeExplicit.toString()") &&
                game.contains("forcePrivateAfterSteamClose.toString()") &&
                game.contains("prefConfig!!.resolutionScaleFactor.toString()") &&
                game.contains("prefConfig!!.framePacing.toString()") &&
                game.contains("prefConfig!!.framePacingWarpFactor.toString()") &&
                game.contains("launchPolicyGateGeneration.incrementAndGet()") &&
                game.contains("this@Game.getIntent() === gateIntent") &&
                game.contains("this@Game.getIntent() !== gateIntent")
        )
        assertTrue(
            "an obsolete gate worker must return an immutable decision and every Nova-authored launch override must be re-resolved by Polaris",
            game.contains("requestedProfilePreference:String") &&
                game.contains("val containsNovaLaunchOverride =") &&
                game.contains("NovaLaunchStreamOverride.NORMALIZATION_REASON") &&
                game.contains(") && !containsNovaLaunchOverride)") &&
                game.contains("queryDisplayLocked = containsNovaLaunchOverride") &&
                game.contains("return LaunchOptimizationDecision(optimizationResult, false, preference, true)")
        )
        assertTrue(
            "fresh and preflight resolved profiles must pass the same HDR, client-FPS, and metered-lock envelope",
            game.contains("private fun resolvedOptimizationHonorsLaunchEnvelope(") &&
                game.countOccurrences("resolvedOptimizationHonorsLaunchEnvelope(") >= 3 &&
                game.contains("resolvedFps <= clientMaximumFps + 0.5f") &&
                game.contains("resolvedBitrate in 1..bitrateCeilingKbps") &&
                game.contains("val displayLockHonored = !displayLocked") &&
                game.contains("val fpsLockHonored = !(displayLocked || fpsLocked)")
        )
        assertTrue(
            "a trusted deterministic FPS must be used exactly by both /launch and the stream connection",
            game.contains("chosenFrameRate = autoSafeTargetFps") &&
                game.contains("if (!launchResolvedProfileTrusted &&\nprefConfig!!.framePacing ==") &&
                game.contains("if (!launchResolvedProfileTrusted && prefConfig!!.framePacingWarpFactor > 0)")
        )
        assertTrue(
            "connection recovery must use the same lifecycle-bound capability scope as Doctor sampling",
            game.contains("ConnectionResilienceManager(") &&
                game.contains("novaFeatureScope") &&
                game.contains("FeatureFlagManager.capabilitiesForScope(novaFeatureScope)")
        )
        assertTrue(
            "client runtime reports should carry the same provenance into the client_runtime payload",
            reportSnapshot.contains("lastClientProfileProvenance") &&
                reportSnapshot.contains("buildClientRuntime(") &&
                // `in 0 until` rather than `<`: a missing first needle is -1, which is
                // less than any real position, so the loose form passes precisely when the
                // thing it is ordering has been deleted.
                reportSnapshot.indexOf("lastClientProfileProvenance") in
                0 until reportSnapshot.indexOf("reportClientSettings(")
        )
    }

    @Test
    fun resumeExistingCannotFallThroughToFreshLaunch() {
        val connection = readSource("src/main/java/com/papi/nova/nvstream/NvConnection.kt")
        val quitAndLaunch = connection.section(
            "protected fun quitAndLaunch(",
            "fun getSessionToken(): String?",
        )
        val launchNotRunning = connection.section(
            "private fun launchNotRunningApp(",
            "fun start(",
        )
        val guard = "canStartFreshLaunch(streamConfig.getResumeExistingOnly(), context.watchOnlyRequested)"

        assertTrue(
            "Resume Existing must fail closed before either helper can quit or launch a session",
            quitAndLaunch.contains(guard) &&
                quitAndLaunch.indexOf(guard) in 0 until quitAndLaunch.indexOf("h.quitApp(") &&
                launchNotRunning.contains(guard) &&
                launchNotRunning.indexOf(guard) in 0 until launchNotRunning.indexOf("h.launchApp("),
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }
}
