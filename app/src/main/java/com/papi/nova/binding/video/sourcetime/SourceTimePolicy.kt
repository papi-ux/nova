package com.papi.nova.binding.video.sourcetime

/**
 * The bounds the source-time mapper works inside, as one value: the shared Nova source-time
 * contract's policy, version 1, instability revision 2
 * (shared/presentation/source-time-contract-v1.json). The Linux
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
    /** ...and at most this in one frame. Debt beyond it waits; it never starts a new epoch alone. */
    val offsetMaxStepUs: Long = 100,
    /**
     * The anchor clock's real resolution: 1000 for the core's millisecond enqueue time (Android and
     * the Deck today), 1 only for an anchor that really has microseconds.
     */
    val anchorResolutionUs: Long = 1000,
    /** The envelope may move this far (at least 4 x [anchorResolutionUs]) before it is divergence... */
    val offsetDivergenceToleranceUs: Long = 5000,
    /** ...confirmed by this many consecutive raw samples in the same direction. */
    val offsetDivergenceFrames: Long = 8,
    /** Post-slew debt beyond this in one direction... */
    val offsetBacklogLimitUs: Long = 20_000,
    /** ...for longer than this re-warms. */
    val offsetBacklogHoldUs: Long = 1_000_000,
    /** A raw sample this far from the applied offset... */
    val offsetStepThresholdUs: Long = 20_000,
    /** ...for this many consecutive frames in one direction is a step, and re-warms. */
    val offsetStepFrames: Long = 8,
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
            anchorResolutionUs in 1..1_000_000 && offsetDivergenceToleranceUs in 0..1_000_000 &&
            offsetDivergenceFrames in 1..1024 && offsetBacklogLimitUs in 1..1_000_000 &&
            offsetBacklogHoldUs in 1..3_000_000 && offsetStepThresholdUs in 1..1_000_000 &&
            offsetStepFrames in 1..1024 &&
            reserveStartUs >= 0 && reserveMaximumUs in 0..12_000 &&
            readinessWindowUs in 1..3_000_000 && readinessSamples in 1..1024 &&
            readinessPercentile in 1..100 && readinessMarginUs in 0..12_000 &&
            reserveAttackUs in 0..500 && cleanHoldUs >= 0 && reserveReleaseUsPerSecond in 0..250 &&
            discontinuityUs in 1..1_000_000 && hitchExclusionUs > 0

    companion object {
        /** The contract's policyVersion these defaults implement. */
        const val VERSION = 1

        /** The contract's offsetInstabilityRevision: re-warm only on sustained divergence. */
        const val INSTABILITY_REVISION = 2

        @JvmField
        val DEFAULT = SourceTimePolicy()
    }
}
