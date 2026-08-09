package com.papi.nova.binding.video

/**
 * Which half-open-window bucket one boundary observation falls into
 * (measurement-spec-v1.md 6.5's inclusion rule: [S, E), accepted only when
 * S <= a <= b < E). Ported line-for-line from Polaris's own
 * classify_boundary() in stream_stats.cpp - same five-step order, same
 * disjoint outcomes, so a host-side and client-side sample landing on the
 * same logical boundary classify identically.
 */
enum class BoundaryClassification {
    ACCEPTED,
    EXCLUDED_BEFORE_WINDOW,
    EXCLUDED_AFTER_WINDOW,
    IGNORED_POST_WINDOW,
    INVALID_NON_MONOTONIC,
}

fun classifyBoundary(aOffsetUs: Long, bOffsetUs: Long, windowEndUs: Long): BoundaryClassification {
    if (aOffsetUs < 0) {
        return BoundaryClassification.EXCLUDED_BEFORE_WINDOW
    }
    if (aOffsetUs >= windowEndUs) {
        return BoundaryClassification.IGNORED_POST_WINDOW
    }
    if (bOffsetUs >= windowEndUs) {
        return BoundaryClassification.EXCLUDED_AFTER_WINDOW
    }
    if (aOffsetUs <= bOffsetUs) {
        return BoundaryClassification.ACCEPTED
    }
    return BoundaryClassification.INVALID_NON_MONOTONIC
}

/**
 * Bounded, benchmark-only capture of the T3->T4 ("reassembly_to_decoder_output",
 * measurement-spec-v1.md 7.2) stage. Not used by the existing sampled
 * diagnostic log (MediaCodecDecoderRenderer.logSampledStageTiming) - this is
 * the gate-authoritative capture path, active only when a benchmark run is
 * armed via the adb-driven control path (piece 4). Deliberately
 * stage-agnostic in its own right, though this arc only ever has one stage
 * to capture, unlike Polaris's three.
 *
 * The three parallel arrays are preallocated at construction and never grow
 * - "no allocation after run start" (7.2). Offsets/durations are stored as
 * Int microseconds: comfortably in range even for a worst-case multi-minute
 * run, and matching the wire format's own start_offset_us/end_offset_us/
 * duration_us arrays directly.
 */
class BenchmarkStageCapture(private val capacity: Int = MAX_SAMPLES) {
    private val startOffsetUsArray = IntArray(capacity)
    private val endOffsetUsArray = IntArray(capacity)
    private val durationUsArray = IntArray(capacity)

    var acceptedCount = 0
        private set
    var excludedStartedBeforeWindow = 0
        private set
    var excludedCompletedAfterWindow = 0
        private set
    var overflowCount = 0
        private set
    var invalidDurationCount = 0
        private set

    // T3 or T4 was unavailable at all for this frame - decided by the
    // caller before it ever has a pair of offsets to classify, so this
    // isn't something record() itself can detect. Separate from
    // invalidDurationCount, which is for a T3/T4 pair that IS available but
    // classifies as non-monotonic.
    var missingTimestampCount = 0
        private set

    fun recordMissing() {
        missingTimestampCount++
    }

    /**
     * Classify and, if accepted, record one observation. An accepted
     * observation that would exceed capacity is counted in overflowCount
     * instead of being stored - ported from Polaris's own
     * benchmark_stage_capture_t::record().
     */
    fun record(aOffsetUs: Long, bOffsetUs: Long, windowEndUs: Long): BoundaryClassification {
        val classification = classifyBoundary(aOffsetUs, bOffsetUs, windowEndUs)
        when (classification) {
            BoundaryClassification.EXCLUDED_BEFORE_WINDOW -> excludedStartedBeforeWindow++
            BoundaryClassification.EXCLUDED_AFTER_WINDOW -> excludedCompletedAfterWindow++
            BoundaryClassification.IGNORED_POST_WINDOW -> {
                // No state created - matches 6.5's "do not create run-owned
                // in-flight state" for this case.
            }
            BoundaryClassification.INVALID_NON_MONOTONIC -> invalidDurationCount++
            BoundaryClassification.ACCEPTED -> {
                if (acceptedCount >= capacity) {
                    overflowCount++
                } else {
                    startOffsetUsArray[acceptedCount] = aOffsetUs.toInt()
                    endOffsetUsArray[acceptedCount] = bOffsetUs.toInt()
                    durationUsArray[acceptedCount] = (bOffsetUs - aOffsetUs).toInt()
                    acceptedCount++
                }
            }
        }
        return classification
    }

    // Copying accessors, not live views - called only at export time
    // (piece 5), well after a run has frozen and stopped recording, so this
    // doesn't touch the "no allocation after run start" hot-path guarantee
    // above, which is specifically about record().
    fun startOffsetUs(): IntArray = startOffsetUsArray.copyOf(acceptedCount)
    fun endOffsetUs(): IntArray = endOffsetUsArray.copyOf(acceptedCount)
    fun durationUs(): IntArray = durationUsArray.copyOf(acceptedCount)

    companion object {
        const val MAX_SAMPLES = 65536
    }
}
