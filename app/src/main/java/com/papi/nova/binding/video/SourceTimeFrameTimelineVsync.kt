package com.papi.nova.binding.video

import android.view.Choreographer
import androidx.annotation.RequiresApi

/**
 * The vsync source for source-time scheduling on API 33 and later: Choreographer's frame
 * timelines give the refreshes still reachable (their deadline not yet passed) and their expected
 * presentation times, which is exactly what the scheduler aims at. Older devices use the frame
 * callback and the display's period instead; this class is never loaded on them.
 *
 * Runs on the renderer's Choreographer thread. [onSlots] returns false to stop.
 */
@RequiresApi(33)
internal class SourceTimeFrameTimelineVsync(
    private val onSlots: (LongArray, Long) -> Boolean,
) : Choreographer.VsyncCallback {
    override fun onVsync(data: Choreographer.FrameData) {
        val now = System.nanoTime()
        val timelines = data.frameTimelines
        val slots = timelines
            .filter { it.deadlineNanos > now }
            .map { it.expectedPresentationTimeNanos }
            .sorted()
            .toLongArray()
        val periodNs = if (timelines.size >= 2) {
            timelines[1].expectedPresentationTimeNanos - timelines[0].expectedPresentationTimeNanos
        } else {
            0L
        }
        if (onSlots(slots, periodNs)) {
            Choreographer.getInstance().postVsyncCallback(this)
        }
    }

    fun start() {
        Choreographer.getInstance().postVsyncCallback(this)
    }

    fun stop() {
        Choreographer.getInstance().removeVsyncCallback(this)
    }
}
