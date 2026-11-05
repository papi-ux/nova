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

/**
 * The renderer's counts and last frame window go to the host through the same reducer as its
 * state: client_runtime.source_time_pacing (design E.2) and client_presentation's render_fps and
 * dropped_frame_ratio (E.1), inside the ranges Polaris accepts and never as a zero it did not
 * measure. Robolectric only for org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PresentationTruthReportTest {
    private val fixed = SourceTimePacingStatus(
        SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN,
    )
    private val display = DisplayPresentationCapability.from(DisplayFacts(33, null, 120f))

    private fun truth(report: SourceTimeReport?) =
        PresentationTruth.from(fixed, display, PolarisSessionVrr.Reading.Absent, true, 41, 120f, report = report)

    private val mediaCodec = SourceTimeReport(
        renderer = SourceTimeReport.RENDERER_MEDIACODEC,
        connectionGeneration = 3,
        decoderGeneration = 7,
        surfaceGeneration = 2,
        reserveUs = 4_500,
        targetDelayUs = 32_456,
        counters = SourceTimeCounters.Snapshot(
            resets = mapOf(SourceTimeCounters.RESET_CODEC_RECOVERY to 1L, SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE to 2L),
            offsetSlewEvents = 12,
            sourceTimed = 3_500,
            sourceTimeMissing = 40,
            timingMetadataMissing = 3,
            staleEpoch = 6,
        ),
        scheduler = SourceTimeScheduler.Counters(
            queued = 1, released = 3_400, releasedLate = 25, superseded = 9, overflow = 4, arrivalFallback = 43, maxLatenessNs = 2_000_000,
            staleDropped = 2,
            inFlightHeld = 7,
        ),
        feedbackMode = SourceTimeReport.FEEDBACK_OBSERVE_PARTIAL,
        feedback = PresentFeedbackSink.Counters(released = 3_400, matched = 3_300, unmatched = 5, withinOneVsync = 3_250,
            maxAbsErrorNs = 9_000_000, sumAbsErrorNs = 1_000_000_000),
        window = FrameWindow(renderedFps = 59.876, intentionalDrops = 3, totalFrames = 120),
    )

    @Test
    fun theRuntimeObjectCarriesTheRenderersGenerationsAndCounts() {
        val json = ClientRuntimeSnapshot.fromAppliedStream("RP6", 33, sourceTimePacing = truth(mediaCodec)).toJson()
            .getJSONObject("source_time_pacing")
        assertEquals("mediacodec", json.getString("renderer"))
        assertEquals(3, json.getInt("connection_gen"))
        assertEquals(7, json.getInt("decoder_gen"))
        assertEquals(2, json.getInt("surface_gen"))
        assertEquals(4_500L, json.getLong("reserve_us"))
        // W26 F2: the learned presentation delay is its own fact, in milliseconds.
        assertEquals(32.5, json.getDouble("target_delay_ms"), 0.0)
        assertEquals(32.456, truth(mediaCodec).targetDelayMs!!, 1e-9)
        assertEquals(12L, json.getLong("offset_slew_events"))
        val resets = json.getJSONObject("resets")
        assertEquals(1L, resets.getLong("codec_recovery"))
        assertEquals(2L, resets.getLong("clock_mapping_unstable"))
        assertEquals(2, resets.length())
        val counts = json.getJSONObject("counts")
        assertEquals("released on time", 3_375L, counts.getLong("on_slot"))
        assertEquals(25L, counts.getLong("late_presented"))
        assertEquals(9L, counts.getLong("late_superseded"))
        assertEquals(4L, counts.getLong("queue_overflow"))
        assertEquals(40L, counts.getLong("source_time_missing"))
        assertEquals(3L, counts.getLong("timing_metadata_missing"))
        // W26 F3: frames aimed by arrival because their timing was made under an old epoch or
        // surface, and queued frames dropped when the fence moved.
        assertEquals(6L, counts.getLong("stale_epoch"))
        assertEquals(2L, counts.getLong("stale_epoch_dropped"))
        // W26 F6: vsyncs that held a waiting frame because a release was still in flight.
        assertEquals(7L, counts.getLong("in_flight_held"))
        assertEquals("observe_partial", json.getString("feedback_mode"))
        assertEquals(0.971, json.getDouble("feedback_coverage"), 0.0)
        // The state is still the same reducer's.
        assertEquals(fixed.copy(fallback = SourceTimeReason.DISPLAY_FIXED_REFRESH).toFramePacingState(),
            json.getString("frame_pacing_state"))
    }

    @Test
    fun whatARendererDoesNotMeasureIsLeftOutNotWrittenAsZero() {
        val pyrowave = truth(SourceTimeReport(SourceTimeReport.RENDERER_PYROWAVE)).toRuntimeJson()
        assertEquals("pyrowave", pyrowave.getString("renderer"))
        assertEquals("off", pyrowave.getString("feedback_mode"))
        for (key in listOf("connection_gen", "decoder_gen", "surface_gen", "reserve_us", "target_delay_ms",
            "offset_slew_events", "resets", "counts", "feedback_coverage")) {
            assertFalse(key, pyrowave.has(key))
        }
        val noFeedbackYet = truth(mediaCodec.copy(feedback = PresentFeedbackSink.Counters(0, 0, 0, 0, 0, 0))).toRuntimeJson()
        assertFalse("no coverage before a frame was released", noFeedbackYet.has("feedback_coverage"))
        val none = truth(null)
        assertFalse(none.toRuntimeJson().has("renderer"))
        assertNull(none.renderFps)
        assertNull(none.droppedFrameRatio)
    }

    @Test
    fun theFrameWindowIsReportedInsidePolarisRangesOrNotAtAll() {
        val measured = truth(mediaCodec)
        assertEquals(59.88, measured.renderFps!!, 0.0)
        assertEquals(0.025, measured.droppedFrameRatio!!, 0.0)

        fun window(fps: Double, drops: Int, frames: Int) = truth(mediaCodec.copy(window = FrameWindow(fps, drops, frames)))
        // Drops are counted at render and frames at submit, so one window can hold more drops.
        assertEquals(1.0, window(60.0, 130, 120).droppedFrameRatio!!, 0.0)
        assertNull("no frames, no ratio", window(0.0, 0, 0).droppedFrameRatio)
        assertEquals("a window that rendered nothing is a measured zero", 0.0, window(0.0, 0, 0).renderFps!!, 0.0)
        // Polaris refuses the whole report outside 0-1000, so an impossible rate is left out.
        assertNull(window(1000.5, 0, 120).renderFps)
        assertNull(window(Double.NaN, 0, 120).renderFps)
        assertNull(window(-1.0, 0, 120).renderFps)
        assertEquals(1000.0, window(1000.0, 0, 120).renderFps!!, 0.0)
        assertNull(window(60.0, -1, 120).droppedFrameRatio)
    }

    @Test
    fun theRuntimeObjectStaysSmallWithEveryResetReason() {
        val every = (SourceTimeCounters.RENDERER_RESETS + SourceTimeCounters.MAPPER_RESETS + SourceTimeCounters.RESET_UNATTRIBUTED)
            .associateWith { Long.MAX_VALUE }
        val big = mediaCodec.copy(
            connectionGeneration = Int.MAX_VALUE, decoderGeneration = Int.MAX_VALUE, surfaceGeneration = Int.MAX_VALUE,
            reserveUs = Long.MAX_VALUE,
            counters = SourceTimeCounters.Snapshot(every, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
            scheduler = SourceTimeScheduler.Counters(2, Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        )
        val bytes = truth(big).toRuntimeJson().toString().toByteArray().size
        // Polaris keeps client_runtime whole up to 8192 bytes; this object leaves most of that.
        assertTrue("source_time_pacing is $bytes bytes", bytes < 1_536)
        for (forbidden in listOf("token", "uuid", "serial", "trace")) {
            assertFalse(forbidden, truth(big).toRuntimeJson().toString().contains(forbidden))
        }
    }

    @Test
    fun feedbackModeSaysWhetherASinkObservesAndWhetherItCanSeeEveryFrame() {
        assertEquals("off", SourceTimeReport.feedbackMode(false, 36))
        assertEquals("observe_partial", SourceTimeReport.feedbackMode(true, 30))
        assertEquals("observe_partial", SourceTimeReport.feedbackMode(true, 33))
        assertEquals("observe", SourceTimeReport.feedbackMode(true, 34))
    }
}
