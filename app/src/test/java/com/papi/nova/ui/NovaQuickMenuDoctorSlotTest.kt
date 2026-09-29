package com.papi.nova.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Doctor card's slot is picked once per opening of the Command Center (review finding 1, N28). */
class NovaQuickMenuDoctorSlotTest {
    @Test
    fun theFirstReadingPinsTheSlotUntilThePanelCloses() {
        val slot = NovaQuickMenuDoctorSlot()
        assertFalse(slot.ranksLast(informational = false, reading = true))
        assertFalse("an observation after pressure keeps the card under the strip", slot.ranksLast(informational = true, reading = true))
        assertFalse(slot.ranksLast(informational = false, reading = true))

        val next = NovaQuickMenuDoctorSlot()
        assertTrue("the next opening picks again", next.ranksLast(informational = true, reading = true))
        assertTrue(next.ranksLast(informational = false, reading = true))
    }

    @Test
    fun beforeTheHostAnswersTheCardFollowsTheVerdictUnlessItHasFocus() {
        val slot = NovaQuickMenuDoctorSlot()
        assertTrue("the placeholder only informs", slot.ranksLast(informational = true, reading = false))
        assertFalse("the first reading places it", slot.ranksLast(informational = false, reading = true))
        assertFalse(slot.ranksLast(informational = true, reading = true))

        val held = NovaQuickMenuDoctorSlot()
        assertTrue(held.ranksLast(informational = true, reading = false))
        held.hold()
        assertTrue("a card that took focus stays where the player found it", held.ranksLast(informational = false, reading = true))
    }
}
