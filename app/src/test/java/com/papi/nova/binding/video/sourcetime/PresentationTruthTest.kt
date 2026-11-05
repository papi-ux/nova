package com.papi.nova.binding.video.sourcetime

import com.papi.nova.api.PolarisSessionVrr
import com.papi.nova.manager.ClientRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json in the payload tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PresentationTruthTest {
    private fun display(sdk: Int, arr: Boolean?) = DisplayPresentationCapability.from(DisplayFacts(sdk, arr, 120f))

    private fun host(generation: Long = 41, fresh: Boolean = true, effective: Boolean = true) =
        PolarisSessionVrr.Reading.Present(
            PolarisSessionVrr(1, generation, "true", "launch", true, effective,
                if (effective) "source_time" else "fixed_grid", if (effective) null else "not_requested",
                "source_driven", PolarisSessionVrr.HostPacing(fresh, "source_time")),
        )

    private val fixed = SourceTimePacingStatus(
        SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN,
    )

    @Test
    fun aFixedRefreshDeviceIsSourcePacedWithTheDisplaysOwnReason() {
        val truth = PresentationTruth.from(fixed, display(33, null), PolarisSessionVrr.Reading.Absent, true, 41, 120f)
        assertTrue(truth.sourcePaced)
        assertFalse(truth.vrrActive)
        assertEquals(
            "st1;requested=source_time;effective=source_time_fixed;vrr_requested=0;vrr_effective=0;fallback=display_fixed_refresh",
            truth.client.toFramePacingState(),
        )
        assertEquals(120, truth.displayModeHz)
        assertEquals(PresentationTruth.HostRecord.ABSENT, truth.hostRecord)
    }

    @Test
    fun vrrIsActiveOnlyWithEveryPieceOfEvidenceAtOnce() {
        val adaptive = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_VRR,
            SourceTimeReason.NONE, vrrRequested = true, vrrEffective = true)
        fun truth(
            client: SourceTimePacingStatus = adaptive,
            screen: DisplayPresentationCapability = display(36, true),
            record: PolarisSessionVrr.Reading = host(),
            current: Boolean = true,
            generation: Long = 41,
            presented: Boolean = true,
        ) = PresentationTruth.from(client, screen, record, current, generation, 120f, presented)

        assertTrue("the full set of evidence", truth().vrrActive)
        // Each missing or contradicting piece on its own withdraws the claim.
        assertFalse(truth(client = adaptive.copy(effective = SourceTimeEffective.SOURCE_TIME_FIXED)).vrrActive)
        assertFalse(truth(client = adaptive.copy(vrrRequested = false)).vrrActive)
        assertFalse(truth(client = adaptive.copy(vrrEffective = false)).vrrActive)
        assertFalse(truth(screen = display(36, false)).vrrActive)
        assertFalse(truth(screen = display(35, null)).vrrActive)
        assertFalse(truth(record = PolarisSessionVrr.Reading.Absent).vrrActive)
        assertFalse(truth(record = PolarisSessionVrr.Reading.Malformed("x")).vrrActive)
        assertFalse(truth(record = host(fresh = false)).vrrActive)
        assertFalse(truth(record = host(effective = false)).vrrActive)
        assertFalse("another generation's record", truth(record = host(generation = 40)).vrrActive)
        assertFalse("an out-of-date status", truth(current = false).vrrActive)
        assertFalse(truth(presented = false).vrrActive)
    }

    @Test
    fun theHostRecordIsTrustedOnlyCurrentAndForItsOwnGeneration() {
        fun record(r: PolarisSessionVrr.Reading, current: Boolean = true, generation: Long = 41) =
            PresentationTruth.from(fixed, display(33, null), r, current, generation, 60f)
        assertEquals(PresentationTruth.HostRecord.PRESENT, record(host()).hostRecord)
        assertTrue(record(host()).hostSourcePaced)
        assertEquals(PresentationTruth.HostRecord.STALE, record(host(), current = false).hostRecord)
        assertEquals(PresentationTruth.HostRecord.STALE, record(host(generation = 7)).hostRecord)
        assertFalse(record(host(generation = 7)).hostSourcePaced)
        assertEquals(PresentationTruth.HostRecord.MALFORMED, record(PolarisSessionVrr.Reading.Malformed("x")).hostRecord)
    }

    @Test
    fun aFallbackIsExplainedOnlyWhenAskedAndShownOnlyWhenAskedExplicitly() {
        val off = PresentationTruth.from(null, display(33, null), PolarisSessionVrr.Reading.Absent, true, 41, 60f)
        assertNull(off.fallback)
        assertFalse(off.prominent)
        val auto = PresentationTruth.from(
            SourceTimePacingStatus(SourceTimeRequest.AUTO, SourceTimeEffective.PENDING, SourceTimeReason.SOURCE_TIMESTAMP_MISSING),
            display(33, null), PolarisSessionVrr.Reading.Absent, true, 41, 60f)
        assertEquals(SourceTimeReason.SOURCE_TIMESTAMP_MISSING, auto.fallback)
        assertFalse("automatic falls back silently", auto.prominent)
        val explicit = PresentationTruth.from(
            SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.LEGACY, SourceTimeReason.PACING_MODE_INCOMPATIBLE),
            display(33, null), PolarisSessionVrr.Reading.Absent, true, 41, 60f)
        assertTrue(explicit.prominent)
        assertEquals("Needs Balanced frame pacing", PresentationTruth.plain(explicit.fallback!!))
    }

    @Test
    fun theRuntimeSnapshotCarriesTheSameStateAndNothingMore() {
        val truth = PresentationTruth.from(fixed, display(33, null), host(), true, 41, 120f)
        val json = ClientRuntimeSnapshot.fromAppliedStream("RP6", 33, sourceTimePacing = truth).toJson()
            .getJSONObject("source_time_pacing")
        assertEquals(truth.client.toFramePacingState(), json.getString("frame_pacing_state"))
        assertEquals("present", json.getString("host_record"))
        assertTrue(json.getBoolean("host_source_paced"))
        assertFalse(json.getBoolean("vrr_active"))
        assertEquals("false", json.getString("display_arr"))
        assertFalse(ClientRuntimeSnapshot.fromAppliedStream("RP6", 33).toJson().has("source_time_pacing"))
        val absent = PresentationTruth.from(fixed, display(33, null), PolarisSessionVrr.Reading.Absent, true, 41, 120f)
        assertFalse("an unknown host answer is not written as one", absent.toRuntimeJson().has("host_source_paced"))
        for (forbidden in listOf("token", "uuid", "serial", "counters")) {
            assertFalse(json.toString().contains(forbidden))
        }
    }

    @Test
    fun everyReasonHasPlainWords() {
        for (reason in SourceTimeReason.values()) {
            val words = PresentationTruth.plain(reason)
            assertTrue(reason.wire, words.isNotBlank() && !words.contains('_'))
        }
    }
}
