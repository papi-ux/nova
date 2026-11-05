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
 * - At most one release is in flight (W26 F6; design C.3 "plus one in-flight release"). A release
 *   is for a future refresh, and MediaCodec keeps the buffer until that refresh has been taken. So
 *   the next release waits until the previous one's slot deadline has passed (the compositor has
 *   latched it or let it go) or the codec reported it rendered, whichever comes first. Until then
 *   the frames keep waiting in the bounded queue, and a frame that falls behind meanwhile takes the
 *   late-frame rule when the gate opens.
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
        /** Vsyncs with frames waiting that released nothing because a release was in flight. */
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

    // The release in flight: its codec timestamp, the refresh it is for and that refresh's deadline.
    private var inFlightPtsUs = NONE
    private var inFlightSlotNs = NONE
    private var inFlightDeadlineNs = NONE

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
        // A release for another surface or a flushed codec no longer holds this one back. A new
        // epoch on the same surface does: that buffer is still waiting for its refresh.
        if (previous.surfaceGeneration != current.surfaceGeneration || previous.decoderGeneration != current.decoderGeneration) {
            forgetInFlight()
        }
        return stale
    }

    /** The codec reported a frame rendered: the release in flight (or a later one) is done. */
    @Synchronized
    fun onRendered(ptsUs: Long) {
        // Frames render in release order and codec timestamps only increase.
        if (inFlightPtsUs != NONE && ptsUs >= inFlightPtsUs) forgetInFlight()
    }

    /** The refresh the release in flight is for, or null when none is. */
    @get:Synchronized
    val inFlightSlot: Long?
        get() = inFlightSlotNs.takeIf { it != NONE }

    /**
     * One vsync at [nowNs]. [slotsNs] are the refreshes still reachable, ascending, each with the
     * deadline in [deadlinesNs] by which a buffer for it must be queued; [vsyncPeriodNs] the display
     * period. Returns what to release (if anything) and what it supersedes.
     */
    @Synchronized
    fun onVsync(nowNs: Long, slotsNs: LongArray, deadlinesNs: LongArray, vsyncPeriodNs: Long): Decision {
        require(deadlinesNs.size == slotsNs.size) { "one deadline per slot" }
        // The release in flight is due once its refresh's deadline has passed.
        if (inFlightDeadlineNs != NONE && nowNs >= inFlightDeadlineNs) forgetInFlight()
        if (queue.isEmpty() || slotsNs.isEmpty() || vsyncPeriodNs <= 0) return NOTHING
        if (inFlightSlotNs != NONE) {
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
        if (lateness > 0) {
            releasedLate++
            if (lateness > maxLatenessNs) maxLatenessNs = lateness
        }
        lastReleasedSlotNs = slot
        inFlightPtsUs = shown.ptsUs
        inFlightSlotNs = slot
        inFlightDeadlineNs = deadlinesNs[slotsNs.indexOf(slot)]
        return Decision(shown, slot, due)
    }

    private fun forgetInFlight() {
        inFlightPtsUs = NONE
        inFlightSlotNs = NONE
        inFlightDeadlineNs = NONE
    }

    /** Forget every waiting frame and hand them back (to drop, or to abandon after a flush). */
    @Synchronized
    fun clear(): List<Entry> {
        val waiting = queue.toList()
        queue.clear()
        lastAdmittedTargetNs = Long.MIN_VALUE
        lastReleasedSlotNs = Long.MIN_VALUE
        // A flushed or stopping codec returns every buffer, the one in flight included.
        forgetInFlight()
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
    }
}
