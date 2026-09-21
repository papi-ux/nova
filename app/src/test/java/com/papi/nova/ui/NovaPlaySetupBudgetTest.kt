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
    fun aPinnedLegendIsNotChargedForRowsThatScroll() {
        // Seven rows is what a host with a display planner, an encoder choice and a Steam
        // launch gives the Retroid. Unpinned, the fourth took the legend's second line and the
        // fifth took the legend off the screen. Pinned, the rows past the third scroll.
        val three = novaPlaySetupPinnedLegendLines(retroidBody, rowCount = 3)
        assertTrue(three >= 2)
        for (rows in 3..8) {
            assertEquals("a row that scrolls must not cost the legend a line", three, novaPlaySetupPinnedLegendLines(retroidBody, rows))
        }
        assertEquals(1, novaPlaySetupConsequenceLines(retroidBody, rowCount = 7))
    }

    @Test
    fun aShortListIsBudgetedAsItIs() {
        for (rows in 0..3) {
            assertEquals(novaPlaySetupConsequenceLines(retroidBody, rows), novaPlaySetupPinnedLegendLines(retroidBody, rows))
        }
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


    private fun text(lines: Int, lineHeight: Int) = NovaPlaySetupMeasuredText(List(lines) { (it + 1) * lineHeight })

    private fun fact(valueLines: Int, detailLines: Int) = NovaPlaySetupMeasuredFact(
        value = text(valueLines, 19),
        detail = text(detailLines, 15),
    )

    private fun fitReadColumn(available: Int) = novaPlaySetupFitReadColumn(
        available = available,
        fixed = 16 + 35 + 22,
        lineGap = 6,
        // What it resolved, then the opening sentence over three lines.
        lines = listOf(text(1, 19), text(3, 19)),
        lineCap = 3,
        factChrome = 10,
        detailGap = 4,
        keyMin = 16,
        facts = listOf(
            fact(valueLines = 1, detailLines = 1),
            fact(valueLines = 1, detailLines = 3),
            // A host profile whose value wrapped: the last line the Retroid cut off.
            fact(valueLines = 2, detailLines = 0),
        ),
    )

    @Test
    fun theReadColumnGivesUpProseAndDetailRatherThanCuttingItsLastFact() {
        // 335 of height against 300: the shape of the Play Setup screen whose host profile ran
        // under the hint bar, where the d-pad could never scroll to it.
        val fit = fitReadColumn(available = 300)
        assertEquals(listOf(1, 1), fit.lineMaxLines)
        assertEquals(listOf(1, 2, Int.MAX_VALUE), fit.detailMaxLines)
    }

    @Test
    fun aReadColumnThatFitsKeepsEveryLine() {
        val fit = fitReadColumn(available = 1000)
        assertEquals(listOf(1, 3), fit.lineMaxLines)
        assertEquals(listOf(1, 3, Int.MAX_VALUE), fit.detailMaxLines)
    }

    @Test
    fun theReadColumnNeverTrimsBelowOneLineOrTouchesAValue() {
        val fit = fitReadColumn(available = 40)
        assertEquals(listOf(1, 1), fit.lineMaxLines)
        assertEquals(listOf(1, 1, Int.MAX_VALUE), fit.detailMaxLines)
    }

    @Test
    fun destinationsAboveTheRowsCostTheLegendRoom() {
        val withoutDestinations = novaPlaySetupConsequenceLines(retroidBody, rowCount = 2)
        val withDestinations = novaPlaySetupConsequenceLines(retroidBody, rowCount = 2, destinations = true)
        assertTrue(withDestinations <= withoutDestinations)
        assertTrue(withDestinations >= 1)
    }
}
