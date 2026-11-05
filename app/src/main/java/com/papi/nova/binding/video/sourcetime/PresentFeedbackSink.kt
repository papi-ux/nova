package com.papi.nova.binding.video.sourcetime

import kotlin.math.abs

/**
 * What MediaCodec's frame-rendered callback says about the refreshes the scheduler asked for,
 * observed and never acted on.
 *
 * The documentation calls these callbacks informational, possibly delayed and batched, and before
 * Android 14 not guaranteed for every rendered frame. So this only measures: how many released
 * frames came back at all (coverage) and how far the reported render time was from the requested
 * refresh. Whether those times agree with what the display really did is for a device receipt
 * (Perfetto SurfaceFlinger FrameTimeline against these numbers) to establish; until one does, no
 * pacing decision reads them. USE_FRAME_RENDER_TIME and its end-to-end statistic are separate and
 * unchanged.
 *
 * Thread-safe: released() runs on the vsync thread, rendered() on the codec's callback thread.
 */
class PresentFeedbackSink(private val capacity: Int = 64) {
    data class Counters(
        val released: Long,
        val matched: Long,
        val unmatched: Long,
        val withinOneVsync: Long,
        val maxAbsErrorNs: Long,
        val sumAbsErrorNs: Long,
    ) {
        /** Released frames whose render was reported; NaN before any release. */
        val coverage: Double
            get() = if (released == 0L) Double.NaN else matched.toDouble() / released.toDouble()
    }

    private class Pending(val ptsUs: Long, val slotNs: Long, val vsyncPeriodNs: Long)

    private val pending = ArrayDeque<Pending>(capacity)
    private var released = 0L
    private var matched = 0L
    private var unmatched = 0L
    private var withinOneVsync = 0L
    private var maxAbsErrorNs = 0L
    private var sumAbsErrorNs = 0L

    @Synchronized
    fun released(ptsUs: Long, slotNs: Long, vsyncPeriodNs: Long) {
        if (pending.size >= capacity) pending.removeFirst()
        pending.addLast(Pending(ptsUs, slotNs, vsyncPeriodNs))
        released++
    }

    @Synchronized
    fun rendered(ptsUs: Long, renderNs: Long) {
        // Callbacks arrive in render order, so anything older than this frame was never reported.
        while (pending.isNotEmpty() && pending.first().ptsUs != ptsUs) {
            if (pending.none { it.ptsUs == ptsUs }) {
                unmatched++
                return
            }
            pending.removeFirst()
        }
        val frame = pending.removeFirstOrNull() ?: run {
            unmatched++
            return
        }
        matched++
        val error = abs(renderNs - frame.slotNs)
        sumAbsErrorNs += error
        if (error > maxAbsErrorNs) maxAbsErrorNs = error
        if (error <= frame.vsyncPeriodNs) withinOneVsync++
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun counters(): Counters =
        Counters(released, matched, unmatched, withinOneVsync, maxAbsErrorNs, sumAbsErrorNs)
}
