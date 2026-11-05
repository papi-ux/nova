package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionVrr
import com.papi.nova.binding.video.sourcetime.DisplayFacts
import com.papi.nova.binding.video.sourcetime.DisplayPresentationCapability
import com.papi.nova.binding.video.sourcetime.PresentationTruth
import com.papi.nova.binding.video.sourcetime.SourceTimeEffective
import com.papi.nova.binding.video.sourcetime.SourceTimePacingStatus
import com.papi.nova.binding.video.sourcetime.SourceTimeReason
import com.papi.nova.binding.video.sourcetime.SourceTimeRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaHudSourceTimeChipsTest {
    private fun truth(
        client: SourceTimePacingStatus,
        arr: Boolean? = null,
        sdk: Int = 33,
        host: PolarisSessionVrr.Reading = PolarisSessionVrr.Reading.Absent,
        presented: Boolean = false,
    ) = PresentationTruth.from(client, DisplayPresentationCapability.from(DisplayFacts(sdk, arr, 120f)), host, true, 41, 120f, presented)

    private fun labels(truth: PresentationTruth?) = NovaHudQualityChips.from(null, 120f, truth).map { it.label }

    @Test
    fun aFixedRefreshStreamPacedByHostTimeSaysSoAndNeverVrr() {
        val fixed = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN)
        assertEquals(listOf("Display mode 120 Hz", "Source-paced"), labels(truth(fixed)))
    }

    @Test
    fun aHighFixedRefreshNeverMakesAVrrChip() {
        // The negative control: a 120-165 Hz panel, the host fresh and source-pacing, and still
        // no adaptive refresh evidence from this device: no VRR chip of either kind.
        val fixed = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.NONE)
        val host = PolarisSessionVrr.Reading.Present(PolarisSessionVrr(1, 41, "true", "launch", true, true, "source_time",
            null, "source_driven", PolarisSessionVrr.HostPacing(true, "source_time")))
        for (hz in listOf(90f, 120f, 144f, 165f)) {
            val chips = NovaHudQualityChips.from(null, hz, truth(fixed, host = host)).map { it.label }
            assertFalse(chips.any { it.startsWith("VRR") })
        }
    }

    @Test
    fun vrrActiveAppearsOnlyWithEveryPieceOfEvidence() {
        val adaptive = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_VRR,
            SourceTimeReason.NONE, vrrRequested = true, vrrEffective = true)
        val host = PolarisSessionVrr.Reading.Present(PolarisSessionVrr(1, 41, "true", "launch", true, true, "source_time",
            null, "source_driven", PolarisSessionVrr.HostPacing(true, "source_time")))
        assertTrue("VRR active" in labels(truth(adaptive, arr = true, sdk = 36, host = host, presented = true)))
        assertFalse("VRR active" in labels(truth(adaptive, arr = true, sdk = 36, host = host, presented = false)))
        assertTrue("VRR requested" in labels(truth(adaptive, arr = true, sdk = 36, host = host, presented = false)))
    }

    @Test
    fun aFallbackIsShownOnlyToAPlayerWhoTurnedItOn() {
        val explicit = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.LEGACY, SourceTimeReason.PACING_MODE_INCOMPATIBLE)
        assertEquals(listOf("Display mode 120 Hz", "Source pacing: Needs Balanced frame pacing"), labels(truth(explicit)))
        val automatic = explicit.copy(requested = SourceTimeRequest.AUTO)
        assertEquals(listOf("Display mode 120 Hz"), labels(truth(automatic)))
        val off = SourceTimePacingStatus.legacy(SourceTimeRequest.OFF, SourceTimeReason.NOT_REQUESTED)
        assertEquals(listOf("Display mode 120 Hz"), labels(truth(off)))
        assertEquals(listOf("Display mode 120 Hz"), labels(null))
    }

    @Test
    fun theHudAsksOnlyWhileVideoIsArriving() {
        val state = File("src/main/java/com/papi/nova/ui/NovaHudUiState.kt").readText()
        assertTrue(state.contains("presentation.takeIf { mediaFresh })"))
        val hud = File("src/main/java/com/papi/nova/ui/NovaStreamHud.kt").readText()
        assertTrue(hud.contains("val currentDisplayModeHz = if (mediaFresh) displayModeHz?.invoke() else null"))
        assertTrue(hud.contains("presentation = if (mediaFresh) {\n                presentation?.invoke(lastSessionStatus, hostFresh, currentDisplayModeHz)"))
    }
}
