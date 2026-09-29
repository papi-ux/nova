package com.papi.nova.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-game #14 and review finding 4: both halves of an armed split take the accent ring every
 * control has, in either tone. The armed End Session's red half drew a black ring (its label
 * colour) where Stay beside it drew the accent, and the armed Live Tuning's accent half drew its
 * label colour the same way. A half filled at rest takes the accent ring with its fill stood off
 * it (NovaSplitConfirmToneComposeTest draws both); only a primary that fills under focus alone
 * rings in its label colour, where an accent ring would vanish on its own fill.
 */
class NovaActionRingTest {
    private val accent = Color(0xFF7C73FF)
    private val onAccent = Color(0xFFFFFFFF)
    private val onDestructive = Color(0xFF000000)

    @Test
    fun anArmedSplitsFilledHalfTakesTheAccentRingInEitherTone() {
        assertEquals("the red half", accent, novaActionRing(fills = true, filledAtRest = true, onFill = onDestructive, focusRing = accent))
        assertEquals("the accent half", accent, novaActionRing(fills = true, filledAtRest = true, onFill = onAccent, focusRing = accent))
        assertEquals("an unfilled control rings in the accent", accent, novaActionRing(fills = false, filledAtRest = false, onFill = onAccent, focusRing = accent))
        assertEquals(
            "only a primary that fills under focus alone rings in its label colour",
            onAccent,
            novaActionRing(fills = true, filledAtRest = false, onFill = onAccent, focusRing = accent),
        )
    }
}
