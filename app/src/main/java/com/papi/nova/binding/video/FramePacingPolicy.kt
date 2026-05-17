package com.papi.nova.binding.video

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object FramePacingPolicy {
    data class SmoothnessDropDecision(
        val shouldDrop: Boolean,
        val dropThresholdNs: Long,
        val pressure: Double,
        val factor: Double,
    )

    data class LatencyDropDecision(
        val shouldDrop: Boolean,
        val isLate: Boolean,
        val nextLateStreak: Int,
        val dropThresholdNs: Long,
        val sinceLastPresentNs: Long,
        val dropCooldownOk: Boolean,
        val refreshMismatch: Double,
        val factor: Double,
    )

    fun renderPeriodNs(preferLowerDelays: Boolean, vsyncPeriodNs: Long, streamPeriodNs: Long): Long =
        if (preferLowerDelays) vsyncPeriodNs else max(vsyncPeriodNs, streamPeriodNs)

    fun smoothnessDropDecision(
        periodNs: Long,
        vsyncPeriodNs: Long,
        ewmaJitterNs: Double,
        recentDrops: Int,
        frameAgeNs: Long,
    ): SmoothnessDropDecision {
        val pressure = min(1.0, ewmaJitterNs / vsyncPeriodNs + recentDrops * 0.1)
        val factor = max(1.05, min(1.2, 1.2 - 0.15 * (1.0 - pressure)))
        val dropThresholdNs = (periodNs * factor).toLong()

        return SmoothnessDropDecision(
            shouldDrop = frameAgeNs >= dropThresholdNs,
            dropThresholdNs = dropThresholdNs,
            pressure = pressure,
            factor = factor,
        )
    }

    fun latencyDropDecision(
        periodNs: Long,
        vsyncPeriodNs: Long,
        targetFps: Int,
        displayHz: Float,
        ewmaJitterNs: Double,
        tryAgainStreak: Int,
        previousLateStreak: Int,
        lastPresentNs: Long,
        lastDropNs: Long,
        nowNs: Long,
        frameAgeNs: Long,
    ): LatencyDropDecision {
        val backPressure = min(1.0, tryAgainStreak.toDouble() / 6.0)
        val streamHz = max(1.0, targetFps.toDouble())
        val mismatch = min(
            2.0,
            abs(
                1_000_000_000.0 / streamHz -
                    1_000_000_000.0 / max(1.0, displayHz.toDouble()),
            ) / vsyncPeriodNs,
        )

        val factor = max(
            1.0,
            min(
                1.15,
                1.02 + 0.13 *
                    (0.5 * (ewmaJitterNs / vsyncPeriodNs) + 0.3 * backPressure + 0.2 * mismatch),
            ),
        )
        val dropThresholdNs = (periodNs * factor).toLong()

        val sinceLastPresent = if (lastPresentNs == 0L) Long.MAX_VALUE else nowNs - lastPresentNs
        val dropCooldownOk = nowNs - lastDropNs >= periodNs / 2
        val isLate = frameAgeNs > dropThresholdNs
        val nextLateStreak = if (isLate) previousLateStreak + 1 else 0
        val shouldDrop = isLate &&
            nextLateStreak >= 1 &&
            sinceLastPresent < (periodNs * 0.5).toLong() &&
            dropCooldownOk

        return LatencyDropDecision(
            shouldDrop = shouldDrop,
            isLate = isLate,
            nextLateStreak = nextLateStreak,
            dropThresholdNs = dropThresholdNs,
            sinceLastPresentNs = sinceLastPresent,
            dropCooldownOk = dropCooldownOk,
            refreshMismatch = mismatch,
            factor = factor,
        )
    }
}
