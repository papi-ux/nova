package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The game page's status line is in capitals, but a unit is a unit: "300 MBPS" read as a typo. */
class NovaInstrumentCaseTest {
    @Test
    fun capitalsKeepTheUnits() {
        assertEquals(
            "RESOLVED: 1920×1080 · 120 FPS · 300 Mbps · HEVC",
            novaInstrumentCase("Resolved: 1920×1080 · 120 FPS · 300 Mbps · HEVC"),
        )
        assertEquals("LIMITED TO 850 kbps", novaInstrumentCase("Limited to 850 kbps"))
        assertEquals("1.2 Gbps", novaInstrumentCase("1.2 Gbps"))
    }
}
