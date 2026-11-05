package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.api.PolarisSessionVrr
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.binding.video.sourcetime.DisplayFacts
import com.papi.nova.binding.video.sourcetime.DisplayPresentationCapability
import com.papi.nova.binding.video.sourcetime.PresentationTruth
import com.papi.nova.binding.video.sourcetime.SourceTimeEffective
import com.papi.nova.binding.video.sourcetime.SourceTimePacingStatus
import com.papi.nova.binding.video.sourcetime.SourceTimeReason
import com.papi.nova.binding.video.sourcetime.SourceTimeRequest
import com.papi.nova.manager.NovaLiveBitratePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCommandCenterPresentationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun truth(client: SourceTimePacingStatus) = PresentationTruth.from(client,
        DisplayPresentationCapability.from(DisplayFacts(33, null, 120f)), PolarisSessionVrr.Reading.Absent, true, 41, 120f)
    private fun status() = PolarisSessionStatus("streaming", streamingActive = true, appSessionId = "session", sessionGeneration = 41)
    private fun sample(at: Long = 1000) = PerfOverlaySample(60.0, 60.0, 60.0, 1920, 1080, "HEVC", 2, 1, 3.0, 0.0,
        monotonicTimestampMs = at, sessionGeneration = 41)

    @Test
    fun aSourcePacedFixedRefreshStreamReadsAsPlainFacts() {
        val rows = commandCenterPresentation(context, truth(SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME,
            SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN))).toMap()
        assertEquals("120 Hz", rows["Display mode"])
        assertEquals("Yes", rows["Paced by host frame times"])
        assertEquals("No", rows["Variable refresh requested"])
        assertEquals("No", rows["Variable refresh active"])
        assertEquals("Fixed-refresh display", rows["Why not"])
        assertEquals("Unavailable", rows["Presentation delay"])
        val measured = PresentationTruth.from(SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME,
            SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.NONE), null, PolarisSessionVrr.Reading.Absent, true, 41, 120f,
            report = com.papi.nova.binding.video.sourcetime.SourceTimeReport("mediacodec", targetDelayUs = 32_450))
        assertEquals("32.5 ms", commandCenterPresentation(context, measured).toMap()["Presentation delay"])
    }

    @Test
    fun anAutomaticFallbackIsRecordedHereAndOffSaysOff() {
        val auto = commandCenterPresentation(context, truth(SourceTimePacingStatus(SourceTimeRequest.AUTO,
            SourceTimeEffective.PENDING, SourceTimeReason.SOURCE_TIMESTAMP_MISSING))).toMap()
        assertEquals("No", auto["Paced by host frame times"])
        assertEquals("Host sends no frame times", auto["Why not"])
        val off = commandCenterPresentation(context, truth(SourceTimePacingStatus.legacy(SourceTimeRequest.OFF,
            SourceTimeReason.NOT_REQUESTED))).toMap()
        assertEquals("Off", off["Paced by host frame times"])
        assertEquals("None", off["Why not"])
    }

    @Test
    fun withoutCurrentVideoThereAreNoPresentationFacts() {
        val t = truth(SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.NONE))
        val current = commandCenterStream(context, status(), NovaLiveBitratePresentation(), sample(), 1000, true, 50000, emptyList(), t)
        assertEquals(6, current.presentation.size)
        val stale = commandCenterStream(context, status(), NovaLiveBitratePresentation(), sample(at = 1000), 60_000, true, 50000, emptyList(), t)
        assertTrue(stale.presentation.isEmpty())
        val none = commandCenterStream(context, status(), NovaLiveBitratePresentation(), null, 1000, true, 50000, emptyList(), t)
        assertTrue(none.presentation.isEmpty())
    }
}
