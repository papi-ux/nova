package com.papi.nova.binding.video.sourcetime

/**
 * The bounds the source-time mapper works inside, as one value: the shared Nova source-time
 * contract's policy, version 1 (shared/presentation/source-time-contract-v1.json). The Linux
 * client's DeckSourceClockPolicy carries the same names and defaults, and both clients replay the
 * same fixtures against them. The values are still marked provisional by the contract.
 *
 * Every time is in microseconds on this device's monotonic clock; RTP is 90 kHz modulo 2^32.
 */
data class SourceTimePolicy(
    /** Lower-envelope history: no older than this, and at most [offsetSamples] observations. */
    val offsetWindowUs: Long = 3_000_000,
    val offsetSamples: Long = 1024,
    /** Valid frames, the first included, before a mapping is used. */
    val warmupFrames: Long = 8,
    /** Once warm, the offset follows the envelope by at most this per second of anchor time... */
    val offsetSlewUsPerSecond: Long = 1000,
    /** ...and at most this in one frame. */
    val offsetMaxStepUs: Long = 100,
    val reserveStartUs: Long = 3000,
    /** The reserve never exceeds min(this, capacity frames x source period). */
    val reserveMaximumUs: Long = 12000,
    /** Decoded-ready lateness history for the reserve. */
    val readinessWindowUs: Long = 3_000_000,
    val readinessSamples: Long = 1024,
    val readinessPercentile: Long = 98,
    val readinessMarginUs: Long = 500,
    /** The reserve rises by at most this per decoded frame... */
    val reserveAttackUs: Long = 500,
    /** ...and falls only after this long without wanting more, by at most [reserveReleaseUsPerSecond]. */
    val cleanHoldUs: Long = 2_000_000,
    val reserveReleaseUsPerSecond: Long = 250,
    /** A forward source step longer than this starts a new phase. */
    val discontinuityUs: Long = 1_000_000,
    /** Lateness beyond this is a local hitch, counted as pressure and never learned as jitter. */
    val hitchExclusionUs: Long = 25_000,
) {
    /** True when every value is inside the contract's hard bounds. */
    fun isBounded(): Boolean =
        offsetWindowUs in 1..3_000_000 && offsetSamples in 1..1024 && warmupFrames in 1..1024 &&
            offsetSlewUsPerSecond in 0..1000 && offsetMaxStepUs in 0..100 &&
            reserveStartUs >= 0 && reserveMaximumUs in 0..12_000 &&
            readinessWindowUs in 1..3_000_000 && readinessSamples in 1..1024 &&
            readinessPercentile in 1..100 && readinessMarginUs in 0..12_000 &&
            reserveAttackUs in 0..500 && cleanHoldUs >= 0 && reserveReleaseUsPerSecond in 0..250 &&
            discontinuityUs in 1..1_000_000 && hitchExclusionUs > 0

    companion object {
        /** The contract's policyVersion these defaults implement. */
        const val VERSION = 1

        @JvmField
        val DEFAULT = SourceTimePolicy()
    }
}
