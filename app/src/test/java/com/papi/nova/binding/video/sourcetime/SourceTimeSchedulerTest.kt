package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
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

    // The discriminating checks against the mapper and a display run the production path, presenter
    // and target delay included, in SourceTimePresenterTest.
}
