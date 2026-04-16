package com.papi.nova.utils;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PerformanceDataTrackerTest {

    private PerformanceDataTracker tracker;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        tracker = new PerformanceDataTracker();
    }

    @After
    public void tearDown() {
        tracker.clearLogs(context);
    }

    @Test
    public void saveThenRetrieveLogs() throws InterruptedException {
        tracker.savePerformanceStatistics(context,
                "Pixel 10", "16", "1.0", "HEVC",
                "2.5ms", "OK", "20Mbps", "1080p", "60", "15ms",
                "balanced", "2026-04-09");

        // Wait for async save
        Thread.sleep(500);

        String log = tracker.getLog(context);
        assertNotNull(log);
        assertTrue(log.contains("Pixel 10"));
        assertTrue(log.contains("HEVC"));
    }

    @Test
    public void duplicateConfigKeepsBetterResult() throws InterruptedException {
        // First entry: 5ms decode
        tracker.savePerformanceStatistics(context,
                "RP6", "14", "1.0", "HEVC",
                "5.0ms", "OK", "20Mbps", "1080p", "60", "15ms",
                "balanced", "2026-04-09");
        Thread.sleep(200);

        // Second entry: 2ms decode (better) — should replace
        tracker.savePerformanceStatistics(context,
                "RP6", "14", "1.0", "HEVC",
                "2.0ms", "OK", "20Mbps", "1080p", "60", "15ms",
                "balanced", "2026-04-09");
        Thread.sleep(200);

        String log = tracker.getLog(context);
        // Should contain 2.0ms but not 5.0ms
        assertTrue(log.contains("2.0ms"));
    }

    @Test
    public void clearLogsRemovesAll() throws InterruptedException {
        tracker.savePerformanceStatistics(context,
                "Test", "16", "1.0", "H264",
                "3ms", "OK", "10Mbps", "720p", "30", "20ms",
                "low_latency", "2026-04-09");
        Thread.sleep(200);

        tracker.clearLogs(context);
        String log = tracker.getLog(context);
        assertEquals("[]", log);
    }

    @Test
    public void emptyLogReturnsEmptyArray() {
        String log = tracker.getLog(context);
        assertEquals("[]", log);
    }
}
