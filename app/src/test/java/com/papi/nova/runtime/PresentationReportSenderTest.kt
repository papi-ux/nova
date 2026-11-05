package com.papi.nova.runtime

import com.papi.nova.binding.video.sourcetime.FrameWindow
import com.papi.nova.binding.video.sourcetime.SourceTimeCounters
import com.papi.nova.binding.video.sourcetime.SourceTimeReport
import com.papi.nova.binding.video.sourcetime.SourceTimeScheduler
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W34 D3: the end of a stream used to find an ordinary report in flight, return, and never be tried
 * again. These drive the sender with the renderer's report as the payload: the refresh in flight
 * carries the counts of second 5, the end those of the stream's last second.
 */
class PresentationReportSenderTest {
    /** The renderer's report after [seconds] of a steady 60 FPS stream. */
    private fun report(seconds: Int) = SourceTimeReport(
        renderer = SourceTimeReport.RENDERER_MEDIACODEC,
        counters = SourceTimeCounters.Snapshot(emptyMap(), 0, seconds * 60L, 0, 0),
        scheduler = SourceTimeScheduler.Counters(1, seconds * 60L, seconds.toLong(), 0, 0, 0, 0),
        window = FrameWindow(59.5 + seconds % 2, seconds % 3, 120),
    )

    private val sent = Collections.synchronizedList(ArrayList<Pair<PresentationReportCadence.Why, SourceTimeReport>>())

    @Test(timeout = 20_000)
    fun anEndWhileARefreshIsInFlightIsSentOnceAfterItReturnsWithTheFinalWindowAndCounts() {
        val sender = PresentationReportSender(PresentationReportCadence(), endWaitMs = 10_000)
        val onTheWire = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val refresh = thread {
            sender.report("steady", 5_000) { why ->
                onTheWire.countDown()
                answer.await(10, TimeUnit.SECONDS)
                sent += why to report(5)
                true
            }
        }
        assertTrue(onTheWire.await(5, TimeUnit.SECONDS))

        // The stream ends while that refresh is on the wire. Its payload is captured now.
        assertTrue(sender.endRequested())
        val last = report(12)
        val end = sender.endAsync("steady", 12_000) { why -> sent += why to last; true }
        assertEquals(PresentationReportSender.THREAD_NAME, end.name)
        assertTrue("a thread of its own, not the Activity's", end.isDaemon)
        end.join(300)
        assertTrue("the end waits behind the refresh", end.isAlive)
        assertTrue("nothing completed yet", sent.isEmpty())
        // A poll that runs meanwhile sends nothing older after the end.
        assertNull(sender.report("changed", 12_500) { error("no ordinary report after the end") })

        answer.countDown()
        refresh.join(5_000)
        end.join(5_000)
        assertFalse(end.isAlive)
        assertEquals("the report on the wire, then exactly one end",
            listOf(PresentationReportCadence.Why.TRANSITION, PresentationReportCadence.Why.END), sent.map { it.first })
        assertTrue("the end carries the stream's final window and counts", sent[1].second === last)
        assertEquals(720L, sent[1].second.scheduler!!.released)

        // Exactly once: a second end sends nothing.
        assertFalse(sender.end("steady", 13_000) { error("the end was already sent") })
        assertEquals(2, sent.size)
    }

    @Test(timeout = 20_000)
    fun anEndDuringAStuckSendStillGoesWithinItsBound() {
        val sender = PresentationReportSender(PresentationReportCadence(), endWaitMs = 200)
        val onTheWire = CountDownLatch(1)
        val never = CountDownLatch(1)
        val stuck = thread(isDaemon = true) {
            sender.report("steady", 5_000) {
                onTheWire.countDown()
                never.await()
                true
            }
        }
        assertTrue(onTheWire.await(5, TimeUnit.SECONDS))
        val startNs = System.nanoTime()
        val end = sender.endAsync("steady", 12_000) { why -> sent += why to report(12); true }
        end.join(5_000)
        val waitedMs = (System.nanoTime() - startNs) / 1_000_000
        assertFalse("the end is not held by a send that never returns", end.isAlive)
        assertEquals(listOf(PresentationReportCadence.Why.END), sent.map { it.first })
        assertTrue("it waited its bound first ($waitedMs ms)", waitedMs >= 200)
        never.countDown()
        stuck.join(5_000)
    }

    @Test
    fun noOrdinaryReportIsSentOnceTheEndIsRequested() {
        val sender = PresentationReportSender(PresentationReportCadence())
        assertEquals(PresentationReportCadence.Why.TRANSITION, sender.report("a", 0) { true })
        assertTrue(sender.endRequested())
        assertFalse("requested once", sender.endRequested())
        assertNull(sender.report("b", 1_000) { error("sent after the end") })
        assertNull(sender.report("a", 60_000) { error("sent after the end") })
    }

    @Test
    fun anOrdinaryReportStillWaitsForTheCadenceAndSkipsWhileAnotherIsInFlight() {
        val sender = PresentationReportSender(PresentationReportCadence())
        var nested: PresentationReportCadence.Why? = PresentationReportCadence.Why.END
        assertEquals(PresentationReportCadence.Why.TRANSITION, sender.report("a", 0) {
            // A second report while this one is on the wire is skipped, as before.
            nested = sender.report("b", 0) { error("two on the wire") }
            true
        })
        assertNull(nested)
        assertNull("unchanged and not yet due", sender.report("a", 1_000) { true })
        assertEquals(PresentationReportCadence.Why.REFRESH, sender.report("a", 5_000) { true })
        // A failed send stays due at the next poll.
        assertEquals(PresentationReportCadence.Why.TRANSITION, sender.report("c", 6_000) { false })
        assertEquals(PresentationReportCadence.Why.TRANSITION, sender.report("c", 7_000) { true })
    }
}
