package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-side scenarios for the shared mapper, beyond the shared replay fixture: Android anchors
 * (the core's enqueue milliseconds), long sessions, and the arrival-only control for each
 * discriminating scenario.
 */
class SourceTimeMapperTest {
    private class Frame(val sourceUs: Long, val arrivalUs: Long)

    private fun rtpOf(baseRtp: Long, sourceUs: Long) = (baseRtp + sourceUs * 90 / 1000) and 0xFFFF_FFFFL

    /** What Android actually has: the core's enqueue time, whole milliseconds. */
    private fun enqueueAnchorUs(arrivalUs: Long) = arrivalUs / 1000 * 1000

    private fun targets(frames: List<Frame>, arrivalOnly: Boolean, warmup: Long = 1): List<Long> {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = warmup), 2, 16_666)
        return frames.mapIndexed { i, f ->
            val anchor = enqueueAnchorUs(f.arrivalUs)
            val point = mapper.observe(i + 1, rtpOf(900_000, f.sourceUs), anchor)
            assertTrue(point.valid)
            if (arrivalOnly) anchor + mapper.reserveUs else point.targetUs
        }
    }

    /** The oracle: after warm-up, target spacing equals source spacing within one slew step. */
    private fun followsSource(frames: List<Frame>, targets: List<Long>, fromIndex: Int = 1): Boolean =
        (fromIndex until frames.size).all { i ->
            val wanted = frames[i].sourceUs - frames[i - 1].sourceUs
            val got = targets[i] - targets[i - 1]
            kotlin.math.abs(got - wanted) <= 101
        }

    private fun scenario(sourceIntervalsUs: List<Long>, arrivalJitterUs: List<Long>, count: Int): List<Frame> {
        var source = 0L
        return (0 until count).map { i ->
            if (i > 0) source += sourceIntervalsUs[(i - 1) % sourceIntervalsUs.size]
            Frame(source, 1_000_000 + source + 20_000 + arrivalJitterUs[i % arrivalJitterUs.size])
        }
    }

    @Test
    fun irregularSourceCadenceSurvivesIndependentArrivalJitter() {
        val frames = scenario(listOf(9_000, 17_000, 11_000, 23_000), listOf(0, 3_000, 1_000, 5_000), 200)
        assertTrue(followsSource(frames, targets(frames, arrivalOnly = false)))
        assertFalse(followsSource(frames, targets(frames, arrivalOnly = true)))
    }

    @Test
    fun equalArrivalsWithDifferentSourceCadenceGetDifferentTargets() {
        // Arrivals every 8.333 ms whatever the source did; the source alternates 6 and 10.667 ms.
        var source = 0L
        val frames = (0 until 240).map { i ->
            if (i > 0) source += if (i % 2 == 1) 6_000 else 10_667
            Frame(source, 1_000_000 + 30_000 + i * 8_333L)
        }
        assertTrue(followsSource(frames, targets(frames, arrivalOnly = false)))
        assertFalse(followsSource(frames, targets(frames, arrivalOnly = true)))
    }

    @Test
    fun equalSourceCadenceWithDifferentArrivalsGetsTheSameTargetSpacing() {
        // 90 FPS from the host, delivered two different ways.
        val calm = scenario(listOf(11_111), listOf(0, 0, 0, 1_000), 270)
        var seed = 12345L
        val jitter = (0 until 64).map {
            seed = (seed * 1103515245 + 12345) and 0x7FFF_FFFF
            seed % 8_000
        }
        val rough = scenario(listOf(11_111), jitter, 270)
        val calmTargets = targets(calm, arrivalOnly = false)
        val roughTargets = targets(rough, arrivalOnly = false)
        assertTrue(followsSource(calm, calmTargets))
        assertTrue(followsSource(rough, roughTargets))
        // Whatever the delivery, the spacing differs only by the bounded offset slew.
        for (i in 1 until calm.size) {
            val difference = (calmTargets[i] - calmTargets[i - 1]) - (roughTargets[i] - roughTargets[i - 1])
            assertTrue("frame $i differs by $difference us", kotlin.math.abs(difference) <= 101)
        }
        assertFalse(followsSource(rough, targets(rough, arrivalOnly = true)))
    }

    @Test
    fun aLocalDecoderStallDoesNotMoveLaterTargets() {
        // Frames 40 to 44 are held up locally and arrive in a burst just behind frame 45; the
        // source never paused.
        val base = scenario(listOf(16_667), listOf(0), 120)
        val frames = base.mapIndexed { i, f ->
            if (i in 40..45) Frame(f.sourceUs, base[45].arrivalUs + (i - 40) * 100L) else f
        }
        val mapped = targets(frames, arrivalOnly = false)
        assertTrue(followsSource(frames, mapped))
        assertFalse(followsSource(frames, targets(frames, arrivalOnly = true)))
    }

    @Test
    fun hostClockDriftInsideTheSlewBudgetIsFollowedWithoutAJump() {
        // The host clock runs 500 ppm fast: anchor minus source falls by half a millisecond a
        // second, inside the one-millisecond-per-second budget.
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        var last = 0L
        for (i in 0 until 60 * 60) {
            val sourceUs = i * 16_667L
            val anchor = enqueueAnchorUs(1_000_000 + sourceUs - sourceUs / 2000 + 20_000)
            val point = mapper.observe(i + 1, rtpOf(900_000, sourceUs), anchor)
            assertTrue(point.active)
            assertEquals("no reset in a drifting but continuous stream", 1L, point.epoch)
            if (i > 0) {
                val step = point.targetUs - last
                assertTrue("step $step at $i", step in 16_667L - 101..16_667L + 101)
            }
            last = point.targetUs
        }
    }

    @Test
    fun aThirteenHourSessionCrossesTheRtpWrapWithoutAReset() {
        // 2^32 ticks at 90 kHz is about 13 h 15 min; 0.9 s steps keep the replay small.
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 900_000)
        var rtp = 0xFFFF_0000L
        var lastSource = -1L
        val frames = 54_000
        for (i in 1..frames) {
            val point = mapper.observe(i, rtp, 1_000_000L + i * 900_000L)
            assertTrue(point.valid)
            assertTrue(point.sourceUs > lastSource)
            lastSource = point.sourceUs
            rtp = (rtp + 81_000) and 0xFFFF_FFFFL
        }
        assertEquals(1L, mapper.epoch)
        assertEquals((frames - 1) * 900_000L, lastSource)
        assertTrue(lastSource > 13L * 3600 * 1_000_000)
    }

    @Test
    fun theLongWrapReplayStaysBoundedAndAStalePointCannotTeachTheNextEpoch() {
        // The Linux mapper test's independent checks, replayed here.
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 10_000)
        var raw = 0xFFFF_FFF0L
        for (i in 1..6000) {
            val point = mapper.observe(i, raw, 100_000 + i * 10_000L)
            assertTrue(point.valid)
            assertTrue(mapper.historySize <= 1024)
            raw = (raw + 900) and 0xFFFF_FFFFL
        }
        val old = mapper.observe(6001, raw, 60_110_000)
        mapper.reset()
        mapper.ready(old, old.targetUs)
        assertEquals(3000L, mapper.reserveUs)
    }

    @Test
    fun repeatedAndOlderFramesNeverMoveTheBaseline() {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        val first = mapper.observe(10, 90_000, 100_000)
        assertEquals(SourceTimeMapper.STATE_SOURCE_TIME, first.state)
        assertEquals("duplicate_timestamp", mapper.observe(11, 90_000, 101_000).reason)
        assertEquals("stale_frame", mapper.observe(11, 91_500, 102_000).reason)
        assertEquals("stale_frame", mapper.observe(9, 91_500, 102_000).reason)
        val next = mapper.observe(12, 91_800, 120_000)
        assertEquals(20_000L, next.sourceUs)
        assertEquals(first.epoch, next.epoch)
    }

    @Test
    fun anAllZeroStreamNeverGetsASourceTime() {
        val mapper = SourceTimeMapper()
        for (i in 1..120) {
            val point = mapper.observe(i, 0, 100_000 + i * 16_667L)
            assertFalse(point.valid)
            assertEquals(SourceTimeMapper.REASON_SOURCE_TIMESTAMP_MISSING, point.reason)
        }
    }

    @Test
    fun halfRangeAndBackwardStepsAndLongGapsStartNewEpochs() {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        val start = mapper.observe(1, 90_000, 100_000)
        val half = mapper.observe(2, (90_000 + 0x8000_0000L) and 0xFFFF_FFFFL, 110_000)
        assertEquals(SourceTimeMapper.REASON_SOURCE_DISCONTINUITY, half.reason)
        assertNotEquals(start.epoch, half.epoch)
        val back = mapper.observe(3, ((90_000 + 0x8000_0000L) - 1500) and 0xFFFF_FFFFL, 120_000)
        assertEquals(SourceTimeMapper.REASON_SOURCE_DISCONTINUITY, back.reason)
        val gap = mapper.observe(4, ((90_000 + 0x8000_0000L) - 1500 + 90_001) and 0xFFFF_FFFFL, 1_200_000)
        assertEquals(SourceTimeMapper.REASON_SOURCE_DISCONTINUITY, gap.reason)
        val near = mapper.observe(5, ((90_000 + 0x8000_0000L) - 1500 + 90_001 + 81_000) and 0xFFFF_FFFFL, 2_100_000)
        assertEquals("a 0.9 s step is continuity", SourceTimeMapper.REASON_NONE, near.reason)
        assertEquals(gap.epoch, near.epoch)
    }

    @Test
    fun targetsNeverGoBackwardsWithinAnEpoch() {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        var last = Long.MIN_VALUE
        for (i in 1..500) {
            // A minimum that keeps dropping pulls the offset down by up to 100 us a frame.
            val point = mapper.observe(i, 90_000L + i * 90, 100_000L + i * 1_000 - i * 50)
            assertTrue(point.targetUs > last)
            last = point.targetUs
        }
    }

    @Test
    fun aHitchIsPressureNotLinkJitter() {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        val point = mapper.observe(1, 90_000, 100_000)
        mapper.ready(point, 100_000 + 30_000)
        assertTrue(mapper.pressure)
        assertEquals(3000L, mapper.reserveUs)
    }

    @Test
    fun theReserveIsCappedByCapacityAndSourcePeriod() {
        val latency = SourceTimeMapper(SourceTimePolicy(), 1, 8_333)
        assertEquals(8_333L, latency.reserveCapUs())
        val balanced = SourceTimeMapper(SourceTimePolicy(), 2, 8_333)
        assertEquals(12_000L, balanced.reserveCapUs())
        assertEquals(3_000L, balanced.reserveUs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPolicyOutsideTheContractBoundsIsRefused() {
        SourceTimeMapper(SourceTimePolicy(reserveMaximumUs = 20_000))
    }
}
