package com.papi.nova.binding.video.sourcetime

/**
 * What the MediaCodec renderer decides about each decoded frame between its dequeue and its
 * release, as one pure object: the renderer hands it the frame and the vsync it sees, and does only
 * what it is told to the codec. The tests drive this same object, so what they check is the path the
 * renderer runs, not a model of it.
 *
 * - [admit] (renderer thread) aims a decoded frame: at its mapped target plus the learned
 *   presentation delay ([SourceTimeTargetDelay]) when it has usable source time, or at its decode
 *   time (the arrival fallback) with a named reason when it has none. It keeps the effective state
 *   the status reports.
 * - [onVsync] (vsync thread) measures the display's lead from the refreshes it offers and asks the
 *   scheduler what to release.
 *
 * Every method is synchronized. Lock order is this object, then the scheduler's or the mapper's
 * lock, never the reverse: nothing here is called with the mapper's lock held.
 */
class SourceTimePresenter(
    capacity: Int,
    private val mapper: SourceTimeMapper,
) {
    data class Admission(
        /** Frames this pushed out of the queue (oldest first), to drop unrendered. */
        val dropped: List<SourceTimeScheduler.Entry>,
        /** How the frame was aimed, for the report's counts. */
        val kind: SourceTimeCounters.Admission,
        /** Its mapped target was further ahead than any frame is held: reset the mapping. */
        val outOfBounds: Boolean,
    )

    private val scheduler = SourceTimeScheduler(capacity)
    private val delay = SourceTimeTargetDelay(capacity)
    private var state = SourceTimeEffective.PENDING
    private var reason = SourceTimeReason.NONE

    /** The learned presentation delay in microseconds; null before any frame measured it. */
    val targetDelayUs: Long?
        get() = delay.reportUs

    @Synchronized
    fun admit(index: Int, ptsUs: Long, decodedNs: Long, record: SourceTimeSideMap.Record?): Admission {
        val point = record?.point
        var targetNs: Long? = null
        var outOfBounds = false
        when {
            point == null -> fallback(SourceTimeReason.TIMING_METADATA_MISSING)
            !point.valid -> reasonFor(point.reason)?.let(::fallback)
            else -> {
                mapper.ready(point, decodedNs / 1000)
                val mappedNs = point.targetUs * 1000
                val inBounds = mappedNs - decodedNs <= EARLY_BOUND_NS
                if (!point.active) {
                    // Warm-up points teach the delay too, so it is ready when the mapping is.
                    if (inBounds) delay.onAdmitted(decodedNs, mappedNs)
                    state = SourceTimeEffective.PENDING
                    reason = reasonFor(point.reason) ?: SourceTimeReason.NONE
                } else if (!inBounds) {
                    // A target this far ahead is a broken mapping, not a frame to hold.
                    outOfBounds = true
                    fallback(SourceTimeReason.CLOCK_MAPPING_UNSTABLE)
                } else {
                    targetNs = mappedNs + delay.onAdmitted(decodedNs, mappedNs)
                    state = SourceTimeEffective.SOURCE_TIME_FIXED
                    reason = SourceTimeReason.NONE
                }
            }
        }
        val kind = when {
            point == null -> SourceTimeCounters.Admission.TIMING_METADATA_MISSING
            targetNs != null -> SourceTimeCounters.Admission.SOURCE_TIMED
            else -> SourceTimeCounters.Admission.SOURCE_TIME_MISSING
        }
        return Admission(scheduler.admit(index, ptsUs, decodedNs, targetNs), kind, outOfBounds)
    }

    /**
     * One vsync at [nowNs]: [slotsNs] are the refreshes still reachable, ascending, and
     * [vsyncPeriodNs] the display period. The earliest of them, against now, is the display's lead.
     */
    @Synchronized
    fun onVsync(nowNs: Long, slotsNs: LongArray, vsyncPeriodNs: Long): SourceTimeScheduler.Decision {
        if (slotsNs.isNotEmpty()) delay.onVsync(nowNs, slotsNs[0], vsyncPeriodNs)
        return scheduler.onVsync(slotsNs, vsyncPeriodNs)
    }

    /** The renderer's state for [request], with the buffer limit named when a bound was reached. */
    @Synchronized
    fun pacing(request: SourceTimeRequest): SourceTimePacingStatus {
        if (state != SourceTimeEffective.SOURCE_TIME_FIXED) {
            return SourceTimePacingStatus(request, state, reason)
        }
        // Nothing is asked of the display, so its capability stays unknown here; a reserve or a
        // delay that wanted more than its bound, or a local hitch, is the buffer limit.
        val limited = synchronized(mapper) { mapper.pressure } || delay.limited
        return SourceTimePacingStatus(
            request,
            SourceTimeEffective.SOURCE_TIME_FIXED,
            if (limited) SourceTimeReason.BUFFER_LIMIT else SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN,
        )
    }

    /** The display's timing changed: its lead has to be measured again. */
    @Synchronized
    fun onDisplayChange() {
        delay.reset()
    }

    /** Forget every waiting frame and hand them back (to drop, or to abandon at stop). */
    @Synchronized
    fun clear(): List<SourceTimeScheduler.Entry> = scheduler.clear()

    @Synchronized
    fun counters(): SourceTimeScheduler.Counters = scheduler.counters()

    private fun fallback(why: SourceTimeReason) {
        state = SourceTimeEffective.PENDING
        reason = why
    }

    companion object {
        /** A mapped target further ahead of its decode than this is a broken mapping (design C.3). */
        const val EARLY_BOUND_NS = 200_000_000L

        /** The shared reason for a mapper point's own reason; null for a one-frame condition. */
        @JvmStatic
        fun reasonFor(reason: String): SourceTimeReason? = when (reason) {
            SourceTimeMapper.REASON_SOURCE_TIMESTAMP_MISSING -> SourceTimeReason.SOURCE_TIMESTAMP_MISSING
            SourceTimeMapper.REASON_SOURCE_DISCONTINUITY -> SourceTimeReason.SOURCE_DISCONTINUITY
            SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE -> SourceTimeReason.CLOCK_MAPPING_UNSTABLE
            SourceTimeMapper.REASON_NONE, SourceTimeMapper.PHASE_WARMUP -> SourceTimeReason.NONE
            // duplicate_timestamp and stale_frame describe one frame, not the stream.
            else -> null
        }
    }
}
