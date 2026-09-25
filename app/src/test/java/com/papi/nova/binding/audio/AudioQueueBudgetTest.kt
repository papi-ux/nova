package com.papi.nova.binding.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQueueBudgetTest {
    @Test fun recoveryBacklogRefillsEmptyPlaybackWithoutDroppingSamples() {
        assertFalse(AudioQueueBudget.shouldSkip(65, 48000, 48000, 2880, 48000))
        assertFalse(AudioQueueBudget.shouldSkip(65, 48480, 48000, 2880, 48000))
    }

    @Test fun aFullOutputQueueRetainsTheExistingDecoderBacklogLimit() {
        assertTrue(AudioQueueBudget.shouldSkip(40, 50880, 48000, 2880, 48000))
        assertFalse(AudioQueueBudget.shouldSkip(35, 50880, 48000, 2880, 48000))
    }

    @Test fun excessiveRecoveryBacklogStillDropsAtTheCombinedBound() {
        assertTrue(AudioQueueBudget.shouldSkip(100, 48000, 48000, 2880, 48000))
        assertFalse(AudioQueueBudget.shouldSkip(95, 48000, 48000, 2880, 48000))
        assertTrue(AudioQueueBudget.shouldSkip(50, 48000, 48000, 480, 48000))
    }

    @Test fun playbackCursorWrapDoesNotInventAFullBuffer() {
        assertFalse(AudioQueueBudget.shouldSkip(65, 0x1_0000_0000L + 720, 240, 2880, 48000))
        assertTrue(AudioQueueBudget.shouldSkip(40, 0x1_0000_0000L + 3120, 240, 2880, 48000))
        assertFalse(AudioQueueBudget.shouldSkip(65, 0xffff_fff0L + 480, -16, 2880, 48000))
    }

    @Test fun invalidOrResetVendorCountersUseAConservativeBound() {
        assertTrue(AudioQueueBudget.shouldSkip(40, 1000, 0, 480, 48000))
        assertTrue(AudioQueueBudget.shouldSkip(40, 0, 0, 0, 48000))
        assertTrue(AudioQueueBudget.shouldSkip(40, 0, 0, 480, 0))
    }
}
