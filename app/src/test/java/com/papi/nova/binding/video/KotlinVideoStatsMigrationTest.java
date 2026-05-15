package com.papi.nova.binding.video;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KotlinVideoStatsMigrationTest {
    @Test
    public void videoStatsIsKotlinSource() {
        File javaFile = new File("src/main/java/com/papi/nova/binding/video/VideoStats.java");
        File kotlinFile = new File("src/main/java/com/papi/nova/binding/video/VideoStats.kt");

        assertFalse("VideoStats should no longer be a Java source", javaFile.exists());
        assertTrue("VideoStats should be migrated to Kotlin", kotlinFile.exists());
    }

    @Test
    public void addCopyAndClearKeepExistingStatsSemantics() {
        VideoStats first = new VideoStats();
        first.decoderTimeMs = 10;
        first.totalTimeMs = 20;
        first.totalFrames = 30;
        first.totalFramesReceived = 25;
        first.totalFramesRendered = 24;
        first.frameLossEvents = 1;
        first.framesLost = 2;
        first.minHostProcessingLatency = 8;
        first.maxHostProcessingLatency = 10;
        first.totalHostProcessingLatency = 18;
        first.framesWithHostProcessingLatency = 2;
        first.measurementStartTimestamp = 100;

        VideoStats second = new VideoStats();
        second.decoderTimeMs = 5;
        second.totalTimeMs = 6;
        second.totalFrames = 7;
        second.totalFramesReceived = 8;
        second.totalFramesRendered = 9;
        second.frameLossEvents = 3;
        second.framesLost = 4;
        second.minHostProcessingLatency = 4;
        second.maxHostProcessingLatency = 12;
        second.totalHostProcessingLatency = 16;
        second.framesWithHostProcessingLatency = 4;
        second.measurementStartTimestamp = 120;

        first.add(second);

        assertEquals(15, first.decoderTimeMs);
        assertEquals(26, first.totalTimeMs);
        assertEquals(37, first.totalFrames);
        assertEquals(33, first.totalFramesReceived);
        assertEquals(33, first.totalFramesRendered);
        assertEquals(4, first.frameLossEvents);
        assertEquals(6, first.framesLost);
        assertEquals(4, first.minHostProcessingLatency);
        assertEquals(12, first.maxHostProcessingLatency);
        assertEquals(34, first.totalHostProcessingLatency);
        assertEquals(6, first.framesWithHostProcessingLatency);
        assertEquals(100, first.measurementStartTimestamp);

        VideoStats copy = new VideoStats();
        copy.copy(first);
        assertEquals(first.decoderTimeMs, copy.decoderTimeMs);
        assertEquals(first.totalFramesRendered, copy.totalFramesRendered);
        assertEquals(first.minHostProcessingLatency, copy.minHostProcessingLatency);
        assertEquals(first.measurementStartTimestamp, copy.measurementStartTimestamp);

        copy.clear();
        assertEquals(0, copy.decoderTimeMs);
        assertEquals(0, copy.totalFramesRendered);
        assertEquals(0, copy.minHostProcessingLatency);
        assertEquals(0, copy.measurementStartTimestamp);
    }
}
