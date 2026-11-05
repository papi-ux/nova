package com.papi.nova.runtime

import com.papi.nova.api.PolarisSessionVrr
import com.papi.nova.binding.video.sourcetime.FrameWindow
import com.papi.nova.binding.video.sourcetime.PresentationTruth
import com.papi.nova.binding.video.sourcetime.SourceTimeCounters
import com.papi.nova.binding.video.sourcetime.SourceTimeEffective
import com.papi.nova.binding.video.sourcetime.SourceTimePacingStatus
import com.papi.nova.binding.video.sourcetime.SourceTimeReason
import com.papi.nova.binding.video.sourcetime.SourceTimeReport
import com.papi.nova.binding.video.sourcetime.SourceTimeRequest
import com.papi.nova.binding.video.sourcetime.SourceTimeScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W26 F5: a steady stream's report state never changes, so its frame window and counts have to be
 * sent on a cadence of their own. This drives the cadence the way Game does (a status poll every
 * second, the report key built from state only) with the payload the shared reducer makes from a
 * renderer whose counts keep moving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PresentationReportCadenceTest {
    private val fixed = SourceTimePacingStatus(SourceTimeRequest.SOURCE_TIME, SourceTimeEffective.SOURCE_TIME_FIXED, SourceTimeReason.NONE)

    /** The renderer's report after [seconds] of a steady 60 FPS stream: counts move, state does not. */
    private fun report(seconds: Int) = SourceTimeReport(
        renderer = SourceTimeReport.RENDERER_MEDIACODEC,
        counters = SourceTimeCounters.Snapshot(emptyMap(), 0, seconds * 60L, 0, 0),
        scheduler = SourceTimeScheduler.Counters(1, seconds * 60L, seconds.toLong(), 0, 0, 0, 0),
        window = if (seconds >= 1) FrameWindow(59.5 + seconds % 2, seconds % 3, 120) else null,
    )

    private class Upload(val key: String, val why: PresentationReportCadence.Why, val presentation: JSONObject, val runtime: JSONObject)

    /** What Game sends: the st1 state in the key, the window and counts beside it. */
    private fun upload(truth: PresentationTruth, key: String, why: PresentationReportCadence.Why) = Upload(
        key,
        why,
        JSONObject().apply {
            put("frame_pacing_state", truth.client.toFramePacingState())
            truth.renderFps?.let { put("render_fps", it) }
            truth.droppedFrameRatio?.let { put("dropped_frame_ratio", it) }
        },
        truth.toRuntimeJson(),
    )

    @Test
    fun anUnchangedStateIsSentAgainEveryFiveSecondsWithItsCountsCurrentAndAtTheEnd() {
        val cadence = PresentationReportCadence()
        val uploads = ArrayList<Upload>()
        // A one-second status poll for twelve seconds of a steady stream, as Game polls.
        for (second in 0..12) {
            val nowMs = 1_000_000L + second * 1_000L
            val truth = PresentationTruth.from(fixed, null, PolarisSessionVrr.Reading.Absent, true, 41, 120f, report = report(second))
            val key = "token|120|" + truth.client.toFramePacingState()
            val why = cadence.due(key, nowMs) ?: continue
            uploads += upload(truth, key, why)
            cadence.sent(key, nowMs)
        }
        assertEquals(listOf(PresentationReportCadence.Why.TRANSITION, PresentationReportCadence.Why.REFRESH,
            PresentationReportCadence.Why.REFRESH), uploads.map { it.why })
        val (first, second) = uploads
        assertEquals("the state key never changed", first.key, second.key)
        // The first report came before the renderer had a window; the refresh carries one, and counts that moved.
        assertEquals(false, first.presentation.has("render_fps"))
        assertEquals(60.5, second.presentation.getDouble("render_fps"), 0.0)
        assertNotEquals(first.runtime.getJSONObject("counts").getLong("on_slot"), second.runtime.getJSONObject("counts").getLong("on_slot"))
        assertEquals("300 released by second 5, 5 of them late", 295L, second.runtime.getJSONObject("counts").getLong("on_slot"))
        // Half a second after the last refresh nothing is due, except the end of the stream.
        assertNull(cadence.due(uploads.last().key, 1_012_500L))
        assertEquals(PresentationReportCadence.Why.END, cadence.due(uploads.last().key, 1_012_500L, ending = true))
    }

    @Test
    fun aTransitionIsSentAtOnceAndAFailedSendIsRetriedAtTheNextPoll() {
        val cadence = PresentationReportCadence()
        assertEquals(PresentationReportCadence.Why.TRANSITION, cadence.due("a", 0))
        // Not marked sent (the request failed): still due a second later.
        assertEquals(PresentationReportCadence.Why.TRANSITION, cadence.due("a", 1_000))
        cadence.sent("a", 1_000)
        assertNull(cadence.due("a", 2_000))
        assertEquals(PresentationReportCadence.Why.TRANSITION, cadence.due("b", 2_000))
        cadence.sent("b", 2_000)
        assertNull(cadence.due("b", 6_999))
        assertEquals(PresentationReportCadence.Why.REFRESH, cadence.due("b", 7_000))
    }
}
