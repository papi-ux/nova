package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the HUD sits, for the Command Center's HUD rows (in-game #4, review finding 2). The rows
 * compare it with the part of the stream the panel covers (NovaCommandCenterHudCaptionComposeTest);
 * a 4dp test of the corner alone called a HUD dragged along the top under the panel uncovered.
 */
class NovaCommandCenterHudCornerTest {
    @Test
    fun aStoredPositionIsWhereTheHudIs() {
        assertEquals("the RP6's stored corner at a 2.3 density", 27.675f, NovaCommandCenterHudCorner.leftPx(27.675f, 2.3f, television = false), 0f)
        assertEquals("dragged along the top", 400f, NovaCommandCenterHudCorner.leftPx(400f, 2.3f, television = false), 0f)
    }

    @Test
    fun aHudThatNeverStoredOneIsInItsCorner() {
        assertEquals("the 12dp margin", 12f * 2.3f, NovaCommandCenterHudCorner.leftPx(Float.NaN, 2.3f, television = false), 0.001f)
        assertEquals("a television's title-safe 48dp", 48f * 2f, NovaCommandCenterHudCorner.leftPx(Float.NaN, 2f, television = true), 0.001f)
    }

    /** Margin remains shared; the caller reads the actual HUD instead of a retired pixel preference. */
    @Test
    fun theCornerAndTheKeyAreTheHudsOwn() {
        val hud = File("src/main/java/com/papi/nova/ui/NovaStreamHud.kt").readText()
        val margin = Regex("""HUD_SAFE_MARGIN_DP = ([0-9.]+)f""").find(hud)?.groupValues?.get(1)?.toFloat()
        assertEquals(NovaCommandCenterHudCorner.MARGIN_DP, margin)
        assertTrue("and a television's is the title-safe margin", hud.contains("NovaPanelMetrics.TvSafeHorizontal.value"))
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue("the measured HUD, including its safe surface", game.contains("measuredX = novaHud?.leftPx ?: Float.NaN"))
    }
}
