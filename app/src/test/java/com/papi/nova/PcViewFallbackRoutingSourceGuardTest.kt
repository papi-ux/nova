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

        val resumeIndex = openBestPlaySurface.indexOf("resumeOrWatchRunningGame(computer)")
        val libraryAvailableIndex = openBestPlaySurface.indexOf(
            "computer.libraryState == ComputerDetails.LibraryState.AVAILABLE"
        )
        val libraryUnknownIndex = openBestPlaySurface.indexOf(
            "computer.libraryState == ComputerDetails.LibraryState.UNKNOWN"
        )
        val appListFallbackIndex = openBestPlaySurface.indexOf("doAppList(computer, false, false)")

        assertTrue(
            "running sessions should still route to resume/watch before choosing a library or app-list surface",
            resumeIndex >= 0 && resumeIndex < libraryAvailableIndex
        )
        assertTrue(
            "Polaris-capable hosts should open Nova Library only after the capability probe marks the library available",
            libraryAvailableIndex >= 0 &&
                openBestPlaySurface.contains("doNovaLibrary(computer)")
        )
        assertTrue(
            "unknown library capability should trigger the Polaris probe and stay on the dashboard instead of falling through to the app list",
            libraryUnknownIndex > libraryAvailableIndex &&
                openBestPlaySurface.contains("maybeProbeLibraryReadiness(computerObject)") &&
                openBestPlaySurface.contains("R.string.pcview_library_checking")
        )
        assertTrue(
            "Apollo/Sunshine/non-Polaris hosts must fall back to the standard app-list flow after the available/unknown library branches",
            appListFallbackIndex > libraryUnknownIndex
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
