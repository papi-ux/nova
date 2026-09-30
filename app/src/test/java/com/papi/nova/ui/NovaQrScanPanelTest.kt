package com.papi.nova.ui

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The QR scanner's bottom panel keeps its hairline on its inner, top edge only (spec 4.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaQrScanPanelTest {
    @Test
    fun theHairlineRunsPastTheScreenEdgesSoOnlyTheTopEdgeShows() {
        val background = novaBottomPanelBackground(fill = Color.DKGRAY, hairline = Color.WHITE, corner = 26f, hairlinePx = 2)
        background.bounds = Rect(0, 0, 400, 200)

        assertEquals(2, background.numberOfLayers)
        assertEquals("the surface fills the panel", Rect(0, 0, 400, 200), background.getDrawable(0).bounds)
        assertEquals(
            "the hairline layer overshoots left, right and bottom by its own width, and meets the top",
            Rect(-2, 0, 402, 202),
            background.getDrawable(1).bounds,
        )
        assertTrue(background.getDrawable(1) is GradientDrawable)
    }
}
