package com.papi.nova

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcViewFallbackRoutingSourceGuardTest {

    @Test
    fun hostTapRoutesByLibraryReadinessWithStandardAppListFallback() {
        val pcView = readSource("src/main/java/com/papi/nova/PcView.kt")
        val openBestPlaySurface = pcView.section(
            "private fun openBestPlaySurface(",
            "private fun syncComputerList()"
        )

        // The order these were checked in is decided in one place now, novaHostPlaySurface, which the card's
        // pill and the host's sheet ask too; NovaHostPlaySurfaceTest holds the order itself.
        val decision = readSource("src/main/java/com/papi/nova/grid/NovaHostPlaySurface.kt")
            .section("internal fun novaHostPlaySurface(", "internal enum class NovaHostRowFocusMove")

        assertTrue(
            "the press asks the one function and does what it answers",
            openBestPlaySurface.contains("val surface = novaHostPlaySurface(")
        )
        assertTrue(
            "this device's own running session still routes to resume before a library or app-list surface is chosen. " +
                "Someone else's no longer does where there is a library to open: papi, 2026-09-21, with a game left " +
                "open by his Deck, \"i also am stuck at Watch Stream it wont just go to the library\"",
            openBestPlaySurface.contains("NovaHostPlaySurface.RESUME, NovaHostPlaySurface.WATCH -> resumeOrWatchRunningGame(computer)") &&
                decision.indexOf("return NovaHostPlaySurface.RESUME") in 1 until decision.indexOf("return when (library) {")
        )
        assertTrue(
            "Polaris-capable hosts should open Nova Library only after the capability probe marks the library available",
            decision.contains("ComputerDetails.LibraryState.AVAILABLE -> NovaHostPlaySurface.LIBRARY") &&
                openBestPlaySurface.contains("NovaHostPlaySurface.LIBRARY -> doNovaLibrary(computer)")
        )
        assertTrue(
            "unknown library capability should trigger the Polaris probe and stay on the dashboard instead of falling through to the app list",
            openBestPlaySurface.contains("NovaHostPlaySurface.CHECK_LIBRARY -> {") &&
                openBestPlaySurface.contains("maybeProbeLibraryReadiness(computerObject)") &&
                openBestPlaySurface.contains("R.string.pcview_library_checking")
        )
        assertTrue(
            "Apollo/Sunshine/non-Polaris hosts must fall back to the standard app-list flow after the available/unknown library branches",
            openBestPlaySurface.contains("NovaHostPlaySurface.APP_LIST -> doAppList(computer, false, false)") &&
                decision.indexOf("NovaHostPlaySurface.APP_LIST") > decision.indexOf("NovaHostPlaySurface.CHECK_LIBRARY")
        )
    }

    @Test
    fun libraryCapabilityHelpersDoNotTreatFallbackHostsAsPolarisLibraryReady() {
        val pcView = readSource("src/main/java/com/papi/nova/PcView.kt")
        val canUseLibrary = pcView.section(
            "private fun canUseLibrary(",
            "private fun canProbeLibrary("
        )
        val canProbeLibrary = pcView.section(
            "private fun canProbeLibrary(",
            "private fun resetLibraryReadiness("
        )
        val probeLibraryReadiness = pcView.section(
            "private fun maybeProbeLibraryReadiness(",
            "private fun findComputerObject("
        )

        assertTrue(
            "Nova Library should require an online, paired host with an active address and AVAILABLE library state",
            canUseLibrary.contains("details.state == ComputerDetails.State.ONLINE") &&
                canUseLibrary.contains("!needsPairing(details)") &&
                canUseLibrary.contains("details.activeAddress != null") &&
                canUseLibrary.contains("details.libraryState == ComputerDetails.LibraryState.AVAILABLE")
        )
        assertTrue(
            "only UNKNOWN hosts should be probed; UNAVAILABLE fallback hosts should not keep re-probing or block app-list routing",
            canProbeLibrary.contains("details.state == ComputerDetails.State.ONLINE") &&
                canProbeLibrary.contains("!needsPairing(details)") &&
                canProbeLibrary.contains("details.activeAddress != null") &&
                canProbeLibrary.contains("details.libraryState == ComputerDetails.LibraryState.UNKNOWN") &&
                !canProbeLibrary.contains("ComputerDetails.LibraryState.UNAVAILABLE")
        )
        assertTrue(
            "a failed/missing Polaris Library capability probe should settle the host as UNAVAILABLE so Apollo/Sunshine can use the app-list fallback",
            probeLibraryReadiness.contains("var state = ComputerDetails.LibraryState.UNAVAILABLE") &&
                probeLibraryReadiness.contains("ComputerDetails.LibraryState.AVAILABLE") &&
                probeLibraryReadiness.contains("state = ComputerDetails.LibraryState.UNAVAILABLE")
        )
    }

    @Test
    fun dashboardCopyAndCardStatesDistinguishPolarisLibraryFromStandardAppList() {
        val strings = readSource("src/main/res/values/strings.xml")
        val pcGridAdapter = readSource("src/main/java/com/papi/nova/grid/PcGridAdapter.kt")
        val onlineCardState = pcGridAdapter.section(
            "if (obj.details.pairState == PairingManager.PairState.PAIRED",
            "        } else if (obj.details.state == ComputerDetails.State.OFFLINE)"
        )

        assertTrue(
            "fallback host copy should name the standard Moonlight-compatible app list for Apollo/Sunshine users",
            strings.contains("<string name=\"pcview_card_hint_open_apps\">Open the standard Moonlight-compatible app list for Apollo, Sunshine, and other compatible hosts.</string>") &&
                strings.contains("<string name=\"applist_error_title\">Standard app list unavailable</string>") &&
                strings.contains("Moonlight-compatible app list from this Apollo/Sunshine host")
        )
        assertFalse(
            "fallback host copy should not call the standard app-list path legacy",
            strings.contains("legacy app list")
        )

        val availableIndex = onlineCardState.indexOf(
            "obj.details.libraryState == ComputerDetails.LibraryState.AVAILABLE"
        )
        val unknownIndex = onlineCardState.indexOf(
            "obj.details.libraryState == ComputerDetails.LibraryState.UNKNOWN"
        )
        val openAppsIndex = onlineCardState.indexOf("R.string.pcview_card_action_open_apps")
        assertTrue(
            "host cards should advertise Open Library only for AVAILABLE Polaris Library hosts",
            availableIndex >= 0 &&
                onlineCardState.contains("R.string.pcview_card_action_open_library") &&
                onlineCardState.contains("R.string.pcview_card_hint_open_library")
        )
        assertTrue(
            "host cards should show a checking state while Polaris Library capability is UNKNOWN",
            unknownIndex > availableIndex &&
                onlineCardState.contains("R.string.pcview_card_action_checking_library") &&
                onlineCardState.contains("R.string.pcview_card_hint_checking_library")
        )
        assertTrue(
            "UNAVAILABLE/non-Polaris host cards should fall through to the standard app-list action and hint",
            openAppsIndex > unknownIndex &&
                onlineCardState.contains("R.string.pcview_card_hint_open_apps")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val startIndex = indexOf(startMarker)
        val endIndex = indexOf(endMarker, startIndex + startMarker.length)
        assertTrue("Missing source marker: $startMarker", startIndex >= 0)
        assertTrue("Missing source marker after $startMarker: $endMarker", endIndex > startIndex)
        return substring(startIndex, endIndex)
    }
}
