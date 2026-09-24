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
    fun theAdviceIsWhatLookedGoodAndNotTheFloorBelowIt() {
        // It used to be 0.35 bits per pixel, which its own description called the point where a
        // picture stops being worth looking at. Advice is read as what to set, so a player who took it
        // saw the codec at its worst and blamed the codec.
        val at60 = PyroWaveDecoderRenderer.recommendedKbps(1920, 1080, 60)
        val bitsPerPixel = at60 * 1000.0 / (1920.0 * 1080.0 * 60.0)
        assertTrue(
            "advice of $bitsPerPixel bits per pixel is back near the soft end of what was measured",
            bitsPerPixel > 0.6,
        )
        assertTrue("advice of $bitsPerPixel bits per pixel is above what was ever measured", bitsPerPixel < 0.8)
    }

    @Test
    fun followingTheAdviceSatisfiesIt() {
        // The warning compares against the exact figure and prints a rounded one, so a player told
        // 91 Mbps who set 91 was under 90846 kbps and told again, on every launch, forever. The one
        // number is the one that is shown.
        for (fps in listOf(30, 60, 90, 120, 144)) {
            for (size in listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)) {
                val advised = PyroWaveDecoderRenderer.advisedMbps(size.first, size.second, fps)
                val exact = PyroWaveDecoderRenderer.recommendedKbps(size.first, size.second, fps)
                assertTrue(
                    "at ${size.first}x${size.second}${fps}: setting the advised $advised Mbps is still " +
                        "under the $exact kbps it wanted, so the advice can never be taken",
                    advised * 1000 >= exact,
                )
            }
        }
    }

    @Test
    fun nonsenseHasNoAdviceInMbpsEither() {
        assertEquals(0, PyroWaveDecoderRenderer.advisedMbps(0, 1080, 60))
        assertEquals(0, PyroWaveDecoderRenderer.advisedMbps(1920, 1080, 0))
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
