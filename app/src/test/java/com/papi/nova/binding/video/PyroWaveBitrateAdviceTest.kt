package com.papi.nova.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What PyroWave asks for, and why the answer is shaped the way it is.
 *
 * The number itself is a judgement, measured by eye on real content: soft at 0.18 bits per pixel,
 * good at 0.73. What these pin is the shape of the arithmetic around it, because that shape is what
 * makes the advice right at settings nobody has tried yet.
 */
class PyroWaveBitrateAdviceTest {

    @Test
    fun frameRateCostsExactlyItsMultiple() {
        // The whole difference between this codec and an inter frame one. Doubling the frame rate of
        // H.264 costs far less than double, because the extra frames resemble their neighbours and
        // are coded as differences. Here every frame is coded from scratch, so it costs double, and
        // advice that assumed otherwise would under ask at high frame rates, which is exactly where
        // the picture was measured falling apart.
        val at60 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60)
        val at120 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 120)
        // Within a kbps, because the answer is truncated to a whole one at each frame rate and
        // half of 87091 is not 43545 exactly. The property is that it doubles, not that integer
        // division commutes.
        assertEquals((at60 * 2).toDouble(), at120.toDouble(), 1.0)
    }

    @Test
    fun pixelsCostExactlyTheirMultiple() {
        val at1080 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60)
        val at720 = PyroWaveDecoderRenderer.recommendedKbps(1280, 720, 60)
        // 1920x1080 is 2.25 times the pixels of 1280x720.
        assertEquals(at1080.toDouble(), at720.toDouble() * 2.25, at1080 * 0.01)
    }

    @Test
    fun theAdviceSitsBetweenWhatLookedSoftAndWhatLookedGood() {
        // Measured on Control at 1920x1080: 50 Mbps at 120 fps looked soft, 200 at 120 looked right.
        // Advice outside that bracket would be either useless or unaffordable.
        val at120 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 120)
        assertTrue("advice of $at120 kbps is below what looked soft", at120 > 50_000)
        assertTrue("advice of $at120 kbps is above what looked good", at120 < 200_000)
    }

    @Test
    fun novasDefaultIsWellUnderWhatThisCodecNeeds() {
        // 20 Mbps is Nova's default and the reason this advice exists at all: a player who picks the
        // codec and changes nothing else would judge it at a setting it cannot meet.
        assertTrue(PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60) > 20_000)
    }

    @Test
    fun nonsenseIsRefusedRatherThanExtrapolated() {
        assertEquals(0, PyroWaveDecoderRenderer.recommendedKbps(0, 1080, 60))
        assertEquals(0, PyroWaveDecoderRenderer.recommendedKbps(1920, 0, 60))
        assertEquals(0, PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 0))
        assertEquals(0, PyroWaveDecoderRenderer.recommendedKbps(-1920, -1080, -60))
    }
}
