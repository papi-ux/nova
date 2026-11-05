package com.papi.nova.binding.video.sourcetime

/**
 * Which decoded frame goes to which display refresh, by target time instead of by arrival.
 *
 * The renderer thread admits each decoded output buffer with the time it should be seen: its
 * mapped source time plus the reserve, or, when it has no usable source time, the moment it was
 * decoded (the arrival fallback, which behaves like the legacy queue). The vsync thread then offers
 * the refreshes it can still reach and gets back at most one frame to release, at one refresh:
 *
 * - The earliest reachable refresh at or after the oldest frame's target (within half a refresh)
 *   is the slot. Nothing is released while every reachable refresh is too early for it, and
 *   nothing is released more than two refreshes ahead, because a buffer released for the future is
 *   held from the decoder until then.
 * - Of the frames due at that slot, the newest is shown and older ones are dropped as superseded:
 *   the display can show one of them, and releasing two for one refresh would let the surface drop
 *   one silently. A slot is never used twice.
 * - A frame already past its target is shown once at the earliest slot, its lateness counted, and
 *   nothing re-anchors on it. The next frame keeps its own slot.
 * - Every release stays outstanding until there is evidence it is done (W26 F6, review W34 D1): its
 *   refresh has passed, or the codec reported it (or a later frame) rendered, whichever comes
 *   first. A release is for a future refresh and MediaCodec keeps the buffer until then; absent a
 *   matched completion, the scheduled presentation time is the conservative local boundary. A
 *   queueing deadline passing is not such evidence, and closes nothing.
 * - No more releases are outstanding than the display's own pipeline holds plus one: at most
 *   L + 1, where L ([pipelineDepth]) is the refreshes from now to the earliest reachable one,
 *   1 when unknown and at most [MAX_PIPELINE_DEPTH]. Releasing every frame for the earliest
 *   reachable refresh, as the legacy path effectively does, keeps L buffers in the display's
 *   pipeline; the one more allows a release one refresh further out. So this costs no frame where
 *   the legacy path keeps up, and a malformed or stalled timeline can never hold more than
 *   [MAX_PIPELINE_DEPTH] + 1. A vsync over the bound releases nothing; the frames keep waiting in
 *   the bounded queue, and one that falls behind meanwhile takes the late-frame rule.
 * - At most [capacity] frames wait (1 for the latency profile, 2 for balanced). A frame that would
 *   exceed that drops the oldest, as the legacy queue did. No synthetic repeat is ever made.
 * - Every frame carries the [Fence] it was aimed under: the mapper epoch and the surface and decoder
 *   generations. When the fence moves (a mapping reset, a display or surface change, a codec flush),
 *   [fence] hands back every frame aimed under the old one, to drop, and admission order starts
 *   again: a target from a mapping that no longer exists is neither shown nor allowed to hold back
 *   the next frame.
 *
 * Pure apart from its own lock: admit() and onVsync() run on different threads. It never touches
 * the codec; the caller releases or drops what it is handed, each index exactly once.
 */
class SourceTimeScheduler(private val capacity: Int) {
    /** The mapping and the output a frame was aimed under. */
    data class Fence(val epoch: Long, val surfaceGeneration: Int, val decoderGeneration: Int)

    data class Entry(
        val index: Int,
        val ptsUs: Long,
        val decodedNs: Long,
        val targetNs: Long,
        /** True when the target came from source time, false for the arrival fallback. */
        val sourceTimed: Boolean,
        val fence: Fence = NO_FENCE,
    )

    data class Decision(
        val release: Entry?,
        /** The refresh [release] is for; the renderTimestampNs to give releaseOutputBuffer. */
        val releaseAtNs: Long,
        /** Older frames due at the same slot, to drop without rendering. */
        val superseded: List<Entry>,
    )

    data class Counters(
        val queued: Int,
        val released: Long,
        val releasedLate: Long,
        val superseded: Long,
        val overflow: Long,
        val arrivalFallback: Long,
        val maxLatenessNs: Long,
        /** Waiting frames dropped because the fence they were aimed under moved. */
        val staleDropped: Long = 0,
        /** Vsyncs with frames waiting that released nothing because L + 1 releases were outstanding. */
        val inFlightHeld: Long = 0,
    )

    private val queue = ArrayDeque<Entry>(capacity + 1)
    private var lastAdmittedTargetNs = Long.MIN_VALUE
    private var lastReleasedSlotNs = Long.MIN_VALUE
    private var released = 0L
    private var releasedLate = 0L
    private var supersededCount = 0L
    private var overflowCount = 0L
    private var arrivalFallback = 0L
    private var maxLatenessNs = 0L
    private var staleDropped = 0L
    private var currentFence = NO_FENCE
    private var inFlightHeld = 0L

    // Every release not yet known to be done, oldest first: its codec timestamp and its refresh.
    // Both only increase from one release to the next. The gate keeps it to MAX_PIPELINE_DEPTH + 1.
    private val outstandingPtsUs = LongArray(MAX_PIPELINE_DEPTH + 1)
    private val outstandingSlotNs = LongArray(MAX_PIPELINE_DEPTH + 1)
    private var outstanding = 0

    // What the last release changed, so one that never reached the codec can be taken back.
    private var lastReleasePtsUs = NONE
    private var slotBeforeLastRelease = Long.MIN_VALUE
    private var lastReleaseLate = false

    init {
        require(capacity >= 1) { "capacity must be at least one frame" }
    }

    /**
     * Queue a decoded frame. [targetNs] null means it has no usable source time and is aimed at
     * its decode time. Returns the frames this pushed out (oldest first), to drop unrendered.
     */
    @Synchronized
    fun admit(index: Int, ptsUs: Long, decodedNs: Long, targetNs: Long?, fence: Fence = currentFence): List<Entry> {
        val sourceTimed = targetNs != null
        if (!sourceTimed) arrivalFallback++
        // Admission order is display order: a fallback frame never jumps ahead of a timed one.
        val target = maxOf(targetNs ?: decodedNs, lastAdmittedTargetNs)
        lastAdmittedTargetNs = target
        val dropped = ArrayList<Entry>(1)
        while (queue.size >= capacity) {
            dropped += queue.removeFirst()
            overflowCount++
        }
        queue.addLast(Entry(index, ptsUs, decodedNs, target, sourceTimed, fence))
        return dropped
    }

    /**
     * The fence is now [current]. Returns the waiting frames aimed under any other one (oldest
     * first), to drop unrendered; nothing when it has not moved.
     */
    @Synchronized
    fun fence(current: Fence): List<Entry> {
        if (current == currentFence) return emptyList()
        val previous = currentFence
        currentFence = current
        val stale = queue.filter { it.fence != current }
        if (stale.isNotEmpty()) {
            queue.removeAll { it.fence != current }
            staleDropped += stale.size
        }
        // Display order holds within one mapping; the old mapping's targets no longer bound it.
        lastAdmittedTargetNs = queue.lastOrNull()?.targetNs ?: Long.MIN_VALUE
        // Releases for another surface or a flushed codec no longer hold this one back. A new
        // epoch on the same surface does: those buffers are still waiting for their refreshes.
        if (previous.surfaceGeneration != current.surfaceGeneration || previous.decoderGeneration != current.decoderGeneration) {
            forgetOutstanding()
        }
        return stale
    }

    /** The codec reported a frame rendered: that release, and every earlier one, is done. */
    @Synchronized
    fun onRendered(ptsUs: Long) {
        // Frames render in release order and codec timestamps only increase.
        var done = 0
        while (done < outstanding && outstandingPtsUs[done] <= ptsUs) done++
        closeOldest(done)
    }

    /** The refresh the oldest outstanding release is for, or null when none is outstanding. */
    @get:Synchronized
    val inFlightSlot: Long?
        get() = if (outstanding > 0) outstandingSlotNs[0] else null

    /**
     * One vsync at [nowNs]. [slotsNs] are the refreshes still reachable, ascending, each with the
     * deadline in [deadlinesNs] by which a buffer for it must be queued; [vsyncPeriodNs] the display
     * period. Returns what to release (if anything) and what it supersedes.
     *
     * A deadline says when a buffer must be queued, not that a queued one is done (W34 D1), so no
     * deadline closes an outstanding release; the renderer still supplies them with every slot.
     */
    @Synchronized
    fun onVsync(nowNs: Long, slotsNs: LongArray, deadlinesNs: LongArray, vsyncPeriodNs: Long): Decision {
        require(deadlinesNs.size == slotsNs.size) { "one deadline per slot" }
        // Releases whose refresh has passed are done. Slots only increase, so they are the oldest.
        var passed = 0
        while (passed < outstanding && nowNs >= outstandingSlotNs[passed] + PRESENTED_TOLERANCE_NS) passed++
        closeOldest(passed)
        if (queue.isEmpty() || slotsNs.isEmpty() || vsyncPeriodNs <= 0) return NOTHING
        if (outstanding >= pipelineDepth(nowNs, slotsNs[0], vsyncPeriodNs) + 1) {
            inFlightHeld++
            return NOTHING
        }
        val half = vsyncPeriodNs / 2
        val usable = slotsNs.filter { lastReleasedSlotNs == Long.MIN_VALUE || it > lastReleasedSlotNs + half }
        if (usable.isEmpty()) return NOTHING

        val head = queue.first()
        val slot = usable.firstOrNull { it >= head.targetNs - half } ?: return NOTHING
        if (slot - usable[0] > vsyncPeriodNs + half) {
            // Due further out than the next two refreshes: a later vsync will reach it.
            return NOTHING
        }

        val due = ArrayList<Entry>(queue.size)
        while (queue.isNotEmpty() && queue.first().targetNs <= slot + half) {
            due += queue.removeFirst()
        }
        val shown = due.removeAt(due.size - 1)
        supersededCount += due.size
        released++
        val lateness = slot - half - shown.targetNs
        lastReleaseLate = lateness > 0
        if (lateness > 0) {
            releasedLate++
            if (lateness > maxLatenessNs) maxLatenessNs = lateness
        }
        lastReleasePtsUs = shown.ptsUs
        slotBeforeLastRelease = lastReleasedSlotNs
        lastReleasedSlotNs = slot
        outstandingPtsUs[outstanding] = shown.ptsUs
        outstandingSlotNs[outstanding] = slot
        outstanding++
        return Decision(shown, slot, due)
    }

    /**
     * The release [onVsync] last handed out never reached the codec: its fence moved between the
     * decision and the hand-off (W34 D2). Nothing is in flight for it and its refresh is free again;
     * it counts as a frame dropped under an old fence, not as released. Its lateness, if any, stays
     * in [Counters.maxLatenessNs].
     */
    @Synchronized
    fun withdraw(entry: Entry) {
        staleDropped++
        if (entry.ptsUs != lastReleasePtsUs) return
        lastReleasePtsUs = NONE
        released--
        if (lastReleaseLate) releasedLate--
        lastReleasedSlotNs = slotBeforeLastRelease
        if (outstanding > 0 && outstandingPtsUs[outstanding - 1] == entry.ptsUs) outstanding--
    }

    private fun closeOldest(count: Int) {
        if (count <= 0) return
        System.arraycopy(outstandingPtsUs, count, outstandingPtsUs, 0, outstanding - count)
        System.arraycopy(outstandingSlotNs, count, outstandingSlotNs, 0, outstanding - count)
        outstanding -= count
    }

    private fun forgetOutstanding() {
        outstanding = 0
    }

    /** Forget every waiting frame and hand them back (to drop, or to abandon after a flush). */
    @Synchronized
    fun clear(): List<Entry> {
        val waiting = queue.toList()
        queue.clear()
        lastAdmittedTargetNs = Long.MIN_VALUE
        lastReleasedSlotNs = Long.MIN_VALUE
        lastReleasePtsUs = NONE
        // A flushed or stopping codec returns every buffer, those outstanding included.
        forgetOutstanding()
        return waiting
    }

    @Synchronized
    fun counters(): Counters =
        Counters(queue.size, released, releasedLate, supersededCount, overflowCount, arrivalFallback, maxLatenessNs, staleDropped,
            inFlightHeld)

    companion object {
        /** Before any fence is set. */
        @JvmField
        val NO_FENCE = Fence(Long.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

        private val NOTHING = Decision(null, 0L, emptyList())
        private const val NONE = Long.MIN_VALUE

        /**
         * The deepest display pipeline the gate trusts (W34 D1): at most this + 1 releases are ever
         * outstanding, whatever a malformed or stalled frame timeline offers. Four refreshes cover a
         * 25 ms lead up to 144 Hz.
         */
        const val MAX_PIPELINE_DEPTH = 4

        /**
         * How far past its refresh a release is done when no render callback said so: none. The
         * slot itself is the boundary (W34 D1). A positive tolerance would count the refresh just
         * presented as outstanding on callbacks that run within it, which the L + 1 bound, measured
         * from now, would then hold against an ordinary release one refresh out. The declared safety
         * timeout for a missing callback, the slot plus two refreshes, is therefore never reached and
         * needs no timer of its own.
         */
        const val PRESENTED_TOLERANCE_NS = 0L

        /**
         * The display's own pipeline depth L at [nowNs]: the refreshes from now to [earliestSlotNs],
         * the earliest still reachable, rounded up. 1 when that is unknown (no period, or a slot not
         * ahead of now), and at most [MAX_PIPELINE_DEPTH].
         */
        @JvmStatic
        fun pipelineDepth(nowNs: Long, earliestSlotNs: Long, periodNs: Long): Int {
            if (periodNs <= 0 || earliestSlotNs <= nowNs) return 1
            val lead = earliestSlotNs - nowNs
            if (lead > MAX_PIPELINE_DEPTH * periodNs) return MAX_PIPELINE_DEPTH
            return ((lead + periodNs - 1) / periodNs).toInt().coerceIn(1, MAX_PIPELINE_DEPTH)
        }
    }
}
