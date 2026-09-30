package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-game #14 and review finding 4: focus takes the accent ring every control has. The armed End
 * Session's red half drew a black ring (its label colour) where Stay beside it drew the accent. An
 * accent fill that stands off the ring, as a half of an armed neutral split does under focus, takes
 * the accent ring too (NovaSplitConfirmToneComposeTest reads both); only a primary whose accent
 * fill runs flush to its edge rings in its label colour, where an accent ring would vanish on it.
 */
class NovaActionRingTest {
    private val accent = Color(0xFF7C73FF)
    private val onAccent = Color(0xFFFFFFFF)
    private val onDestructive = Color(0xFF000000)

    @Test
    fun focusTakesTheAccentRingButOnAFlushAccentFill() {
        assertEquals("a red fill", accent, novaActionRing(fills = true, destructive = true, standsOff = false, onFill = onDestructive, focusRing = accent))
        assertEquals("an accent fill stood off the ring", accent, novaActionRing(fills = true, destructive = false, standsOff = true, onFill = onAccent, focusRing = accent))
        assertEquals("an unfilled control", accent, novaActionRing(fills = false, destructive = false, standsOff = false, onFill = onAccent, focusRing = accent))
        assertEquals(
            "only a primary whose accent fill runs flush to its edge rings in its label colour",
            onAccent,
            novaActionRing(fills = true, destructive = false, standsOff = false, onFill = onAccent, focusRing = accent),
        )
    }
}
