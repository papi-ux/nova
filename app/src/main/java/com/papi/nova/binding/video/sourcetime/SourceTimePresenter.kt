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
 *   scheduler what to release, one release in flight at a time (W26 F6): the next waits for the
 *   previous refresh's deadline or for [onRendered], whichever comes first. The render callback
 *   only ever ends that wait early; it never moves a target, a delay or a reserve.
 * - Both first check the fence (W26 F3): the mapper epoch and the renderer's surface and decoder
 *   generations. When it has moved, every queued frame aimed under the old one is handed back to
 *   drop, a source-paced state stops claiming so, the delay is learned again for a new surface, and
 *   the next vsync says the frame-rendered feedback still pending belongs to the old fence. A
 *   decoded frame whose timing was made under an old epoch or surface is aimed by arrival with a
 *   named reason (stale_epoch, surface_recreated) and never restores source_time_fixed.
 * - Source-paced has to stay fresh (W26 F4). A repeated RTP timestamp or an older frame identity
 *   is one frame's condition and keeps the state, but when [staleFrames] frames in a row (one
 *   second at the negotiated rate) or [STALE_AFTER_NS] go by without a valid mapped target, the
 *   state becomes pending with source_timestamp_missing (the shared contract's one-second freshness
 *   expiry, as on the Deck) until a frame is source-timed again.
 *
 * Every method is synchronized. Lock order is this object, then the scheduler's or the mapper's
 * lock, never the reverse: nothing here is called with the mapper's lock held.
 */
class SourceTimePresenter(
    capacity: Int,
    private val mapper: SourceTimeMapper,
    /** The negotiated stream rate: a second of frames without a mapped target is stale. */
    framesPerSecond: Int = 60,
) {
    /** Consecutive frames without a valid mapped target that end a source-paced claim. */
    val staleFrames = maxOf(1, framesPerSecond)

    data class Admission(
        /** Frames this pushed out of the queue or left behind a moved fence, to drop unrendered. */
        val dropped: List<SourceTimeScheduler.Entry>,
        /** How the frame was aimed, for the report's counts. */
        val kind: SourceTimeCounters.Admission,
        /** Its mapped target was further ahead than any frame is held: reset the mapping. */
        val outOfBounds: Boolean,
    )

    /** What one vsync asks of the renderer. */
    data class Vsync(
        val release: SourceTimeScheduler.Entry?,
        /** The refresh [release] is for; the renderTimestampNs to give releaseOutputBuffer. */
        val releaseAtNs: Long,
        /** Older frames due at the same refresh, and frames left behind a moved fence: drop them. */
        val drop: List<SourceTimeScheduler.Entry>,
        /** The fence moved since the last vsync: pending frame-rendered feedback is the old one's. */
        val fenceMoved: Boolean,
    )

    private val scheduler = SourceTimeScheduler(capacity)
    private val delay = SourceTimeTargetDelay(capacity)
    private var state = SourceTimeEffective.PENDING
    private var reason = SourceTimeReason.NONE
    private var fence = SourceTimeScheduler.NO_FENCE
    private var fenceMovedSinceVsync = false
    private var framesWithoutTarget = 0
    private var lastTargetNs = Long.MIN_VALUE

    /** The learned presentation delay in microseconds; null before any frame measured it. */
    val targetDelayUs: Long?
        get() = delay.reportUs

    /**
     * One decoded frame, with the record queued beside it (or null), under the renderer's current
     * [surfaceGeneration] and [decoderGeneration].
     */
    @Synchronized
    fun admit(
        index: Int,
        ptsUs: Long,
        decodedNs: Long,
        record: SourceTimeSideMap.Record?,
        surfaceGeneration: Int,
        decoderGeneration: Int,
    ): Admission {
        val stale = refence(surfaceGeneration, decoderGeneration)
        val current = fence
        val point = record?.point
        var targetNs: Long? = null
        var outOfBounds = false
        var staleTiming = false
        when {
            point == null -> fallback(SourceTimeReason.TIMING_METADATA_MISSING)
            record?.surfaceGeneration != current.surfaceGeneration -> {
                staleTiming = true
                fallback(SourceTimeReason.SURFACE_RECREATED)
            }
            point.epoch != current.epoch -> {
                staleTiming = true
                fallback(SourceTimeReason.STALE_EPOCH)
            }
            !point.valid -> reasonFor(point.reason)?.let(::fallback)
            // The mapper may have started a new epoch since the fence was read.
            !mapper.ready(point, decodedNs / 1000) -> {
                staleTiming = true
                fallback(SourceTimeReason.STALE_EPOCH)
            }
            else -> {
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
        if (targetNs != null) {
            framesWithoutTarget = 0
            lastTargetNs = decodedNs
        } else {
            framesWithoutTarget++
            // A run of repeats, not one: the claim ends, whatever the frames' own reasons.
            if (state == SourceTimeEffective.SOURCE_TIME_FIXED && (framesWithoutTarget >= staleFrames || stale(decodedNs))) {
                fallback(SourceTimeReason.SOURCE_TIMESTAMP_MISSING)
            }
        }
        val kind = when {
            point == null -> SourceTimeCounters.Admission.TIMING_METADATA_MISSING
            staleTiming -> SourceTimeCounters.Admission.STALE_EPOCH
            targetNs != null -> SourceTimeCounters.Admission.SOURCE_TIMED
            else -> SourceTimeCounters.Admission.SOURCE_TIME_MISSING
        }
        return Admission(stale + scheduler.admit(index, ptsUs, decodedNs, targetNs, current), kind, outOfBounds)
    }

    /**
     * One vsync at [nowNs]: [slotsNs] are the refreshes still reachable, ascending, [deadlinesNs]
     * their queueing deadlines and [vsyncPeriodNs] the display period. The earliest of them, against
     * now, is the display's lead.
     */
    @Synchronized
    fun onVsync(
        nowNs: Long,
        slotsNs: LongArray,
        deadlinesNs: LongArray,
        vsyncPeriodNs: Long,
        surfaceGeneration: Int,
        decoderGeneration: Int,
    ): Vsync {
        val stale = refence(surfaceGeneration, decoderGeneration)
        if (slotsNs.isNotEmpty()) delay.onVsync(nowNs, slotsNs[0], vsyncPeriodNs)
        val decision = scheduler.onVsync(nowNs, slotsNs, deadlinesNs, vsyncPeriodNs)
        val moved = fenceMovedSinceVsync
        fenceMovedSinceVsync = false
        return Vsync(decision.release, decision.releaseAtNs, stale + decision.superseded, moved)
    }

    /** The codec reported the frame queued with [ptsUs] rendered. */
    @Synchronized
    fun onRendered(ptsUs: Long) {
        scheduler.onRendered(ptsUs)
    }

    /**
     * The renderer reset the mapping at one of its own sites: whatever was source-paced is not any
     * more, for [why]. The queued frames go at the next call, when the fence is seen to move.
     */
    @Synchronized
    fun onMappingReset(why: SourceTimeReason) {
        state = SourceTimeEffective.PENDING
        reason = why
    }

    /**
     * The renderer's state for [request] at [nowNs], with the buffer limit named when a bound was
     * reached. No mapped target for [STALE_AFTER_NS], frames or none, is no longer source-paced.
     */
    @Synchronized
    fun pacing(request: SourceTimeRequest, nowNs: Long): SourceTimePacingStatus {
        if (state == SourceTimeEffective.SOURCE_TIME_FIXED && stale(nowNs)) {
            fallback(SourceTimeReason.SOURCE_TIMESTAMP_MISSING)
        }
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

    /** Forget every waiting frame and hand them back (to drop, or to abandon at stop). */
    @Synchronized
    fun clear(): List<SourceTimeScheduler.Entry> = scheduler.clear()

    @Synchronized
    fun counters(): SourceTimeScheduler.Counters = scheduler.counters()

    private fun stale(nowNs: Long) = lastTargetNs != Long.MIN_VALUE && nowNs - lastTargetNs >= STALE_AFTER_NS

    private fun fallback(why: SourceTimeReason) {
        state = SourceTimeEffective.PENDING
        reason = why
    }

    /** Move to the current fence; returns the waiting frames aimed under an older one. */
    private fun refence(surfaceGeneration: Int, decoderGeneration: Int): List<SourceTimeScheduler.Entry> {
        val current = SourceTimeScheduler.Fence(mapper.epoch, surfaceGeneration, decoderGeneration)
        val previous = fence
        if (current == previous) return emptyList()
        fence = current
        if (previous != SourceTimeScheduler.NO_FENCE) {
            fenceMovedSinceVsync = true
            val surfaceMoved = previous.surfaceGeneration != current.surfaceGeneration
            // A new surface or display timing has a lead of its own to measure.
            if (surfaceMoved) delay.reset()
            if (state == SourceTimeEffective.SOURCE_TIME_FIXED) {
                fallback(if (surfaceMoved) SourceTimeReason.SURFACE_RECREATED else SourceTimeReason.STALE_EPOCH)
            }
        }
        return scheduler.fence(current)
    }

    companion object {
        /** A mapped target further ahead of its decode than this is a broken mapping (design C.3). */
        const val EARLY_BOUND_NS = 200_000_000L

        /** A second without a valid mapped target ends a source-paced claim (W26 F4). */
        const val STALE_AFTER_NS = 1_000_000_000L

        /** The state's reason after the renderer reset the mapping at the named site. */
        @JvmStatic
        fun reasonForReset(reset: String): SourceTimeReason = when (reset) {
            SourceTimeCounters.RESET_DISPLAY_CHANGE -> SourceTimeReason.SURFACE_RECREATED
            SourceTimeCounters.RESET_TARGET_OUT_OF_BOUNDS -> SourceTimeReason.CLOCK_MAPPING_UNSTABLE
            // Stream setup, a frame restart, codec recovery or foreground: warming up again.
            else -> SourceTimeReason.NONE
        }

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
