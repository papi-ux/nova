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
    fun aControllerPressActsAndAFirstTouchOnlyBringsTheLegend() {
        // A on the row under the cursor: it holds focus, its alternatives are on screen.
        assertTrue(novaPlaySetupPressActs(firstPressFocuses = true, heldFocus = true))
        // A finger on a row the legend is not showing: show them first.
        assertFalse(novaPlaySetupPressActs(firstPressFocuses = true, heldFocus = false))
        // Every other row in the app acts on its first press, whatever holds focus.
        assertTrue(novaPlaySetupPressActs(firstPressFocuses = false, heldFocus = false))
        assertTrue(novaPlaySetupPressActs(firstPressFocuses = false, heldFocus = true))
    }

    @Test
    fun theLegendDescribesTheDestinationCardUnderTheCursorAndGuessesNothing() {
        // Found on a Retroid Pocket 6: Play Setup opened with the cursor on the Desktop card and
        // the drawer below it empty, while four cards across had cut every sentence short.
        val places = listOf(
            NovaPlaySetupOption(label = "Desktop", consequence = "Use this computer's usual games and settings."),
            NovaPlaySetupOption(label = "Living room", consequence = "Not installed in Living room. Change Space to install it there."),
        )
        assertEquals(places[0], novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, 0))
        assertEquals(places[1], novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, 1))
        // A row holds focus: that row's legend, never a place.
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.WHERE_IT_RUNS, places, 1))
        // The places reloaded shorter than the card the cursor was on: nothing, rather than a neighbour.
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, 2))
        assertNull(novaPlaySetupPlaceUnderCursor(NovaPlaySetupRow.PLAY_IN, places, -1))
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
    fun playSetupRowsShowTheirChoicesBeforeTheyChangeThem() {
        // The press itself: the row also asks for focus when the panel opens, which is not this.
        val row = read("NovaGameDetailDestinations.kt")
            .section("internal fun NovaSteamChoiceRow(", ".focusable(enabled = actionable")
            .section("Modifier.clickable(", "// Explicit, like every other focusable")
        assertTrue(
            "whether the row held focus is read before the press moves focus onto it, or every " +
                "press would find the row focused and act",
            row.contains("val heldFocus = focused") &&
                row.indexOf("val heldFocus = focused") < row.indexOf("runCatching { focusRequester.requestFocus() }") &&
                row.contains("if (novaPlaySetupPressActs(firstPressFocuses, heldFocus)) onClick?.invoke()")
        )
        assertTrue(
            "both scopes of Play Setup advance a value on a press, so both ask for the first press to focus",
            read("NovaGameDetailContent.kt").contains("firstPressFocuses = true,") &&
                read("NovaHostSetupRows.kt").contains("firstPressFocuses = true,")
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
        assertTrue(
            "a name cut short names nothing; the second line is there for a title that needs it",
            card.section("text = option.label,", "text = option.consequence,").contains("maxLines = 2,")
        )
    }

    @Test
    fun thePanelsThatFillTheWindowStandOnTheLibrarysMargins() {
        val panels = read("NovaGameDetailDestinations.kt")
        val wide = panels.section("internal fun NovaGameDetailWidePanel(", "/** Every destination says how to act and how to get back. */")
        assertTrue(
            "papi, 2026-09-21: \"i feel like there a lot of open space on the sides\". safeContent also kept " +
                "clear of the gesture edges, about 30dp a side on a handheld that the library never gave up",
            wide.contains(".windowInsetsPadding(WindowInsets.safeDrawing)") && !wide.contains("WindowInsets.safeContent")
        )
        assertEquals(
            "both window-filling panels pad their own sides, so their header and hints must not pad again",
            2, Regex("selfInset = false,\\n").findAll(wide).count()
        )
        assertEquals(2, Regex("NovaGameDetailDestinationHints\\(selfInset = false\\)").findAll(wide).count())
        assertTrue(
            "a television keeps the wider margin: nothing reports its overscan",
            panels.contains("Configuration.UI_MODE_TYPE_TELEVISION") &&
                panels.contains("return if (television) NovaGameDetailInset else NOVA_DETAIL_WINDOW_INSET")
        )
        val lane = panels.section("BoxWithConstraints(", "internal fun Modifier.novaFadeAtCut(")
        assertTrue(
            "the lane still leaves each child to keep itself clear of a cutout",
            lane.contains("WindowInsets.safeContent.only(WindowInsetsSides.Vertical)") && !lane.contains("selfInset = false")
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
