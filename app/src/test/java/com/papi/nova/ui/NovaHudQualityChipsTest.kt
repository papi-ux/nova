package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaHudQualityChipsTest {
    @Test
    fun onlyTheHostsHdr10StreamMakesAnHdrChip() {
        assertEquals(listOf("HDR10"), labels(owned(hdr = "hdr10")))
        assertEquals(listOf("HDR10"), labels(owned(hdr = " HDR10 ")))
        // Ten-bit SDR is not HDR, and an older host that reports no mode shows nothing.
        assertEquals(emptyList<String>(), labels(owned(hdr = "sdr_10bit")))
        assertEquals(emptyList<String>(), labels(owned(hdr = "sdr_8bit")))
        assertEquals(emptyList<String>(), labels(owned(hdr = "")))
    }

    @Test
    fun hdr10ForAStreamThisDeviceDoesNotOwnMakesNoChip() {
        val hdr = owned(hdr = "hdr10")
        for (other in listOf(
            hdr.copy(clientRole = "viewer", ownedByClient = false),
            hdr.copy(clientRole = "none", ownedByClient = false),
            hdr.copy(authorityContractValid = false),
            hdr.copy(shutdownRequested = true),
        )) {
            assertEquals(emptyList<String>(), labels(other))
        }
        assertEquals(emptyList<String>(), labels(null))
    }

    @Test
    fun hdr10BesideADowngradeReportMakesNoChip() {
        val contradicted = owned(hdr = "hdr10").let {
            it.copy(health = it.health.copy(primaryIssue = "hdr_downgraded"))
        }
        assertEquals(emptyList<String>(), labels(contradicted))
    }

    @Test
    fun aStaleHostEchoOfTheClientsRefreshRateCannotMakeTheHzChip() {
        // Polaris keeps each device's last presentation report across sessions, and calls
        // it synced. Even a synced echo is never the source of the chip.
        val echo = owned(hdr = "sdr_8bit").copy(
            clientPresentation = PolarisSessionStatus.ClientPresentationStatus(
                status = "synced",
                appliedRefreshRateHz = 120.0,
                targetRefreshRateHz = 120.0
            )
        )
        assertEquals(emptyList<String>(), labels(echo, displayModeHz = null))
        assertEquals(listOf("Display mode 60 Hz"), labels(echo, displayModeHz = 60f))
    }

    @Test
    fun theDisplayChipIsTheModeRateAndOnlyAPositiveOne() {
        val chip = NovaHudQualityChips.from(null, displayModeHz = 119.88f).single()
        assertEquals("Display mode 120 Hz", chip.label)
        assertEquals("Display mode: 120 hertz", chip.description)
        for (unknown in listOf(0f, -60f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(emptyList<String>(), labels(null, displayModeHz = unknown))
        }
    }

    @Test
    fun theHudStateDropsChipsWhoseReadingsHaveGoneStale() {
        val status = owned(hdr = "hdr10")
        assertEquals(listOf("HDR10", "Display mode 120 Hz"), hud(status, hostFresh = true, mediaFresh = true).qualityChips.map { it.label })
        // A host answer older than the HUD's window no longer speaks for the stream.
        assertEquals(listOf("Display mode 120 Hz"), hud(status, hostFresh = false, mediaFresh = true).qualityChips.map { it.label })
        // A display rate beside video that stopped arriving describes nothing.
        assertEquals(listOf("HDR10"), hud(status, hostFresh = true, mediaFresh = false).qualityChips.map { it.label })
        assertEquals(emptyList<String>(),
            hud(status.copy(streamingActive = false), hostFresh = true, mediaFresh = false).qualityChips.map { it.label })
    }

    private fun labels(status: PolarisSessionStatus?, displayModeHz: Float? = null) =
        NovaHudQualityChips.from(status, displayModeHz).map { it.label }

    private fun hud(status: PolarisSessionStatus, hostFresh: Boolean, mediaFresh: Boolean) = NovaHudUiState.from(
        mode = NovaHudMode.PERFORMANCE,
        fps = 119.0,
        targetFps = 120.0,
        latencyMs = 12,
        codec = "hevc",
        bitrateKbps = 30000,
        width = 1920,
        height = 1080,
        status = status,
        sparklineSamples = listOf(119f),
        mediaFresh = mediaFresh,
        hostFresh = hostFresh,
        displayModeHz = 120f
    )

    private fun owned(hdr: String) = PolarisSessionStatus(
        state = "streaming",
        streamingActive = true,
        clientRole = "owner",
        ownedByClient = true,
        authorityContractValid = true,
        dynamicRange = if (hdr.trim().equals("hdr10", ignoreCase = true)) 1 else 0,
        health = PolarisSessionStatus.HealthStatus(grade = "good", hdrEffectiveMode = hdr)
    )
}
