package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client_runtime counts (design E.2) read from what the shared mapper returns, never from
 * inside it: resets by reason, offset slew events and how each frame was aimed.
 */
class SourceTimeCountersTest {
    private fun rtp(ticks: Long) = (900_000L + ticks) and 0xFFFF_FFFFL

    /** Whole 90 kHz ticks at 60 fps, exact microsecond anchors, the host [ppm] fast. */
    private fun stream(mapper: SourceTimeMapper, counters: SourceTimeCounters, frames: IntRange, ppm: Long = 0) {
        for (i in frames) {
            val ticks = i * 1500L
            val sourceUs = ticks * 1000 / 90
            counters.onPoint(mapper.observe(i + 1, rtp(ticks), 1_000_000L + sourceUs - sourceUs * ppm / 1_000_000L + 20_000L))
        }
    }

    @Test
    fun aSteadyStreamHasNoResetsAndNoSlew() {
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val counters = SourceTimeCounters()
        stream(mapper, counters, 0 until 600)
        val snapshot = counters.snapshot()
        assertEquals("the stream's first epoch is its start, not a reset", emptyMap<String, Long>(), snapshot.resets)
        assertEquals(0L, snapshot.offsetSlewEvents)
    }

    @Test
    fun warmUpSettlingIsNeitherSlewNorReset() {
        // The first frames arrive late and the lower envelope settles during warm-up; that is the
        // mapping being found, not an offset slewing once warm, and (since warm-up estimates never
        // seed the divergence reference) not a reset either, even beyond the 5 ms tolerance.
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val counters = SourceTimeCounters()
        val offsets = mutableSetOf<Long>()
        for (i in 0 until 600) {
            val ticks = i * 1500L
            val sourceUs = ticks * 1000 / 90
            val lateUs = if (i < 4) (4 - i) * 2_000L else 0L
            val point = mapper.observe(i + 1, rtp(ticks), 1_000_000L + sourceUs + 20_000L + lateUs)
            counters.onPoint(point)
            offsets += point.baseUs - point.sourceUs
        }
        assertTrue("the warm-up offset did settle: $offsets", offsets.size > 1)
        assertEquals(emptyMap<String, Long>(), counters.snapshot().resets)
        assertEquals(0L, counters.snapshot().offsetSlewEvents)
    }

    @Test
    fun driftInsideTheBudgetIsCountedAsSlewNotAsResets() {
        // 500 ppm: inside the one-millisecond-per-second budget, so the offset follows by slewing.
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val counters = SourceTimeCounters()
        stream(mapper, counters, 0 until 3600, ppm = 500)
        val snapshot = counters.snapshot()
        assertEquals(emptyMap<String, Long>(), snapshot.resets)
        assertTrue("slew events: ${snapshot.offsetSlewEvents}", snapshot.offsetSlewEvents > 0)
    }

    @Test
    fun theMappersOwnResetsAreNamedByTheirFirstPoint() {
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val counters = SourceTimeCounters()
        stream(mapper, counters, 0 until 30)
        // A forward source gap over one second: source_discontinuity.
        counters.onPoint(mapper.observe(31, rtp(29 * 1500L + 2 * 90_000L), 1_000_000L + 2_500_000L))
        // The anchor going backwards: clock_mapping_unstable.
        counters.onPoint(mapper.observe(32, rtp(29 * 1500L + 2 * 90_000L + 1500L), 1_000_000L))
        assertEquals(
            mapOf(SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE to 1L, SourceTimeMapper.REASON_SOURCE_DISCONTINUITY to 1L),
            counters.snapshot().resets,
        )
    }

    @Test
    fun aRendererResetIsNamedOnceAndAStalePointNeverCountsAgain() {
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val counters = SourceTimeCounters()
        stream(mapper, counters, 0 until 30)
        // A point observed just before the reset, recorded just after it.
        val before = mapper.observe(31, rtp(30 * 1500L), 1_000_000L + 30 * 16_667L + 20_000L)
        mapper.reset()
        counters.onReset(SourceTimeCounters.RESET_FOREGROUND, mapper.epoch)
        counters.onPoint(before)
        stream(mapper, counters, 31 until 60)
        assertEquals(mapOf(SourceTimeCounters.RESET_FOREGROUND to 1L), counters.snapshot().resets)
    }

    @Test
    fun aStalePointNeverMovesTheSlewCount() {
        // A point from an epoch already replaced carries the old mapping's offset; comparing it with
        // the new epoch's would count two slews that never happened.
        fun warm(epoch: Long, frame: Int, offset: Long) =
            SourceTimeMapper.Point(epoch, frame, frame.toLong(), frame * 16_667L, frame * 16_667L + offset, 0, true, true, "none")
        val counters = SourceTimeCounters()
        counters.onPoint(warm(1, 1, 5_000))
        counters.onReset(SourceTimeCounters.RESET_DISPLAY_CHANGE, 2)
        for (frame in 2..10) counters.onPoint(warm(2, frame, 9_000))
        counters.onPoint(warm(1, 11, 5_000))
        counters.onPoint(warm(2, 12, 9_000))
        assertEquals(0L, counters.snapshot().offsetSlewEvents)
        assertEquals(mapOf(SourceTimeCounters.RESET_DISPLAY_CHANGE to 1L), counters.snapshot().resets)
    }

    @Test
    fun theResetVocabularyIsBounded() {
        val counters = SourceTimeCounters()
        counters.onReset("anything a caller makes up", 2)
        counters.onPoint(SourceTimeMapper.Point(3, 1, 1, 0, 0, 0, true, false, SourceTimeMapper.PHASE_WARMUP))
        assertEquals(mapOf(SourceTimeCounters.RESET_UNATTRIBUTED to 2L), counters.snapshot().resets)
    }

    @Test
    fun admissionsAreCountedByHowTheFrameWasAimedAndClearStartsAgain() {
        val counters = SourceTimeCounters()
        repeat(5) { counters.onAdmitted(SourceTimeCounters.Admission.SOURCE_TIMED) }
        repeat(2) { counters.onAdmitted(SourceTimeCounters.Admission.SOURCE_TIME_MISSING) }
        counters.onAdmitted(SourceTimeCounters.Admission.TIMING_METADATA_MISSING)
        counters.onReset(SourceTimeCounters.RESET_CODEC_RECOVERY, 2)
        val snapshot = counters.snapshot()
        assertEquals(listOf(5L, 2L, 1L), listOf(snapshot.sourceTimed, snapshot.sourceTimeMissing, snapshot.timingMetadataMissing))
        counters.clear()
        assertEquals(SourceTimeCounters.Snapshot(emptyMap(), 0, 0, 0, 0), counters.snapshot())
    }
}
