package com.papi.nova.utils

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceDataTrackerTest {

    private lateinit var tracker: PerformanceDataTracker
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tracker = PerformanceDataTracker()
    }

    @After
    fun tearDown() {
        tracker.clearLogs(context)
    }

    @Test
    fun saveThenRetrieveLogs() {
        tracker.savePerformanceStatistics(
            context,
            "Pixel 10",
            "16",
            "1.0",
            "HEVC",
            "2.5ms",
            "OK",
            "20Mbps",
            "1080p",
            "60",
            "15ms",
            "balanced",
            "2026-04-09"
        )

        Thread.sleep(500)

        val log = tracker.getLog(context)
        assertNotNull(log)
        assertTrue(log.contains("Pixel 10"))
        assertTrue(log.contains("HEVC"))
    }

    @Test
    fun duplicateConfigKeepsBetterResult() {
        tracker.savePerformanceStatistics(
            context,
            "RP6",
            "14",
            "1.0",
            "HEVC",
            "5.0ms",
            "OK",
            "20Mbps",
            "1080p",
            "60",
            "15ms",
            "balanced",
            "2026-04-09"
        )
        Thread.sleep(200)

        tracker.savePerformanceStatistics(
            context,
            "RP6",
            "14",
            "1.0",
            "HEVC",
            "2.0ms",
            "OK",
            "20Mbps",
            "1080p",
            "60",
            "15ms",
            "balanced",
            "2026-04-09"
        )
        Thread.sleep(200)

        val log = tracker.getLog(context)
        assertTrue(log.contains("2.0ms"))
    }

    @Test
    fun clearLogsRemovesAll() {
        tracker.savePerformanceStatistics(
            context,
            "Test",
            "16",
            "1.0",
            "H264",
            "3ms",
            "OK",
            "10Mbps",
            "720p",
            "30",
            "20ms",
            "low_latency",
            "2026-04-09"
        )
        Thread.sleep(200)

        tracker.clearLogs(context)
        val log = tracker.getLog(context)
        assertEquals("[]", log)
    }

    @Test
    fun emptyLogReturnsEmptyArray() {
        val log = tracker.getLog(context)
        assertEquals("[]", log)
    }
}
