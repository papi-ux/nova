package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinVideoStatsMigrationTest {
    @Test
    fun videoStatsIsKotlinSource() {
        val javaFile = File("src/main/java/com/papi/nova/binding/video/VideoStats.java")
        val kotlinFile = File("src/main/java/com/papi/nova/binding/video/VideoStats.kt")

        assertFalse("VideoStats should no longer be a Java source", javaFile.exists())
        assertTrue("VideoStats should be migrated to Kotlin", kotlinFile.exists())
    }

    @Test
    fun addCopyAndClearKeepExistingStatsSemantics() {
        val first = VideoStats()
        first.decoderTimeMs = 10
        first.totalTimeMs = 20
        first.totalFrames = 30
        first.totalFramesReceived = 25
        first.totalFramesRendered = 24
        first.frameLossEvents = 1
        first.framesLost = 2
        first.decoderStarvationEvents = 3
        first.intentionalFrameDrops = 4
        first.watchdogFlushes = 5
        first.outputFormatChanges = 6
        first.minHostProcessingLatency = 8.toChar()
        first.maxHostProcessingLatency = 10.toChar()
        first.totalHostProcessingLatency = 18
        first.framesWithHostProcessingLatency = 2
        first.measurementStartTimestamp = 100

        val second = VideoStats()
        second.decoderTimeMs = 5
        second.totalTimeMs = 6
        second.totalFrames = 7
        second.totalFramesReceived = 8
        second.totalFramesRendered = 9
        second.frameLossEvents = 3
        second.framesLost = 4
        second.decoderStarvationEvents = 5
        second.intentionalFrameDrops = 6
        second.watchdogFlushes = 7
        second.outputFormatChanges = 8
        second.minHostProcessingLatency = 4.toChar()
        second.maxHostProcessingLatency = 12.toChar()
        second.totalHostProcessingLatency = 16
        second.framesWithHostProcessingLatency = 4
        second.measurementStartTimestamp = 120

        first.add(second)

        assertEquals(15, first.decoderTimeMs)
        assertEquals(26, first.totalTimeMs)
        assertEquals(37, first.totalFrames)
        assertEquals(33, first.totalFramesReceived)
        assertEquals(33, first.totalFramesRendered)
        assertEquals(4, first.frameLossEvents)
        assertEquals(6, first.framesLost)
        assertEquals(8, first.decoderStarvationEvents)
        assertEquals(10, first.intentionalFrameDrops)
        assertEquals(12, first.watchdogFlushes)
        assertEquals(14, first.outputFormatChanges)
        assertEquals(4, first.minHostProcessingLatency.code)
        assertEquals(12, first.maxHostProcessingLatency.code)
        assertEquals(34, first.totalHostProcessingLatency)
        assertEquals(6, first.framesWithHostProcessingLatency)
        assertEquals(100, first.measurementStartTimestamp)

        val copy = VideoStats()
        copy.copy(first)
        assertEquals(first.decoderTimeMs, copy.decoderTimeMs)
        assertEquals(first.totalFramesRendered, copy.totalFramesRendered)
        assertEquals(first.decoderStarvationEvents, copy.decoderStarvationEvents)
        assertEquals(first.intentionalFrameDrops, copy.intentionalFrameDrops)
        assertEquals(first.watchdogFlushes, copy.watchdogFlushes)
        assertEquals(first.outputFormatChanges, copy.outputFormatChanges)
        assertEquals(first.minHostProcessingLatency, copy.minHostProcessingLatency)
        assertEquals(first.measurementStartTimestamp, copy.measurementStartTimestamp)

        copy.clear()
        assertEquals(0, copy.decoderTimeMs)
        assertEquals(0, copy.totalFramesRendered)
        assertEquals(0, copy.decoderStarvationEvents)
        assertEquals(0, copy.intentionalFrameDrops)
        assertEquals(0, copy.watchdogFlushes)
        assertEquals(0, copy.outputFormatChanges)
        assertEquals(0, copy.minHostProcessingLatency.code)
        assertEquals(0, copy.measurementStartTimestamp)
    }

    @Test
    fun addKeepsHostLatencyMinimumWhenNextWindowHasNoHostLatency() {
        val accumulated = VideoStats()
        accumulated.minHostProcessingLatency = 7.toChar()
        accumulated.maxHostProcessingLatency = 11.toChar()
        accumulated.totalHostProcessingLatency = 18
        accumulated.framesWithHostProcessingLatency = 2
        accumulated.measurementStartTimestamp = 100

        val emptyLatencyWindow = VideoStats()
        emptyLatencyWindow.totalFrames = 3
        emptyLatencyWindow.totalFramesReceived = 3
        emptyLatencyWindow.measurementStartTimestamp = 120

        accumulated.add(emptyLatencyWindow)

        assertEquals(7, accumulated.minHostProcessingLatency.code)
        assertEquals(11, accumulated.maxHostProcessingLatency.code)
        assertEquals(18, accumulated.totalHostProcessingLatency)
        assertEquals(2, accumulated.framesWithHostProcessingLatency)
    }
}
