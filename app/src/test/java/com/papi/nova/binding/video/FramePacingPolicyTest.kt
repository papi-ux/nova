package com.papi.nova.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingPolicyTest {
    private val vsync60Ns = 16_666_666L
    private val stream30Ns = 33_333_333L

    @Test
    fun renderPeriodUsesVsyncForLowerDelayAndSlowerPeriodOtherwise() {
        assertEquals(vsync60Ns, FramePacingPolicy.renderPeriodNs(true, vsync60Ns, stream30Ns))
        assertEquals(stream30Ns, FramePacingPolicy.renderPeriodNs(false, vsync60Ns, stream30Ns))
    }

    @Test
    fun smoothnessDecisionDropsAtAdaptiveThreshold() {
        val decision = FramePacingPolicy.smoothnessDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            ewmaJitterNs = 0.0,
            recentDrops = 0,
            frameAgeNs = (vsync60Ns * 1.05).toLong(),
        )

        assertTrue(decision.shouldDrop)
        assertEquals((vsync60Ns * 1.05).toLong(), decision.dropThresholdNs)
    }

    @Test
    fun smoothnessDecisionKeepsFreshFrames() {
        val decision = FramePacingPolicy.smoothnessDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            ewmaJitterNs = vsync60Ns * 0.1,
            recentDrops = 0,
            frameAgeNs = vsync60Ns,
        )

        assertFalse(decision.shouldDrop)
    }

    @Test
    fun latencyDecisionDropsLateFrameOnlyInsideCooldownRules() {
        val oldDropNs = 1_000_000_000L
        val nowNs = oldDropNs + vsync60Ns
        val lateAgeNs = (vsync60Ns * 1.03).toLong()

        val decision = FramePacingPolicy.latencyDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            targetFps = 60,
            displayHz = 60f,
            ewmaJitterNs = 0.0,
            tryAgainStreak = 0,
            previousLateStreak = 0,
            lastPresentNs = nowNs - vsync60Ns / 4,
            lastDropNs = oldDropNs,
            nowNs = nowNs,
            frameAgeNs = lateAgeNs,
        )

        assertTrue(decision.isLate)
        assertTrue(decision.dropCooldownOk)
        assertEquals(1, decision.nextLateStreak)
        assertTrue(decision.shouldDrop)

        val recentDrop = FramePacingPolicy.latencyDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            targetFps = 60,
            displayHz = 60f,
            ewmaJitterNs = 0.0,
            tryAgainStreak = 0,
            previousLateStreak = 0,
            lastPresentNs = nowNs - vsync60Ns / 4,
            lastDropNs = nowNs - vsync60Ns / 4,
            nowNs = nowNs,
            frameAgeNs = lateAgeNs,
        )

        assertFalse(recentDrop.dropCooldownOk)
        assertFalse(recentDrop.shouldDrop)
    }

    @Test
    fun latencyDecisionDoesNotDropBeforeFirstPresentation() {
        val decision = FramePacingPolicy.latencyDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            targetFps = 60,
            displayHz = 60f,
            ewmaJitterNs = 0.0,
            tryAgainStreak = 0,
            previousLateStreak = 0,
            lastPresentNs = 0L,
            lastDropNs = 0L,
            nowNs = 2_000_000_000L,
            frameAgeNs = (vsync60Ns * 1.03).toLong(),
        )

        assertTrue(decision.isLate)
        assertEquals(Long.MAX_VALUE, decision.sinceLastPresentNs)
        assertFalse(decision.shouldDrop)
    }

    @Test
    fun latencyDecisionRaisesThresholdForRefreshMismatch() {
        val matched = FramePacingPolicy.latencyDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            targetFps = 60,
            displayHz = 60f,
            ewmaJitterNs = 0.0,
            tryAgainStreak = 0,
            previousLateStreak = 0,
            lastPresentNs = 1L,
            lastDropNs = 0L,
            nowNs = 2L,
            frameAgeNs = 0L,
        )
        val mismatched = FramePacingPolicy.latencyDropDecision(
            periodNs = vsync60Ns,
            vsyncPeriodNs = vsync60Ns,
            targetFps = 30,
            displayHz = 60f,
            ewmaJitterNs = 0.0,
            tryAgainStreak = 0,
            previousLateStreak = 0,
            lastPresentNs = 1L,
            lastDropNs = 0L,
            nowNs = 2L,
            frameAgeNs = 0L,
        )

        assertTrue(mismatched.dropThresholdNs > matched.dropThresholdNs)
        assertEquals(1.0, mismatched.refreshMismatch, 0.01)
    }
}
