package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding 2 (in-game #4): the "HUD is under this panel" caption only asked whether a
 * position was stored, and the HUD stores one on every mode change, so on papi's RP6 the caption
 * never showed although the HUD sat in its corner under the panel.
 */
class NovaCommandCenterHudCornerTest {
    @Test
    fun aStoredCornerIsStillTheCorner() {
        assertTrue("the RP6's stored corner at a 2.3 density", NovaCommandCenterHudCorner.isAtItsCorner(27.675f, 27.675f, 2.3f, television = false))
        assertTrue("a position never stored is the corner", NovaCommandCenterHudCorner.isAtItsCorner(Float.NaN, Float.NaN, 2.3f, television = false))
        assertTrue("a pixel of rounding either way", NovaCommandCenterHudCorner.isAtItsCorner(26.6f, 28.6f, 2.3f, television = false))
    }

    @Test
    fun aDraggedHudIsNotInItsCorner() {
        assertFalse("dragged along the top", NovaCommandCenterHudCorner.isAtItsCorner(400f, 27.675f, 2.3f, television = false))
        assertFalse("dragged down the side", NovaCommandCenterHudCorner.isAtItsCorner(27.675f, 600f, 2.3f, television = false))
    }

    @Test
    fun aTelevisionsCornerIsItsTitleSafeMargin() {
        assertTrue(NovaCommandCenterHudCorner.isAtItsCorner(96f, 54f, 2f, television = true))
        assertFalse("the handheld margin is not a television's corner", NovaCommandCenterHudCorner.isAtItsCorner(24f, 24f, 2f, television = true))
    }

    /** The margin is the HUD's own, read from its file, which this pass does not edit. */
    @Test
    fun theCornerIsTheMarginTheHudKeeps() {
        val hud = File("src/main/java/com/papi/nova/ui/NovaStreamHud.kt").readText()
        val margin = Regex("""HUD_SAFE_MARGIN_DP = ([0-9.]+)f""").find(hud)?.groupValues?.get(1)?.toFloat()
        assertEquals(NovaCommandCenterHudCorner.MARGIN_DP, margin)
        assertTrue("and a television's is the title-safe margin", hud.contains("NovaPanelMetrics.TvSafeHorizontal.value"))
    }
}
