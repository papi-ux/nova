package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The artwork tint rule, on 24 by 24 samples. The same table is in the Linux client's
 * deck_artwork_tint_test.cpp, and both follow the Polaris dashboard's artwork-tint.js, so one
 * cover gives one tint on every surface.
 *
 * UNRUN: written for Nova #385 and not executed in the authoring session.
 */
class NovaArtworkTintTest {
    private val blue = 0xFF0000C8.toInt()
    private val orange = 0xFFFF8000.toInt()
    private val grey = 0xFF808080.toInt()

    private fun sample(vararg runs: Pair<Int, Int>): IntArray {
        val pixels = runs.flatMap { (count, argb) -> List(count) { argb } }.toIntArray()
        assertEquals("a fixture is one 24 by 24 sample", NovaArtworkTint.SIZE * NovaArtworkTint.SIZE, pixels.size)
        return pixels
    }

    @Test
    fun aBlueCoverWithAnOrangeTitleTintsBlueNotAMuddyMean() {
        assertEquals(0xFF000096.toInt(), NovaArtworkTint.fromPixels(sample(520 to blue, 56 to orange)))
    }

    @Test
    fun greyTransparentAndBarelyColouredCoversHaveNoTint() {
        assertNull(NovaArtworkTint.fromPixels(sample(576 to grey)))
        assertNull(NovaArtworkTint.fromPixels(sample(576 to 0x000000C8)))
        // 23 of 576 is under one in twenty.
        assertNull(NovaArtworkTint.fromPixels(sample(553 to grey, 23 to blue)))
        // 35 of 576 is over it, and only the colourful pixels make the tint.
        assertEquals(0xFF000096.toInt(), NovaArtworkTint.fromPixels(sample(541 to grey, 35 to blue)))
    }

    @Test
    fun aPaleCoverAndADarkOneOfTheSameHueTintAlike() {
        val pale = NovaArtworkTint.fromPixels(sample(576 to 0xFF6464C8.toInt()))
        val dark = NovaArtworkTint.fromPixels(sample(576 to 0xFF323264.toInt()))
        assertEquals(0xFF4B4B96.toInt(), pale)
        assertEquals(pale, dark)
    }

    @Test
    fun redsEitherSideOfZeroHueAreOneGroup() {
        // Hues just below 6 and just above 0 sit in the last and first bins; their run wraps.
        assertEquals(
            0xFF960404.toInt(),
            NovaArtworkTint.fromPixels(sample(288 to 0xFFC8000A.toInt(), 288 to 0xFFC80A00.toInt())),
        )
    }
}
