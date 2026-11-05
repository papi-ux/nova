package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentFeedbackSinkTest {
    private val vsync = 8_333_333L

    @Test
    fun renderTimesAreComparedWithTheRefreshThatWasAskedFor() {
        val sink = PresentFeedbackSink()
        sink.released(100, 1_000_000_000, vsync)
        sink.released(200, 1_008_333_333, vsync)
        sink.rendered(100, 1_000_500_000)
        sink.rendered(200, 1_030_000_000)
        val counters = sink.counters()
        assertEquals(2L, counters.matched)
        assertEquals(1L, counters.withinOneVsync)
        assertEquals(21_666_667L, counters.maxAbsErrorNs)
        assertEquals(1.0, counters.coverage, 0.0)
    }

    @Test
    fun aFrameNeverReportedLowersCoverageAndAnUnknownOneIsCounted() {
        val sink = PresentFeedbackSink()
        sink.released(100, 1_000_000_000, vsync)
        sink.released(200, 1_008_333_333, vsync)
        // Before Android 14 a rendered frame may never be reported: 100 is skipped.
        sink.rendered(200, 1_008_400_000)
        sink.rendered(999, 1_010_000_000)
        val counters = sink.counters()
        assertEquals(1L, counters.matched)
        assertEquals(1L, counters.unmatched)
        assertEquals(0.5, counters.coverage, 0.0)
    }

    @Test
    fun theWaitingListIsBounded() {
        val sink = PresentFeedbackSink(capacity = 4)
        for (i in 0 until 100) sink.released(i.toLong(), i * vsync, vsync)
        sink.rendered(0, 0)
        assertEquals("evicted records are not matched later", 1L, sink.counters().unmatched)
        sink.rendered(99, 99 * vsync)
        assertTrue(sink.counters().matched == 1L)
    }
}
