package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One plan, 3840x2160 at 120 FPS on PyroWave with a 300 Mbps bitrate, got two verdicts: the codec
 * preview warned "Limited by bitrate" while What Will Happen, with PyroWave saved, said nothing
 * (in-game smoke #10). There is one verdict now, and the preview, the Resolution page, What Will
 * Happen and the game page's status line all read it.
 */
class NovaPlaySetupBitrateVerdictTest {
    private val handheld = 1920L * 1080L
    private fun need(width: Int, height: Int) = if (width >= 3840) 469 else 120

    @Test
    fun pyroWavePastThisDevicesSizeAndOverTheBitrateIsHeldBack() {
        assertEquals(469, novaPyroWaveShortfallMbps(true, 3840 to 2160, handheld, 300_000, ::need))
    }

    @Test
    fun anythingElseIsNot() {
        assertEquals("not PyroWave", 0, novaPyroWaveShortfallMbps(false, 3840 to 2160, handheld, 300_000, ::need))
        assertEquals("this device's own size", 0, novaPyroWaveShortfallMbps(true, 1920 to 1080, handheld, 20_000, ::need))
        assertEquals("the bitrate covers it", 0, novaPyroWaveShortfallMbps(true, 3840 to 2160, handheld, 500_000, ::need))
        assertEquals("no size", 0, novaPyroWaveShortfallMbps(true, null, handheld, 300_000, ::need))
    }

    @Test
    fun thePreviewTheResolutionPageAndThePlanReadTheOneVerdict() {
        val activity = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val content = File("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt").readText()
        assertTrue("the codec preview", activity.contains("limit = if (bitrateShortfallMbps(format, askedSize, preferences) > 0) limitedBy else \"\""))
        assertTrue("the Resolution page", activity.contains("val need = bitrateShortfallMbps(codec, size, preferences)"))
        assertTrue("the plan", activity.contains("playSetupBitrateShortfallMbps = planShortfallMbps()"))
        assertTrue("What Will Happen's card", content.contains("limit = planLimit,"))
        assertTrue("the status line", content.contains("planLimit = bitrateLimit,"))
    }
}
