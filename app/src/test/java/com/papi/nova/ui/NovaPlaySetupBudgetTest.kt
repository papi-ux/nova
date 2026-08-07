package com.papi.nova.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line budgets, against the viewport the handheld actually reports.
 *
 * These were arithmetic in a comment once and the arithmetic was wrong -- the source said
 * a 374-393dp window and the device said 444dp, which is the difference between the legend
 * fitting and the legend being off the bottom of the screen. Measured on a Retroid Pocket 6
 * at 1920x1080, 369dpi: 444dp of app height, about 312dp of scrolling body once the panel's
 * insets, compact header and hint bar are out.
 */
class NovaPlaySetupBudgetTest {

    private val retroidBody = 312.dp

    @Test
    fun threeRowsAffordAFullConsequence() {
        // An upper bound, not a target: the sentence wraps to what it needs and stops.
        // Two is what the shipped copy actually uses at this width.
        assertTrue(novaPlaySetupConsequenceLines(retroidBody, rowCount = 3) >= 2)
    }

    @Test
    fun aFourthRowCostsTheLegendItsSecondLine() {
        // A host advertising a display planner is what adds Resolution, and its 53dp come
        // out of the legend rather than out of the panel fitting.
        assertEquals(1, novaPlaySetupConsequenceLines(retroidBody, rowCount = 4))
    }

    @Test
    fun theLegendNeverDisappears() {
        // However little is left, a card keeps one line. A legend with no text is a row of
        // labels whose whole purpose was to explain what the labels mean.
        for (rows in 0..8) {
            assertTrue(novaPlaySetupConsequenceLines(retroidBody, rows) >= 1)
        }
        assertEquals(1, novaPlaySetupConsequenceLines(40.dp, rowCount = 6))
    }

    @Test
    fun anUnmeasuredBodyFallsBackRatherThanCollapsing() {
        assertEquals(2, novaPlaySetupConsequenceLines(0.dp, rowCount = 3))
        assertEquals(2, novaPlaySetupIntroLines(0.dp, factCount = 4))
    }

    @Test
    fun thePlanGetsItsThirdLineWhenTheFactsAreFew() {
        assertTrue(novaPlaySetupIntroLines(retroidBody, factCount = 2) >= 3)
    }

    @Test
    fun aLongFactListTakesThePlansProseFirst() {
        val few = novaPlaySetupIntroLines(retroidBody, factCount = 2)
        val many = novaPlaySetupIntroLines(retroidBody, factCount = 6)
        assertTrue("more facts must not buy more prose", many <= few)
        assertTrue(many >= 1)
    }
}
