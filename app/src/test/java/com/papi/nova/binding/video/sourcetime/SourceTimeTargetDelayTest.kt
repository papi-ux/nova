package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTimeTargetDelayTest {
    private val period = 8_333_333L
    private val lead = 25_000_000L

    @Test
    fun nothingIsLearnedBeforeAVsyncHasMeasuredTheDisplay() {
        val delay = SourceTimeTargetDelay(2)
        assertEquals(0L, delay.onAdmitted(1_000_000_000L, 1_000_000_000L))
        assertNull(delay.reportUs)
    }

    @Test
    fun theFloorIsTheDecodeToReleaseLatencyPlusOneRefresh() {
        val delay = SourceTimeTargetDelay(2)
        delay.onVsync(0, lead, period)
        // Decoded 2 ms before its own target: the display's 25 ms lead less those 2 ms, plus a refresh.
        assertEquals(lead - 2_000_000 + period, delay.onAdmitted(10_000_000, 12_000_000))
        assertEquals((lead - 2_000_000 + period) / 1000, delay.reportUs)
    }

    @Test
    fun withRoomForOneFrameOnlyHalfARefreshIsAdded() {
        val delay = SourceTimeTargetDelay(1)
        delay.onVsync(0, lead, period)
        assertEquals(lead + period / 2, delay.onAdmitted(10_000_000, 10_000_000))
    }

    @Test
    fun lateFramesAreTheReservesBusinessAndNeverRaiseTheDelay() {
        val delay = SourceTimeTargetDelay(2)
        delay.onVsync(0, lead, period)
        val onTime = delay.onAdmitted(10_000_000, 10_000_000)
        // 40 ms behind its target: a decode hitch, not something to hold every frame for.
        assertEquals(onTime, delay.onAdmitted(60_000_000, 20_000_000))
        assertFalse(delay.limited)
    }

    @Test
    fun framesThatComeEarlyLetItShrinkBackSlowlyAndNeverBelowTheFloor() {
        val delay = SourceTimeTargetDelay(2)
        delay.onVsync(0, lead, period)
        val frame = 16_666_667L
        var t = 1_000_000_000L
        // Frames ready 1 ms before their target for a while.
        repeat(200) { delay.onAdmitted(t, t + 1_000_000); t += frame }
        val high = delay.currentNs
        assertEquals(lead - 1_000_000 + period, high)
        // From now on they come 6 ms early, so the floor is 5 ms lower. Nothing moves while the
        // old samples still hold the 98th percentile of the three-second window.
        val switched = t
        while (delay.currentNs == high) {
            delay.onAdmitted(t, t + 6_000_000)
            t += frame
            assertTrue("never released", t - switched < 10_000_000_000L)
        }
        assertTrue("held for ${(t - switched) / 1_000_000} ms", t - switched >= 2_500_000_000L)
        // Then at most a millisecond per second.
        val releaseStart = t
        while (t - releaseStart < 2_000_000_000L) {
            delay.onAdmitted(t, t + 6_000_000)
            t += frame
        }
        val released = high - delay.currentNs
        assertTrue("released $released ns in two seconds", released in 1_900_000L..2_100_000L)
        // Down to the new floor and no further.
        while (t - releaseStart < 20_000_000_000L) {
            delay.onAdmitted(t, t + 6_000_000)
            t += frame
        }
        assertEquals(lead - 6_000_000 + period, delay.currentNs)
    }

    @Test
    fun aRiseIsTakenInOneStepAsSoonAsThePercentileMoves() {
        val delay = SourceTimeTargetDelay(2)
        delay.onVsync(0, lead, period)
        val frame = 16_666_667L
        var t = 1_000_000_000L
        repeat(60) { delay.onAdmitted(t, t); t += frame }
        val before = delay.currentNs
        // The display's lead grows by 4 ms (a deeper compositor pipeline after a mode change).
        var frames = 0
        while (delay.currentNs == before) {
            delay.onVsync(t, t + lead + 4_000_000, period)
            delay.onAdmitted(t, t)
            t += frame
            frames++
        }
        assertTrue("rose after $frames frames", frames <= 3)
        assertEquals(lead + 4_000_000 + period, delay.currentNs)
    }

    @Test
    fun theDelayStaysInsideItsDeclaredBoundsAndSaysWhenItWantedMore() {
        val delay = SourceTimeTargetDelay(2)
        delay.onVsync(0, 500_000_000L, period)
        assertEquals(SourceTimeTargetDelay.MAXIMUM_US * 1000, delay.onAdmitted(1_000, 1_000))
        assertTrue(delay.limited)
        delay.reset()
        assertNull(delay.reportUs)
        assertEquals(SourceTimeTargetDelay.MINIMUM_US * 1000, delay.currentNs)
        // A display whose earliest refresh is behind now asks for no lead, only the refresh of wait.
        delay.onVsync(10_000_000, 5_000_000, period)
        assertEquals(period, delay.onAdmitted(10_000_000, 10_000_000))
        assertFalse(delay.limited)
    }
}
