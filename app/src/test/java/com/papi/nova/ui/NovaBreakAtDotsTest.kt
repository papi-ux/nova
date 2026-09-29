package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The game page's status line packs whole parts to a line, and no line ends in a dot (N22). */
class NovaBreakAtDotsTest {
    private val sep = "  ·  "

    @Test
    fun partsPackWholeAndALineNeverEndsInADot() {
        val parts = novaDottedParts("HOST VIRTUAL DISPLAY  ·  3840×2160 @ 120 FPS · 300 Mbps · PYROWAVE · SDR (HDR NOT REQUESTED)")
        assertEquals(listOf("HOST VIRTUAL DISPLAY", "3840×2160 @ 120 FPS", "300 Mbps", "PYROWAVE", "SDR (HDR NOT REQUESTED)"), parts)
        val packed = novaPackAtDots(parts, sep) { it.length <= 52 }
        assertEquals("HOST VIRTUAL DISPLAY  ·  3840×2160 @ 120 FPS\n300 Mbps  ·  PYROWAVE  ·  SDR (HDR NOT REQUESTED)", packed)
        packed.lines().forEach { line ->
            assertFalse("no line ends in a dot: $line", line.trimEnd().endsWith("·"))
            assertFalse("no line starts with one: $line", line.trimStart().startsWith("·"))
        }
    }

    @Test
    fun aPartTooLongForALineTakesOneOfItsOwnAndAShortLineStaysOne() {
        assertEquals("A\nLONG PART\nB", novaPackAtDots(listOf("A", "LONG PART", "B"), sep) { it.length <= 4 })
        assertEquals("A  ·  B", novaPackAtDots(listOf("A", "B"), sep) { true })
        assertEquals("", novaPackAtDots(emptyList(), sep) { true })
    }
}
