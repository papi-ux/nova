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
                game.contains("private fun loadLaunchOptimization(") &&
                game.contains("bitrateLocked = queryBitrateLocked")
        )
        assertTrue(
            "Game must keep a trusted Play Setup envelope on metered launches and reject legacy launch-policy responses",
                game.contains("if (!launchOptimizationJson.isNullOrBlank())") &&
                game.contains("StreamSyncManager.hasTrustedResolvedProfile(preflight)") &&
                game.contains("trustedPreflight = preflight") &&
                game.contains("result[0] = trustedPreflight ?: novaApiClient!!.getOptimization(") &&
                game.contains("Rejecting malformed preflight optimization payload") &&
                game.contains("mode = streamMode") &&
                game.contains("launchOptimizationPolicyBlocked = true") &&
                game.contains("identifyLaunchHost()") &&
                game.contains("PolarisLaunchHostKind.CURRENT_POLARIS") &&
                game.contains("PolarisLaunchHostKind.NON_POLARIS") &&
                game.contains("Legacy or unknown host cannot prove deterministic launch authority") &&
                game.contains("launchBounded = true") &&
                game.contains("setResolvedProfile(launchResolvedProfileTrusted)")
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

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
