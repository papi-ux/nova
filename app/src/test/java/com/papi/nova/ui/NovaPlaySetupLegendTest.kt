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
        val legend = setup.section("private fun NovaPlaySetupPinnedLegend(", "private fun NovaPlaySetupFact(")

        assertTrue(
            "pinned, the legend comes after the rows' own scroll region rather than inside it",
            column.contains("NovaPlaySetupRowsRegion { rows() }") &&
                column.indexOf("NovaPlaySetupRowsRegion { rows() }") in 0 until column.indexOf("NovaPlaySetupPinnedLegend(comparison, legendCap)")
        )
        assertTrue(
            "the region scrolls, fades at its own cut, and takes only what its rows need so a " +
                "short list keeps its legend directly under it",
            region.contains(".verticalScroll(scroll)") &&
                region.contains(".novaFadeAtCut(moreBelow, band = NOVA_PLAY_SETUP_ROWS_FADE)") &&
                region.contains("val moreBelow = scroll.maxValue - scroll.value > slack") &&
                region.contains(".weight(1f, fill = false)")
        )
        assertFalse("a legend inside a scroll is a legend that can be scrolled away", legend.contains("verticalScroll"))
        assertTrue(
            "the legend is measured before the rows, so it is capped or a tall one takes the whole body",
            legend.contains("Modifier.heightIn(max = cap).clipToBounds()")
        )
        assertTrue(
            "stacked on a narrow screen the plan scrolls with the rows and the legend still stays",
            setup.contains("if (stacked && pinned) {") &&
                setup.section("if (stacked && pinned) {", "} else if (stacked) {").let {
                    it.indexOf("NovaPlaySetupRowsRegion {") in 0 until it.indexOf("NovaPlaySetupPinnedLegend(comparison, ")
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
            "a legend that stacks rows of cards gives each one line",
            read("NovaHostSetupRows.kt").contains("consequenceMaxLines = if (explained.options.size > perRow) 1 else consequenceMaxLines,")
        )
    }

    @Test
    fun theLegendKeepsItsHeightAsTheCursorMoves() {
        val card = read("NovaPlaySetup.kt").section("private fun NovaPlaySetupComparisonCard(", "internal fun novaPlaySetupOptionDescription(")
        assertTrue(
            "the legend changes with every row, and one that grew and shrank would resize the rows " +
                "above it and leave the row under the cursor half out of view",
            card.contains("minLines = consequenceMaxLines,") && card.contains("maxLines = consequenceMaxLines,")
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
    fun theReadColumnsCutTextTakesTurnsAndAColumnThatFitsNeverStirs() {
        // Six texts: each hands on to the next.
        assertEquals(1, novaPlaySetupNextTurn(index = 0, items = 6, revealedThisRound = false))
        assertEquals(5, novaPlaySetupNextTurn(index = 4, items = 6, revealedThisRound = true))
        // After the last: another round only if this one had something hidden to show.
        assertEquals(NOVA_PLAY_SETUP_TURN_REST, novaPlaySetupNextTurn(index = 5, items = 6, revealedThisRound = true))
        assertEquals(NOVA_PLAY_SETUP_TURN_DONE, novaPlaySetupNextTurn(index = 5, items = 6, revealedThisRound = false))
        assertEquals(NOVA_PLAY_SETUP_TURN_DONE, novaPlaySetupNextTurn(index = 0, items = 1, revealedThisRound = false))

        val column = read("NovaPlaySetup.kt").section("private fun NovaPlaySetupReadColumn(", "private fun rememberNovaPlaySetupReadFit(")
        assertTrue(
            "nothing in the read column takes the cursor, so what the fit cut off could not be read at all; " +
                "its lines and its facts' details show the rest of themselves one at a time",
            column.contains("highlighted = turn == index,") && column.contains("revealing = turn == item,") &&
                column.contains("passes = 1,") && column.contains("novaPlaySetupNextTurn(index, items, revealedThisRound)")
        )
        assertTrue(
            "a change to the plan starts the turns again from the top",
            column.contains("var turn by remember(plan, fit) { mutableIntStateOf(-1) }")
        )
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
