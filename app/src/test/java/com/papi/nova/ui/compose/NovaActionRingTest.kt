package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-game #14: the armed End Session's half, a destructive fill, drew a black focus ring (its label
 * colour) where Stay beside it, and every other control, drew the accent ring.
 */
class NovaActionRingTest {
    private val accent = Color(0xFF7C73FF)
    private val onAccent = Color(0xFFFFFFFF)
    private val onDestructive = Color(0xFF000000)

    @Test
    fun anArmedDestructiveHalfTakesTheAccentRingEveryControlHas() {
        assertEquals(accent, novaActionRing(fills = true, destructive = true, onFill = onDestructive, focusRing = accent))
        assertEquals("an unfilled control rings in the accent", accent, novaActionRing(fills = false, destructive = false, onFill = onAccent, focusRing = accent))
        assertEquals(
            "only the accent fill, where an accent ring would vanish, rings in its label colour",
            onAccent,
            novaActionRing(fills = true, destructive = false, onFill = onAccent, focusRing = accent),
        )
    }

    @Test
    fun theActionSurfaceTakesItsRingFromThatRule() {
        val source = File("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt").readText()
        val surface = source.substringAfter("fun NovaActionSurface(").substringBefore(".semantics {")
        assertTrue(surface.contains("ring = novaActionRing(fills = fills, destructive = destructive"))
    }
}
