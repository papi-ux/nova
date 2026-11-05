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

    /** One frame's target, with the epoch and warmth of the point it came from. */
    private class Aim(val targetUs: Long, val epoch: Long, val active: Boolean)

    private fun targets(frames: List<Frame>, arrivalOnly: Boolean, warmup: Long = 1): List<Aim> {
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = warmup), 2, 16_666)
        return frames.mapIndexed { i, f ->
            val anchor = enqueueAnchorUs(f.arrivalUs)
            val point = mapper.observe(i + 1, rtpOf(900_000, f.sourceUs), anchor)
            assertTrue(point.valid)
            Aim(if (arrivalOnly) anchor + mapper.reserveUs else point.targetUs, point.epoch, point.active)
        }
    }

    /** Consecutive frames the oracle can compare: both warm, in one epoch. */
    private fun comparable(aims: List<Aim>): List<Int> =
        (1 until aims.size).filter { aims[it].active && aims[it - 1].active && aims[it].epoch == aims[it - 1].epoch }

    /**
     * The oracle: target spacing equals source spacing within one slew step, for every comparable
     * pair. Under the shared re-warm rule a frame that starts a new epoch, or is still warming,
     * is not comparable; the oracle still has to see nearly every frame, so it cannot pass by
     * comparing nothing.
     */
    private fun followsSource(frames: List<Frame>, aims: List<Aim>): Boolean {
        val pairs = comparable(aims)
        assertTrue("the oracle saw ${pairs.size} of ${frames.size - 1} pairs", pairs.size >= (frames.size - 1) * 9 / 10)
        return pairs.all { i ->
            val wanted = frames[i].sourceUs - frames[i - 1].sourceUs
            val got = aims[i].targetUs - aims[i - 1].targetUs
            kotlin.math.abs(got - wanted) <= 101
        }
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
        val both = comparable(calmTargets).toSet().intersect(comparable(roughTargets).toSet())
        assertTrue(both.size >= (calm.size - 1) * 9 / 10)
        for (i in both) {
            val difference = (calmTargets[i].targetUs - calmTargets[i - 1].targetUs) -
                (roughTargets[i].targetUs - roughTargets[i - 1].targetUs)
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
        // second, inside the one-millisecond-per-second budget. Exact (microsecond) anchors and
        // frames of whole 90 kHz ticks (1500 = 16.667 ms), so the only correction is the drift
        // itself, about 8 us a frame against a budget of about 16: the shared rule never re-warms.
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1), 2, 16_666)
        var last = 0L
        var lastSource = 0L
        for (i in 0 until 60 * 60) {
            val ticks = i * 1500L
            val sourceUs = ticks * 1000 / 90
            val anchor = 1_000_000 + sourceUs - sourceUs / 2000 + 20_000
            val point = mapper.observe(i + 1, (900_000 + ticks) and 0xFFFF_FFFFL, anchor)
            assertTrue("frame $i", point.active)
            assertEquals("no reset in a drifting but continuous stream", 1L, point.epoch)
            if (i > 0) {
                val step = point.targetUs - last
                assertTrue("step $step at $i", kotlin.math.abs(step - (sourceUs - lastSource)) <= 101)
            }
            last = point.targetUs
            lastSource = sourceUs
        }
    }

    /** Seeded one-sided arrival jitter, 0..[amplitudeUs], by the shared fixture's LCG. */
    private fun lcgJitter(amplitudeUs: Long, count: Int, seed: Long = 17): List<Long> {
        var state = seed and 0xFFFF_FFFFL
        return (0 until count).map {
            state = (state * 1_664_525L + 1_013_904_223L) and 0xFFFF_FFFFL
            state % (amplitudeUs + 1)
        }
    }

    @Test
    fun onMillisecondAnchorsDriftAndJitterNeverRewarm() {
        // Android's anchor is the core's enqueue time in whole milliseconds (ROOT D2). Under the
        // shared contract's instability revision 2, neither clock drift inside the slew budget nor
        // ordinary arrival jitter is instability: one epoch for the whole minute, the applied
        // offset moving at most one update's budget, and targets following the source. Aiming the
        // same frames by arrival fails the same oracle.
        for (ppm in listOf(50L, -50L, 500L, -500L)) {
            for (jitterUs in listOf(0L, 2_000L, 4_000L)) {
                val count = 60 * 60
                val jitter = lcgJitter(jitterUs, count)
                val frames = (0 until count).map { i ->
                    val sourceUs = i * 1500L * 1000 / 90
                    Frame(sourceUs, 1_000_000 + sourceUs + sourceUs * ppm / 1_000_000 + 20_000 + jitter[i])
                }
                val aims = targets(frames, arrivalOnly = false, warmup = 8)
                assertEquals("ppm=$ppm jitter=$jitterUs: one epoch", setOf(1L), aims.map { it.epoch }.toSet())
                assertTrue("ppm=$ppm jitter=$jitterUs", followsSource(frames, aims))
                assertFalse("ppm=$ppm jitter=$jitterUs", followsSource(frames, targets(frames, arrivalOnly = true, warmup = 8)))
            }
        }
    }

    @Test
    fun onMillisecondAnchorsAGenuineStepRewarmsOnceOnTheEighthConfirmingFrame() {
        // A real 30 ms jump in local arrival is instability: the step detector needs eight
        // consecutive forward frames beyond 20 ms, then starts one new epoch, and the mapping
        // settles on the new offset without re-warming again.
        val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
        val rewarmFrames = ArrayList<Int>()
        var last: SourceTimeMapper.Point? = null
        var settledErrorUs = 0L
        for (frame in 1..240) {
            val ticks = (frame - 1) * 1500L
            val sourceUs = ticks * 1000 / 90
            val truth = 1_020_000L + if (frame >= 101) 30_000L else 0L
            val point = mapper.observe(frame, (900_000 + ticks) and 0xFFFF_FFFFL, enqueueAnchorUs(sourceUs + truth))
            assertTrue(point.valid)
            if (last != null && point.epoch != last.epoch) {
                rewarmFrames += frame
                assertEquals(SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE, point.reason)
                assertFalse(point.active)
            }
            // The mapped base against the true offset, to the anchor's millisecond.
            if (frame >= 116) settledErrorUs = maxOf(settledErrorUs, kotlin.math.abs(point.baseUs - sourceUs - truth))
            last = point
        }
        assertEquals(listOf(108), rewarmFrames)
        assertTrue("settled error $settledErrorUs us", settledErrorUs <= 1000)
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
        var last: SourceTimeMapper.Point? = null
        var pairs = 0
        var epochs = mutableSetOf<Long>()
        for (i in 1..500) {
            // A minimum that keeps dropping, 5% a frame: the applied offset follows only as fast as
            // its budget allows, and the divergence soon re-warms (instability revision 2). A new
            // epoch invalidates the old targets, so chronology holds inside each epoch.
            val point = mapper.observe(i, 90_000L + i * 90, 100_000L + i * 1_000 - i * 50)
            epochs += point.epoch
            if (last != null && point.epoch == last.epoch) {
                assertTrue("frame $i", point.targetUs > last.targetUs)
                pairs++
            }
            last = point
        }
        assertTrue("the oracle compared $pairs pairs", pairs >= 450)
        assertTrue("this much drift is instability: ${epochs.size} epochs", epochs.size > 1)
    }

    @Test
    fun aLateStartupConvergesInWarmUpWithoutARewarm() {
        // A large first IDR completes reassembly late and the frames queued behind it drain 0.5 ms
        // apart until they are on time again; the anchor (whole milliseconds) never goes back.
        // Warm-up absorbs that, and since only steady estimates seed the divergence reference,
        // nothing re-warms later (the shared case late_startup_over_5ms, here on Android anchors).
        for (lateUs in listOf(6_000L, 12_000L, 20_000L, 50_000L)) {
            val mapper = SourceTimeMapper(SourceTimePolicy(), 2, 16_666)
            var lastExact = 0L
            val epochs = mutableSetOf<Long>()
            for (frame in 1..600) {
                val ticks = (frame - 1) * 1500L
                val nominal = 1_020_000L + ticks * 1000 / 90
                val exact = if (frame == 1) nominal + lateUs else maxOf(nominal, lastExact + 500)
                lastExact = exact
                val point = mapper.observe(frame, (900_000 + ticks) and 0xFFFF_FFFFL, enqueueAnchorUs(exact))
                assertTrue(point.valid)
                epochs += point.epoch
            }
            assertEquals("late by $lateUs us", setOf(1L), epochs)
        }
    }

    @Test
    fun reserveReleaseEarnsCreditOnlyAfterTheCleanHold() {
        // The shared mapper (b468357e on): release starts 2 s after the reserve last wanted more,
        // and the first release counts only the time past that hold, not the whole gap since the
        // previous decoded frame. Readiness here forgets after 0.1 s so the clean frames decide.
        val mapper = SourceTimeMapper(SourceTimePolicy(warmupFrames = 1, readinessWindowUs = 100_000), 2, 16_666)
        val points = (1..200).map { frame ->
            val ticks = (frame - 1) * 1500L
            mapper.observe(frame, (900_000 + ticks) and 0xFFFF_FFFFL, 1_000_000 + ticks * 1000 / 90)
        }
        // Eleven frames 8 ms late raise the reserve from 3 ms to 8.5 ms, half a millisecond each.
        for (i in 0 until 11) mapper.ready(points[i], points[i].baseUs + 8_000)
        assertEquals(8_500L, mapper.reserveUs)
        // Clean from frame 30, which starts the hold; frame 180 is 2.5 s later, 0.5 s past the hold.
        mapper.ready(points[29], points[29].baseUs)
        assertEquals(8_500L, mapper.reserveUs)
        mapper.ready(points[179], points[179].baseUs)
        assertEquals("0.5 s at 250 us/s, not the whole 2.5 s gap", 8_375L, mapper.reserveUs)
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
