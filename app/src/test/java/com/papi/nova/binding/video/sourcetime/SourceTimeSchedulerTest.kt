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

    // --- W26 F6 / W34 D1: releases outstanding until there is evidence they are done. ---

    @Test
    fun theReviewsDeepLeadCaseKeepsAInFlightAndReleasesBForItsOwnRefreshAt120Hz() {
        // Review W34 D1's arithmetic, on the presenter tests' own 120 Hz display: period 8.333 ms,
        // callbacks 0.3 ms after each vsync, the earliest reachable refresh 25 ms after it, each
        // refresh to be queued 17.67 ms before it. A (target 25 ms) goes at 0.3 ms with deadline
        // 7.33 ms. At the 8.633 ms callback that deadline has passed but A's refresh has not: A is
        // still outstanding. B (target 33.33 ms) goes for its own earliest refresh, not ahead of A
        // and not held: two outstanding is inside the display's own pipeline of three, plus one.
        val period = vsync
        val lead = 25_000_000L
        fun now(k: Int) = k * period + 300_000L
        fun offered(k: Int) = LongArray(3) { k * period + lead + it * period }
        fun deadlines(slots: LongArray) = LongArray(slots.size) { slots[it] - 2 * period - 1_000_000L }
        fun at(scheduler: SourceTimeScheduler, k: Int) = scheduler.onVsync(now(k), offered(k), deadlines(offered(k)), period)
        val scheduler = SourceTimeScheduler(2)
        scheduler.admit(1, 10, 0, lead)
        scheduler.admit(2, 20, 0, lead + period)

        val a = at(scheduler, 0)
        assertEquals(1, a.release!!.index)
        assertEquals(lead, a.releaseAtNs)
        assertEquals(7_333_334L, deadlines(offered(0))[0])
        assertEquals(3, SourceTimeScheduler.pipelineDepth(now(1), offered(1)[0], period))

        val b = at(scheduler, 1)
        assertEquals("A is still in flight at 8.633 ms: its deadline passed, its refresh has not", lead, scheduler.inFlightSlot)
        assertEquals(2, b.release!!.index)
        assertEquals("B on its own earliest refresh", lead + period, b.releaseAtNs)
        assertEquals(0L, scheduler.counters().releasedLate)
        assertEquals(0L, scheduler.counters().inFlightHeld)

        // A stays outstanding until its refresh has passed, then B is the oldest.
        scheduler.admit(3, 30, 0, lead + 2 * period)
        assertEquals(3, at(scheduler, 2).release!!.index)
        assertEquals(lead, scheduler.inFlightSlot)
        at(scheduler, 3)
        assertEquals(lead + period, scheduler.inFlightSlot)
    }

    @Test
    fun noMoreReleasesAreOutstandingThanTheDisplaysPipelinePlusOne() {
        // A display three refreshes deep takes A, B and C, one per vsync. Its timeline then offers a
        // refresh only half a vsync out (L = 1): with three outstanding, D waits, and goes for its own
        // refresh once enough of them have passed.
        val scheduler = SourceTimeScheduler(2)
        fun offer(atNs: Long, fromNs: Long) = scheduler.onVsync(atNs, LongArray(4) { fromNs + it * vsync },
            LongArray(4) { fromNs + it * vsync - vsync }, vsync)
        scheduler.admit(1, 10, 0, 3 * vsync)
        assertEquals(3 * vsync, offer(0, 3 * vsync).releaseAtNs)
        scheduler.admit(2, 20, 0, 4 * vsync)
        assertEquals(4 * vsync, offer(vsync, 4 * vsync).releaseAtNs)
        scheduler.admit(3, 30, 0, 5 * vsync)
        assertEquals(5 * vsync, offer(2 * vsync, 5 * vsync).releaseAtNs)
        scheduler.admit(4, 40, 0, 6 * vsync)

        assertEquals(1, SourceTimeScheduler.pipelineDepth(2 * vsync + half, 3 * vsync, vsync))
        assertNull("three outstanding, a pipeline of one plus one", offer(2 * vsync + half, 3 * vsync).release)
        assertNull("A's refresh passed, still two", offer(3 * vsync, 4 * vsync).release)
        assertEquals(2L, scheduler.counters().inFlightHeld)
        val d = offer(4 * vsync, 5 * vsync)
        assertEquals(4, d.release!!.index)
        assertEquals("on its own refresh, not late", 6 * vsync, d.releaseAtNs)
        assertEquals(0L, scheduler.counters().releasedLate)
    }

    @Test
    fun aRenderCallbackClosesAReleaseEarlyButNeverMovesATarget() {
        // A display one refresh deep (L = 1) holds at most two. A render callback for the oldest, or
        // a later frame, closes it before its refresh; an earlier frame's closes nothing.
        val scheduler = SourceTimeScheduler(2)
        val slots = longArrayOf(vsync, 2 * vsync, 3 * vsync)
        val farDeadlines = LongArray(3) { 1_000_000_000L }
        scheduler.admit(1, 10, 0, vsync)
        scheduler.admit(2, 20, 0, 2 * vsync)
        assertEquals(1, scheduler.onVsync(0, slots, farDeadlines, vsync).release!!.index)
        assertEquals(2, scheduler.onVsync(vsync / 4, slots, farDeadlines, vsync).release!!.index)
        scheduler.admit(3, 30, 0, 3 * vsync)
        assertNull(scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync).release)
        scheduler.onRendered(5)
        assertNull(scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync).release)
        scheduler.onRendered(10)
        assertEquals(2 * vsync, scheduler.inFlightSlot)
        val next = scheduler.onVsync(vsync / 2, slots, farDeadlines, vsync)
        assertEquals(3, next.release!!.index)
        assertEquals(3 * vsync, next.releaseAtNs)
        // A later frame's callback closes every earlier release too.
        scheduler.onRendered(30)
        assertNull(scheduler.inFlightSlot)
    }

    @Test
    fun thePipelineDepthIsTheLeadInRefreshesOneWhenUnknownAndCapped() {
        assertEquals(3, SourceTimeScheduler.pipelineDepth(300_000L, 25_000_000L, vsync))
        assertEquals(1, SourceTimeScheduler.pipelineDepth(0, vsync, vsync))
        assertEquals(2, SourceTimeScheduler.pipelineDepth(0, vsync + 1, vsync))
        assertEquals("a slot not ahead of now", 1, SourceTimeScheduler.pipelineDepth(vsync, vsync, vsync))
        assertEquals("no period", 1, SourceTimeScheduler.pipelineDepth(0, 25_000_000L, 0))
        assertEquals("a stalled or malformed timeline", SourceTimeScheduler.MAX_PIPELINE_DEPTH,
            SourceTimeScheduler.pipelineDepth(0, 1_000_000_000L, vsync))
        assertEquals(4, SourceTimeScheduler.MAX_PIPELINE_DEPTH)
        assertEquals(0L, SourceTimeScheduler.PRESENTED_TOLERANCE_NS)
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
