package com.papi.nova.binding.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBufferTunerTest {
    @Test fun stablePlaybackKeepsLowestLatency() {
        val tuner = AudioBufferTuner(240, 2880)
        repeat(100) { assertEquals(480, tuner.sizeForUnderruns(0, 480)) }
    }

    @Test fun newUnderrunsGrowOnePacketWithoutRepeatedGrowthForTheSameCount() {
        val tuner = AudioBufferTuner(240, 2880)
        assertEquals(720, tuner.sizeForUnderruns(1, 480))
        assertEquals(720, tuner.sizeForUnderruns(1, 720))
        assertEquals(960, tuner.sizeForUnderruns(8, 720))
    }

    @Test fun growthCannotExceedLatencyCapOrShrinkAVendorMinimum() {
        val tuner = AudioBufferTuner(240, 2880)
        assertEquals(2880, tuner.sizeForUnderruns(1, 2800))
        assertEquals(2880, tuner.sizeForUnderruns(2, 2880))
        assertEquals(4096, tuner.sizeForUnderruns(3, 4096))
    }

    @Test fun counterResetAndUnavailableTelemetryDoNotCauseGrowth() {
        val tuner = AudioBufferTuner(240, 2880)
        assertEquals(720, tuner.sizeForUnderruns(10, 480))
        assertEquals(720, tuner.sizeForUnderruns(-1, 720))
        assertEquals(720, tuner.sizeForUnderruns(0, 720))
        assertEquals(960, tuner.sizeForUnderruns(1, 720))
        assertEquals(0, tuner.sizeForUnderruns(2, 0))
    }

    @Test fun failedResizeCanBeRetriedOnlyAfterAnotherUnderrun() {
        val tuner = AudioBufferTuner(240, 2880)
        assertEquals(720, tuner.sizeForUnderruns(1, 480))
        assertEquals(480, tuner.sizeForUnderruns(1, 480))
        assertEquals(720, tuner.sizeForUnderruns(2, 480))
    }
}
