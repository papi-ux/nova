package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legend explains the row under the cursor, so it has to be where the row is.
 *
 * Found while fixing nova#302 on a Retroid Pocket 6: with seven rows the legend sat a screen
 * below the Resolution row, and one tap on that row changed the game to 2880x1620 with none of
 * its alternatives ever shown.
 */
class NovaPlaySetupLegendTest {

    @Test
    fun theLegendDescribesTheDestinationCardUnderTheCursorAndGuessesNothing() {
        // Found on a Retroid Pocket 6: Play Setup opened with the cursor on the Desktop card and
        // the drawer below it empty, while four cards across had cut every sentence short.
        val places = listOf(
            NovaPlaySetupOption(label = "Desktop", consequence = "Use this computer's usual games and settings."),
            NovaPlaySetupOption(label = "Living room", consequence = "Not installed in Living room. Change Space to install it there."),
        )
        assertEquals(places[0], novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, "Desktop"))
        assertEquals(places[1], novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, "Living room"))
        // A row holds focus: that row's legend, never a place.
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.WHERE_IT_RUNS, places, "Living room"))
        // The place the cursor was on is gone from the reloaded list: nothing, rather than a neighbour.
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, "Bedroom"))
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, ""))
        // A Space added ahead of it does not hand the legend the neighbour that took its place,
        // which is the whole reason the cursor is remembered by name.
        val withNewSpace = listOf(
            NovaPlaySetupOption(label = "Bedroom", consequence = "Installed in Bedroom."),
        ) + places
        assertEquals(places[1], novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, withNewSpace, "Living room"))
    }

    @Test
    fun aLegendCardIsReadAsItsNameAndWhatItMeans() {
        val card = NovaPlaySetupOption(label = "Balanced", consequence = "1440x810 \u00b7 Preserve aspect ratio.")
        assertEquals("Balanced. 1440x810 \u00b7 Preserve aspect ratio.", novaPlaySetupOptionDescription(card))
        assertEquals("Balanced", novaPlaySetupOptionDescription(card.copy(consequence = "")))
        assertFalse(
            "the description was an escaped template, so a screen reader read the source text",
            read("NovaPlaySetup.kt").contains("${'$'}{'${'$'}'}")
        )
    }

    @Test
    fun theRowsScrollByThemselvesAndTheLegendIsDrawnOutsideThatScroll() {
        val setup = read("NovaPlaySetup.kt")
        val column = setup.section("private fun NovaPlaySetupActColumn(", "private fun ColumnScope.NovaPlaySetupRowsRegion(")
        val region = setup.section("private fun ColumnScope.NovaPlaySetupRowsRegion(", "private fun NovaPlaySetupPinnedLegend(")
        val legend = setup.section("private fun NovaPlaySetupPinnedLegend(", "internal fun NovaPlaySetupFact(")

        assertTrue(
            "pinned, the legend comes after the rows' own scroll region rather than inside it",
            column.contains("NovaPlaySetupRowsRegion { rows() }") &&
                column.indexOf("NovaPlaySetupRowsRegion { rows() }") in 0 until column.indexOf("NovaPlaySetupPinnedLegend(comparison, legendCap)")
        )
        assertTrue(
            "the region scrolls, fades at its own cut, and takes only what its rows need so a " +
                "short list keeps its legend directly under it",
            region.contains(".verticalScroll(scroll)") &&
                region.contains(".novaFadeAtCut(moreBelow, band = NOVA_PLAY_SETUP_ROWS_FADE, atTop = moreAbove)") &&
                region.contains("val moreBelow = scroll.maxValue - scroll.value > slack") &&
                region.contains(".weight(1f, fill = false)")
        )
        assertFalse("a legend inside a scroll is a legend that can be scrolled away", legend.contains("verticalScroll"))
        assertTrue(
            "the legend is measured before the rows, so it is held to its cap or a tall one takes the whole body. " +
                "It is held by choosing what fits, never by cutting: all of it, the current card, or nothing (R13)",
            legend.contains("maxHeight = cap,") &&
                legend.contains("{ comparison(NovaPlaySetupLegendForm.All) },\n            { comparison(NovaPlaySetupLegendForm.Current) },") &&
                !legend.contains("clipToBounds")
        )
        assertTrue(
            "stacked on a narrow screen the plan scrolls with the rows and the legend still stays",
            setup.contains("if (stacked && pinned) {") &&
                setup.section("if (stacked && pinned) {", "} else if (stacked) {").let {
                    it.indexOf("NovaPlaySetupRowsRegion(") in 0 until it.indexOf("NovaPlaySetupPinnedLegend(comparison, ")
                }
        )
    }

    @Test
    fun playSetupRowsShowTheirChoicesWhereverTheyChange() {
        // nova#302: one tap on Resolution changed the game with none of its alternatives shown. A
        // first tap that only moved focus fixed that at the cost of R12; now the row changes in
        // place, where Left, Right, its arrows and A all act at once (R1), and whatever changes it
        // brings the legend to it, so the alternatives are on screen however the value moved.
        val row = read("NovaPlaySetup.kt")
            .section("internal fun NovaPlaySetupSettingRow(", "internal fun NovaPlaySetupDestinations(")
        assertTrue(
            "an enumerated row is a value row, a cycler because the legend already sets out every option",
            row.contains("NovaValueRow(") && row.contains("style = NovaValueStyle.Cycler,") &&
                row.contains("ordered = state.ordered,")
        )
        assertTrue(
            "a change explains the row before it applies, so a tap that moves no focus still moves the legend",
            row.section("onChange = { index ->", "caption = ").let {
                it.indexOf("explain(row)") in 0 until it.indexOf("?.onSelect?.invoke()")
            } && row.contains("val followsFocus = Modifier.onFocusChanged { if (it.hasFocus) explain(row) }")
        )
        assertFalse(
            "no row waits for a second press any more (R12)",
            listOf("NovaGameDetailContent.kt", "NovaHostSetupRows.kt", "NovaGameDetailDestinations.kt", "NovaPlaySetup.kt")
                .any { read(it).contains("firstPressFocuses") }
        )
        assertTrue(
            "both scopes draw their rows the same way",
            read("NovaGameDetailContent.kt").contains("NovaPlaySetupSettingRow(") &&
                read("NovaHostSetupRows.kt").contains("NovaPlaySetupSettingRow(")
        )
    }

    @Test
    fun aTallLegendStillLeavesTheRowsARowAndAHalf() {
        // Every Game's Default Display: seven modes, three rows of cards, on the Retroid's 312dp body.
        val body = androidx.compose.ui.unit.Dp(312f)
        val cap = novaPlaySetupLegendCap(body, columnHead = true)
        assertTrue("the rows keep at least 80dp under the column head", (body - cap).value >= 96f)
        assertTrue("and a three-row legend of one-line cards still fits under that cap", cap.value >= 194f)
        assertEquals(androidx.compose.ui.unit.Dp.Unspecified, novaPlaySetupLegendCap(androidx.compose.ui.unit.Dp.Unspecified, columnHead = true))
        assertTrue(
            "a legend that stacks rows of cards says every sentence whole, and where the rows of cards do not " +
                "fit under the rows the body asks for the current mode's card alone",
            read("NovaHostSetupRows.kt").contains("form = form,") &&
                !read("NovaHostSetupRows.kt").contains("consequenceMaxLines")
        )
    }

    @Test
    fun theLegendKeepsItsHeightAsTheCursorMoves() {
        val setup = read("NovaPlaySetup.kt")
        val legend = setup.section("private fun NovaPlaySetupPinnedLegend(", "private class NovaTallestHeight")
        assertTrue(
            "the legend changes with every row, and one that grew and shrank would resize the rows " +
                "above it and leave the row under the cursor half out of view. Its cards say their whole " +
                "sentences now, so it keeps the tallest height it has had rather than a fixed count of lines",
            legend.contains("keepTallest = true,") &&
                legend.contains("tallest.px = maxOf(tallest.px, height)") &&
                legend.contains("height = tallest.px.coerceAtMost(limit)")
        )
        val card = setup.section("private fun NovaPlaySetupComparisonCard(", "internal fun novaPlaySetupOptionDescription(")
        assertFalse(
            "a card is never cut to a count of lines (R13)",
            card.contains("maxLines") || card.contains("minLines") || card.contains("TextOverflow")
        )
    }

    @Test
    fun aLegendLabelMayWrapAndItsCardsShareOneHeight() {
        val card = read("NovaPlaySetup.kt").section("internal fun NovaPlaySetupComparison(", "internal fun novaPlaySetupOptionDescription(")
        assertTrue(card.contains("Modifier.fillMaxWidth().height(IntrinsicSize.Min)"))
        assertTrue(card.contains("modifier = Modifier.weight(1f).fillMaxHeight(),"))
        val label = card.section("text = option.label,", "text = option.consequence,")
        assertFalse(
            "a name cut short names nothing, so a label wraps onto as many lines as it needs (R13)",
            label.contains("maxLines") || label.contains("TextOverflow")
        )
    }

    @Test
    fun thePanelsThatFillTheWindowStandOnTheLibrarysMargins() {
        // Play Setup left the window-filling panel for an edge panel (spec R6), whose frame keeps
        // clear of the bars and cutouts at its outer edge; Artwork is the one destination that
        // still fills the window, and the side lane that had no callers is gone.
        val panels = read("NovaGameDetailDestinations.kt")
        val full = panels.section("internal fun NovaGameDetailFullScreen(", "/** Every destination says how to act and how to get back. */")
        assertTrue(
            "papi, 2026-09-21: \"i feel like there a lot of open space on the sides\". safeContent also kept " +
                "clear of the gesture edges, about 30dp a side on a handheld that the library never gave up",
            full.contains(".windowInsetsPadding(WindowInsets.safeDrawing)") && !full.contains("WindowInsets.safeContent")
        )
        assertEquals(
            "the window-filling panel pads its own sides, so its header and hints must not pad again",
            1, Regex("selfInset = false,\\n").findAll(full).count()
        )
        assertEquals(1, Regex("NovaGameDetailDestinationHints\\(selfInset = false\\)").findAll(full).count())
        assertTrue(
            "a television keeps the wider margin: nothing reports its overscan",
            panels.contains("Configuration.UI_MODE_TYPE_TELEVISION") &&
                panels.contains("return if (television) NovaGameDetailInset else NOVA_DETAIL_WINDOW_INSET")
        )
        assertTrue(
            "Play Setup stands on the panel frame, attached to the end edge and clear of the screen's insets",
            read("NovaPlaySetupPages.kt").contains("edge = NovaEdge.End") &&
                read("panel/NovaPanelFrame.kt")
                    .contains(".windowInsetsPadding(WindowInsets.safeDrawing.only(outer + WindowInsetsSides.Vertical))")
        )
    }

    @Test
    fun theReadColumnIsWholeAndNothingInItMovesByItself() {
        val setup = read("NovaPlaySetup.kt")
        val column = setup.section("private fun NovaPlaySetupReadColumn(", "private fun NovaPlaySetupPlanRow(")
        assertFalse(
            "nothing in the read column takes the cursor, so its lines used to be cut to fit and then take turns " +
                "showing the rest every nine seconds: text cut at rest and an animation with no end inside a " +
                "panel (R13). Its lines and its facts' details now wrap whole",
            column.contains("maxLines") || column.contains("LaunchedEffect") || column.contains("delay(") ||
                setup.contains("NOVA_PLAY_SETUP_READ_REST_MS")
        )
        val fact = setup.section("internal fun NovaPlaySetupFact(", "internal fun NovaPlaySetupRule(")
        assertFalse("a fact's key, value and detail wrap whole", fact.contains("maxLines") || fact.contains("NovaRevealingText("))
        val body = setup.section("internal fun NovaPlaySetupBody(", "private fun NovaPlaySetupReadColumn(")
        assertTrue(
            "stacked, the plan shares one scroll with the rows the cursor walks, so it is one row that opens the " +
                "whole plan on its page; beside the rows it is whole when it fits and that row when it does not",
            body.contains("val stackedPlan: @Composable () -> Unit = if (onOpenPlan != null) {") &&
                body.contains("NovaFirstThatFits(\n                        maxHeight = fitHeight,")
        )
        val pages = read("NovaPlaySetupPages.kt")
        assertTrue(
            "the plan's page holds every part of the plan, each a stop, so the cursor scrolls to all of it",
            pages.contains("data class Plan(override val title: String, val plan: NovaPlaySetupPlan) : PlaySetupPage") &&
                pages.contains("NovaPlaySetupReadStop(Modifier.novaInitialFocus()) { NovaPlaySetupPlanStatement(page.plan) }") &&
                pages.contains("page.plan.facts.forEach { fact ->")
        )
        assertTrue(
            "both hosts of Play Setup's pages draw the plan's page",
            read("NovaGameDetailContent.kt").contains("is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)") &&
                read("NovaLibraryActivity.kt").contains("is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)")
        )
    }

    @Test
    fun theRowsScrollNeverStopsPartOfTheWayIntoThePlanAboveThem() {
        // The plan above the rows is 120px. A move that would stop 10px into it stops at the top
        // when the row fits there, and past the plan when it does not.
        assertEquals(0f, novaPlaySetupWholePlanStop(current = 0f, wanted = 10f, above = 120f, fitsAtTop = true))
        assertEquals(120f, novaPlaySetupWholePlanStop(current = 0f, wanted = 10f, above = 120f, fitsAtTop = false))
        // Back up from below the plan to a row near its foot: whole again, from the top.
        assertEquals(0f, novaPlaySetupWholePlanStop(current = 200f, wanted = -150f, above = 120f, fitsAtTop = true))
        // Anywhere else the page's own scrolling stands.
        assertEquals(260f, novaPlaySetupWholePlanStop(current = 200f, wanted = 60f, above = 120f, fitsAtTop = false))
        assertEquals(0f, novaPlaySetupWholePlanStop(current = 40f, wanted = -40f, above = 120f, fitsAtTop = true))
        assertEquals("with nothing above the rows it is the page's scrolling", 10f, novaPlaySetupWholePlanStop(0f, 10f, 0f, true))
    }

    @Test
    fun thePlansRowSaysItsFirstLineAndWhatHoldsItBack() {
        val plan = NovaPlaySetupPlan(
            mode = "Private Stream",
            lines = listOf("1920x1080 \u00b7 60 FPS \u00b7 HEVC", "Nothing outside this game changes."),
            facts = listOf(
                NovaPlaySetupFact(key = "Last session", value = "Smooth", tone = NovaPlaySetupTone.GOOD),
                NovaPlaySetupFact(key = "Limited by", value = "Network", detail = "12 ms jitter", tone = NovaPlaySetupTone.WARN),
            ),
        )
        assertEquals("1920x1080 \u00b7 60 FPS \u00b7 HEVC\nLimited by: Network", novaPlaySetupPlanSummary(plan))
        assertEquals("1920x1080 \u00b7 60 FPS \u00b7 HEVC", novaPlaySetupPlanSummary(plan.copy(facts = plan.facts.take(1))))
        assertNull(novaPlaySetupPlanSummary(plan.copy(lines = emptyList(), facts = emptyList())))
    }

    @Test
    fun aSpaceGamesStripAlwaysStartsOnARowItHas() {
        val activity = read("NovaGameDetailActivity.kt")
        // A Space game has no Where It Runs row, so starting the strip there left it empty on a
        // touch reopen, where no focus event arrives to move it.
        assertFalse(
            "every reset of the explained row goes through openingExplainedRow()",
            activity.contains("explainedRow = NovaPlaySetupRow.WHERE_IT_RUNS")
        )
        assertTrue(
            activity.contains("if (spaceGame != null) NovaPlaySetupRow.RESOLUTION else NovaPlaySetupRow.WHERE_IT_RUNS")
        )
        // The helper, then the three places the strip is reset: on open, on close, and on a scope change.
        assertEquals(4, activity.split("openingExplainedRow()").size - 1)
    }

    private fun read(name: String): String =
        String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/$name")), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
