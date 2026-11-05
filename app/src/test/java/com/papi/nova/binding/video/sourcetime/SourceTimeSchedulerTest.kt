package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTimeSchedulerTest {
    private val vsync = 8_333_333L
    private val half = vsync / 2

    private fun slots(fromNs: Long, count: Int = 2) = LongArray(count) { fromNs + (it + 1) * vsync }

    /**
     * A vsync at [fromNs] offering the next [count] refreshes, each reachable until one refresh
     * before it: a release for the first is due as soon as it is made, so these checks see one
     * release per call, as before the in-flight bound.
     */
    private fun SourceTimeScheduler.offer(fromNs: Long, count: Int = 2): SourceTimeScheduler.Decision {
        val offered = slots(fromNs, count)
        return onVsync(fromNs, offered, LongArray(count) { offered[it] - vsync }, vsync)
    }

    @Test
    fun aFrameIsReleasedAtTheFirstRefreshAtOrAfterItsTarget() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 9_000_000)
        val decision = scheduler.offer(0)
        assertEquals(1, decision.release!!.index)
        assertEquals(vsync, decision.releaseAtNs)
    }

    @Test
    fun aFrameDueBeyondTheNextTwoRefreshesIsHeldNotReleasedEarly() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 30_000_000)
        assertNull(scheduler.offer(0, count = 6).release)
        // Two refreshes later the same frame is in reach.
        val decision = scheduler.offer(2 * vsync, count = 6)
        assertEquals(1, decision.release!!.index)
        assertTrue(decision.releaseAtNs >= 30_000_000 - half)
    }

    @Test
    fun ofSeveralFramesDueTheNewestIsShownAndTheOthersSuperseded() {
        val scheduler = SourceTimeScheduler(3)
        scheduler.admit(1, 10, 0, 1_000_000)
        scheduler.admit(2, 20, 0, 2_000_000)
        scheduler.admit(3, 30, 0, 100_000_000)
        val decision = scheduler.offer(0)
        assertEquals(2, decision.release!!.index)
        assertEquals(listOf(1), decision.superseded.map { it.index })
        assertEquals(1, scheduler.counters().queued)
        assertEquals(1L, scheduler.counters().superseded)
    }

    @Test
    fun noRefreshIsUsedTwice() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, vsync)
        assertEquals(vsync, scheduler.offer(0).releaseAtNs)
        scheduler.admit(2, 20, 0, vsync + 1)
        // The same refreshes offered again: the used one is skipped, the next is taken.
        val decision = scheduler.offer(0)
        assertEquals(2, decision.release!!.index)
        assertEquals(2 * vsync, decision.releaseAtNs)
    }

    @Test
    fun aLateFrameIsShownOnceAtTheEarliestRefreshAndCounted() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 1_000_000)
        val decision = scheduler.offer(100_000_000)
        assertEquals(1, decision.release!!.index)
        assertEquals(100_000_000 + vsync, decision.releaseAtNs)
        assertEquals(1L, scheduler.counters().releasedLate)
        assertNull(scheduler.offer(100_000_000 + vsync).release)
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
        val first = scheduler.offer(10_000_000)
        assertEquals(2, first.release!!.index)
        assertEquals(listOf(1), first.superseded.map { it.index })
    }

    @Test
    fun clearForgetsWaitingFramesAndHandsThemBack() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, 50_000_000)
        assertEquals(listOf(1), scheduler.clear().map { it.index })
        assertNull(scheduler.offer(100_000_000).release)
    }

    // The discriminating checks against the mapper and a display run the production path, presenter
    // and target delay included, in SourceTimePresenterTest.

    // --- W26 F6: one release in flight. ---

    @Test
    fun aSecondFutureReleaseWaitsForTheFirstToBeDueAt120Hz() {
        // The review's arithmetic. At 0 the display offers 8.33 and 16.67 ms; A (target 16.67) is
        // released for 16.67. At 8.33 it offers 16.67 and 25: B (target 25) could go for 25 while A
        // still waits for 16.67, two future releases outside the waiting queue. It must wait until
        // A's refresh is past its deadline (each refresh is reachable until 7.33 ms before it here).
        val scheduler = SourceTimeScheduler(2)
        fun offered(atNs: Long) = longArrayOf(atNs + vsync, atNs + 2 * vsync)
        fun deadlines(slots: LongArray) = LongArray(slots.size) { slots[it] - vsync + 1_000_000 }
        scheduler.admit(1, 10, 0, 2 * vsync)
        scheduler.admit(2, 20, 0, 3 * vsync)

        val first = scheduler.onVsync(0, offered(0), deadlines(offered(0)), vsync)
        assertEquals(1, first.release!!.index)
        assertEquals(2 * vsync, first.releaseAtNs)
        assertEquals(2 * vsync, scheduler.inFlightSlot)

        val second = scheduler.onVsync(vsync, offered(vsync), deadlines(offered(vsync)), vsync)
        assertNull("B waits while A is in flight", second.release)
        assertEquals(1L, scheduler.counters().inFlightHeld)
        assertEquals("and keeps waiting in the bounded queue", 1, scheduler.counters().queued)

        val third = scheduler.onVsync(2 * vsync, offered(2 * vsync), deadlines(offered(2 * vsync)), vsync)
        assertEquals(2, third.release!!.index)
        assertEquals("on its own refresh, not late", 3 * vsync, third.releaseAtNs)
        assertEquals(0L, scheduler.counters().releasedLate)
    }

    @Test
    fun aRenderCallbackEndsTheWaitEarlyButNeverMovesATarget() {
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, vsync)
        scheduler.admit(2, 20, 0, 2 * vsync)
        // A refresh whose deadline is far off (a display that reports a long one).
        val slots = longArrayOf(vsync, 2 * vsync)
        val farDeadlines = longArrayOf(1_000_000_000L, 1_000_000_000L)
        assertEquals(1, scheduler.onVsync(0, slots, farDeadlines, vsync).release!!.index)
        assertNull(scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync).release)
        // An older frame's callback says nothing about this one.
        scheduler.onRendered(5)
        assertNull(scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync).release)
        scheduler.onRendered(10)
        val next = scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync)
        assertEquals(2, next.release!!.index)
        assertEquals(2 * vsync, next.releaseAtNs)
    }

    @Test
    fun aFlushANewSurfaceOrStopForgetsTheReleaseInFlight() {
        val fence = SourceTimeScheduler.Fence(1, 1, 1)
        val scheduler = SourceTimeScheduler(2)
        scheduler.fence(fence)
        scheduler.admit(1, 10, 0, vsync, fence)
        val far = longArrayOf(1_000_000_000L, 1_000_000_000L)
        scheduler.onVsync(0, slots(0), far, vsync)
        // A new mapper epoch on the same surface: that buffer still waits for its refresh.
        scheduler.fence(fence.copy(epoch = 2))
        assertEquals(vsync, scheduler.inFlightSlot)
        scheduler.fence(fence.copy(epoch = 2, surfaceGeneration = 2))
        assertNull(scheduler.inFlightSlot)
        scheduler.admit(2, 20, 0, 3 * vsync)
        scheduler.onVsync(2 * vsync, slots(2 * vsync), far, vsync)
        assertEquals(3 * vsync, scheduler.inFlightSlot)
        scheduler.clear()
        assertNull(scheduler.inFlightSlot)
    }
}
