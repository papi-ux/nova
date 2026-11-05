package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTimeSchedulerTest {
    private val vsync = 8_333_333L
    private val half = vsync / 2

    private fun slots(fromNs: Long, count: Int = 2) = LongArray(count) { fromNs + (it + 1) * vsync }

    @Test
    fun aFrameIsReleasedAtTheFirstRefreshAtOrAfterItsTarget() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 9_000_000)
        val decision = scheduler.onVsync(slots(0), vsync)
        assertEquals(1, decision.release!!.index)
        assertEquals(vsync, decision.releaseAtNs)
    }

    @Test
    fun aFrameDueBeyondTheNextTwoRefreshesIsHeldNotReleasedEarly() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 30_000_000)
        assertNull(scheduler.onVsync(slots(0, count = 6), vsync).release)
        // Two refreshes later the same frame is in reach.
        val decision = scheduler.onVsync(slots(2 * vsync, count = 6), vsync)
        assertEquals(1, decision.release!!.index)
        assertTrue(decision.releaseAtNs >= 30_000_000 - half)
    }

    @Test
    fun ofSeveralFramesDueTheNewestIsShownAndTheOthersSuperseded() {
        val scheduler = SourceTimeScheduler(3)
        scheduler.admit(1, 10, 0, 1_000_000)
        scheduler.admit(2, 20, 0, 2_000_000)
        scheduler.admit(3, 30, 0, 100_000_000)
        val decision = scheduler.onVsync(slots(0), vsync)
        assertEquals(2, decision.release!!.index)
        assertEquals(listOf(1), decision.superseded.map { it.index })
        assertEquals(1, scheduler.counters().queued)
        assertEquals(1L, scheduler.counters().superseded)
    }

    @Test
    fun noRefreshIsUsedTwice() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, vsync)
        assertEquals(vsync, scheduler.onVsync(slots(0), vsync).releaseAtNs)
        scheduler.admit(2, 20, 0, vsync + 1)
        // The same refreshes offered again: the used one is skipped, the next is taken.
        val decision = scheduler.onVsync(slots(0), vsync)
        assertEquals(2, decision.release!!.index)
        assertEquals(2 * vsync, decision.releaseAtNs)
    }

    @Test
    fun aLateFrameIsShownOnceAtTheEarliestRefreshAndCounted() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 1_000_000)
        val decision = scheduler.onVsync(slots(100_000_000), vsync)
        assertEquals(1, decision.release!!.index)
        assertEquals(100_000_000 + vsync, decision.releaseAtNs)
        assertEquals(1L, scheduler.counters().releasedLate)
        assertNull(scheduler.onVsync(slots(100_000_000 + vsync), vsync).release)
    }

    @Test
    fun theQueueNeverExceedsItsCapacityAndPushesOutTheOldest() {
        val balanced = SourceTimeScheduler(2)
        assertTrue(balanced.admit(1, 10, 0, 50_000_000).isEmpty())
        assertTrue(balanced.admit(2, 20, 0, 60_000_000).isEmpty())
        assertEquals(listOf(1), balanced.admit(3, 30, 0, 70_000_000).map { it.index })
        assertEquals(2, balanced.counters().queued)
        val latency = SourceTimeScheduler(1)
        latency.admit(1, 10, 0, 50_000_000)
        assertEquals(listOf(1), latency.admit(2, 20, 0, 60_000_000).map { it.index })
        assertEquals(1L, latency.counters().overflow)
    }

    @Test
    fun aFrameWithoutSourceTimeIsAimedAtItsDecodeTimeAndKeepsOrder() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 20_000_000)
        // Decoded at 5 ms with no source time: never shown ahead of the timed frame before it.
        scheduler.admit(2, 20, 5_000_000, null)
        assertEquals(1L, scheduler.counters().arrivalFallback)
        val first = scheduler.onVsync(slots(10_000_000), vsync)
        assertEquals(2, first.release!!.index)
        assertEquals(listOf(1), first.superseded.map { it.index })
    }

    @Test
    fun clearForgetsWaitingFramesAndHandsThemBack() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 50_000_000)
        assertEquals(listOf(1), scheduler.clear().map { it.index })
        assertNull(scheduler.onVsync(slots(100_000_000), vsync).release)
    }

    // --- The scheduler with the mapper, against the display: the discriminating checks. ---

    private class Frame(val sourceUs: Long, val arrivalUs: Long)

    /**
     * A 120 Hz display, three milliseconds of decode, the shared mapper with a fixed 8 ms reserve
     * (so two deliveries are compared on the same footing) and the scheduler. Every target also
     * carries the same 25 ms of display pipeline, as frame timelines would report, so a frame
     * admitted on one vsync can still reach the refresh it is due at. Returns, per shown frame,
     * its refresh index. [arrivalOnly] aims frames by their arrival instead.
     */
    private fun shownRefreshes(frames: List<Frame>, arrivalOnly: Boolean): Map<Int, Long> {
        val policy = SourceTimePolicy(reserveStartUs = 8_000, reserveMaximumUs = 8_000, reserveReleaseUsPerSecond = 0)
        val mapper = SourceTimeMapper(policy, 2, 11_111)
        val scheduler = SourceTimeScheduler(2)
        val shown = HashMap<Int, Long>()
        var next = 0
        val endNs = (frames.last().arrivalUs + 100_000) * 1000
        var vsyncNs = 0L
        var maxQueued = 0
        while (vsyncNs < endNs) {
            while (next < frames.size && (frames[next].arrivalUs + 3_000) * 1000 <= vsyncNs) {
                val f = frames[next]
                val point = mapper.observe(next + 1, (900_000 + f.sourceUs * 90 / 1000) and 0xFFFF_FFFFL, f.arrivalUs)
                val decodedUs = f.arrivalUs + 3_000
                mapper.ready(point, decodedUs)
                val target = when {
                    arrivalOnly -> (f.arrivalUs + mapper.reserveUs) * 1000 + PIPELINE_NS
                    point.valid && point.active -> point.targetUs * 1000 + PIPELINE_NS
                    else -> null
                }
                scheduler.admit(next, 0, decodedUs * 1000, target)
                maxQueued = maxOf(maxQueued, scheduler.counters().queued)
                next++
            }
            val decision = scheduler.onVsync(slots(vsyncNs), vsync)
            decision.release?.let { shown[it.index] = decision.releaseAtNs / vsync }
            vsyncNs += vsync
        }
        assertTrue("the queue stayed bounded", maxQueued <= 2)
        return shown
    }

    private companion object {
        const val PIPELINE_NS = 25_000_000L
    }

    private fun stream(count: Int, sourceIntervalUs: (Int) -> Long, arrivalJitterUs: (Int) -> Long): List<Frame> {
        var source = 0L
        return (0 until count).map { i ->
            if (i > 0) source += sourceIntervalUs(i)
            Frame(source, 1_000_000 + source + 15_000 + arrivalJitterUs(i))
        }
    }

    private fun intervals(shown: Map<Int, Long>, from: Int): List<Long> {
        val indices = shown.keys.filter { it >= from }.sorted()
        return indices.zipWithNext { a, b -> shown[b]!! - shown[a]!! }
    }

    @Test
    fun theSameSourceCadenceIsShownTheSameWayWhateverItsDelivery() {
        // 90 FPS on 120 Hz. Calm delivery versus up to 4 ms of jitter (every eighth frame on
        // time, so both see the same lowest offset).
        var seed = 7L
        val jitter = (0 until 360).map {
            seed = (seed * 1103515245 + 12345) and 0x7FFF_FFFF
            if (it % 8 == 0) 0L else seed % 4_000
        }
        val calm = stream(360, { 11_111 }, { 0 })
        val rough = stream(360, { 11_111 }, { jitter[it] })
        assertEquals(intervals(shownRefreshes(calm, false), 20), intervals(shownRefreshes(rough, false), 20))
        assertNotEquals(intervals(shownRefreshes(calm, true), 20), intervals(shownRefreshes(rough, true), 20))
    }

    @Test
    fun differentSourceCadencesArrivingAlikeAreShownDifferently() {
        // Two hosts deliver identically every 11.111 ms; one rendered evenly, one unevenly.
        val even = stream(360, { 11_111 }, { 0 }).map { it.arrivalUs }
        val uneven = stream(360, { if (it % 2 == 1) 7_000 else 15_222 }, { 0 })
        val sameArrivals = uneven.mapIndexed { i, f -> Frame(f.sourceUs, even[i]) }
        val evenFrames = stream(360, { 11_111 }, { 0 })
        assertNotEquals(intervals(shownRefreshes(evenFrames, false), 20), intervals(shownRefreshes(sameArrivals, false), 20))
        assertEquals(intervals(shownRefreshes(evenFrames, true), 20), intervals(shownRefreshes(sameArrivals, true), 20))
    }

    @Test
    fun aBurstAfterAStallIsNotCaughtUpInOneRefresh() {
        // Ten frames held for 60 ms arrive within a millisecond; at most one frame per refresh,
        // the stale ones superseded rather than flashed.
        val frames = stream(120, { 11_111 }, { 0 }).mapIndexed { i, f ->
            if (i in 50..59) Frame(f.sourceUs, 1_000_000 + 15_000 + 59 * 11_111L + (i - 50) * 100L) else f
        }
        val shown = shownRefreshes(frames, false)
        assertEquals("one frame per refresh", shown.size, shown.values.toSet().size)
        assertTrue("some of the burst was superseded", (50..58).any { it !in shown })
    }
}
