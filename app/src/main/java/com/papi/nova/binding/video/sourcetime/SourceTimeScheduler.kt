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
 * - At most [capacity] frames wait (1 for the latency profile, 2 for balanced). A frame that would
 *   exceed that drops the oldest, as the legacy queue did. No synthetic repeat is ever made.
 *
 * Pure apart from its own lock: admit() and onVsync() run on different threads. It never touches
 * the codec; the caller releases or drops what it is handed, each index exactly once.
 */
class SourceTimeScheduler(private val capacity: Int) {
    data class Entry(
        val index: Int,
        val ptsUs: Long,
        val decodedNs: Long,
        val targetNs: Long,
        /** True when the target came from source time, false for the arrival fallback. */
        val sourceTimed: Boolean,
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

    init {
        require(capacity >= 1) { "capacity must be at least one frame" }
    }

    /**
     * Queue a decoded frame. [targetNs] null means it has no usable source time and is aimed at
     * its decode time. Returns the frames this pushed out (oldest first), to drop unrendered.
     */
    @Synchronized
    fun admit(index: Int, ptsUs: Long, decodedNs: Long, targetNs: Long?): List<Entry> {
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
        queue.addLast(Entry(index, ptsUs, decodedNs, target, sourceTimed))
        return dropped
    }

    /**
     * One vsync. [slotsNs] are the refreshes still reachable from now, ascending; [vsyncPeriodNs]
     * the display period. Returns what to release (if anything) and what it supersedes.
     */
    @Synchronized
    fun onVsync(slotsNs: LongArray, vsyncPeriodNs: Long): Decision {
        if (queue.isEmpty() || slotsNs.isEmpty() || vsyncPeriodNs <= 0) return NOTHING
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
        return Decision(shown, slot, due)
    }

    /** Forget every waiting frame and hand them back (to drop, or to abandon after a flush). */
    @Synchronized
    fun clear(): List<Entry> {
        val waiting = queue.toList()
        queue.clear()
        lastAdmittedTargetNs = Long.MIN_VALUE
        lastReleasedSlotNs = Long.MIN_VALUE
        return waiting
    }

    @Synchronized
    fun counters(): Counters =
        Counters(queue.size, released, releasedLate, supersededCount, overflowCount, arrivalFallback, maxLatenessNs)

    private companion object {
        val NOTHING = Decision(null, 0L, emptyList())
    }
}
