package com.papi.nova.ui

import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.novaKeyChipSize
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game page stands where the library stands, and nothing on it ends in an ellipsis nobody
 * can open.
 *
 * papi, 2026-09-21, after Play Setup and the Artwork Studio got the same: "we should give the
 * actual game menu the same treatment as well". On a Retroid Pocket 6 the title started 58dp in
 * from the glass while the grid behind it started at 17dp, and a Host Virtual launch ran its
 * status line past the edge of the screen.
 */
class NovaGameDetailOverviewLayoutTest {

    @Test
    fun anInstrumentLineBreaksOnlyAfterADot() {
        val nb = '\u00A0'
        assertEquals(
            "Steam$nb$nb\u00b7$nb Played${nb}15${nb}hr.${nb}ago$nb$nb\u00b7$nb Action",
            novaBreakAtDots("Steam  \u00b7  Played 15 hr. ago  \u00b7  Action"),
        )
        // The profile's own line is set with narrow dots, and those are breaks too.
        assertEquals(
            "1920\u00d71080$nb@${nb}60${nb}FPS$nb\u00b7 20.0${nb}Mbps",
            novaBreakAtDots("1920\u00d71080 @ 60 FPS \u00b7 20.0 Mbps"),
        )
        // Nothing to break at: every space holds.
        assertEquals("In${nb}papi$nb-${nb}lutris", novaBreakAtDots("In papi - lutris"))
        assertEquals("", novaBreakAtDots(""))

        // The property the screen depends on: wherever a line may break, a dot came just before.
        val line = novaBreakAtDots("Host Virtual Display  \u00b7  Resolved: 1920\u00d71080 @ 60 FPS \u00b7 SDR (HDR not requested)")
        line.forEachIndexed { index, char ->
            if (char == ' ') {
                assertEquals("a break at $index does not follow a dot: $line", '\u00b7', line.substring(0, index).trimEnd(nb).last())
            }
        }
        assertEquals(2, line.count { it == ' ' })
    }

    @Test
    fun aKeyChipGrowsWithItsLetterAndNeverShrinks() {
        assertEquals(20.dp, novaKeyChipSize(20.dp, fontScale = 1f))
        assertEquals(30.dp, novaKeyChipSize(20.dp, fontScale = 1.5f))
        assertEquals(42.dp, novaKeyChipSize(28.dp, fontScale = 1.5f))
        // The Retroid ships at 0.85: the circle the hint row lines up on stays its size.
        assertEquals(20.dp, novaKeyChipSize(20.dp, fontScale = 0.85f))
        assertTrue(
            "the page's footer and the library's hint row both cut their letters in half at a font scale of 1.5",
            read("NovaGameDetailOverview.kt").contains("val chip = novaKeyChipSize(20.dp)") &&
                read("NovaLibraryCinematicChrome.kt")
                    .contains(".size(novaKeyChipSize(if (hint.key.length <= 2) 20.dp else 28.dp))")
        )
    }

    @Test
    fun thePageStandsAtTheLibrarysMargin() {
        val overview = read("NovaGameDetailOverview.kt")
        val page = overview.section("internal fun NovaGameDetailOverview(", "private fun NovaGameDetailFooter(")
        assertTrue(
            "safeContent also kept clear of the back-swipe edges, about 30dp a side on a handheld, on top " +
                "of a 28dp inset: the page started 58dp in while the library behind it started at 17dp",
            page.contains(".windowInsetsPadding(WindowInsets.safeDrawing)") &&
                !overview.contains("WindowInsets.safeContent")
        )
        assertTrue(
            "one margin for the page and the panels that open over it, and a television keeps the wider one",
            page.contains("val inset = novaGameDetailWindowInset()") &&
                page.contains(".padding(start = inset, end = inset, bottom = NOVA_GAME_DETAIL_FLOOR_GAP)") &&
                page.contains(".padding(horizontal = inset, vertical = NOVA_GAME_DETAIL_FLOOR_GAP)") &&
                read("NovaGameDetailDestinations.kt").contains("internal fun novaGameDetailWindowInset(): Dp {")
        )
    }

    @Test
    fun theStatusLineTakesTheLinesItNeedsAndLaunchShowsTheRest() {
        val overview = read("NovaGameDetailOverview.kt")
        val status = overview.section("private fun NovaGameDetailStatusLine(", "private fun NovaGameDetailActions(")
        assertTrue(
            "it was one line ending in an ellipsis, and what it lost is the part that says what limited the launch",
            status.contains("NovaRevealingText(") &&
                status.contains("text = novaBreakAtDots(novaGameDetailStatusText(uiState, summary).uppercase()),") &&
                status.contains("maxLines = maxLines,") &&
                !status.contains("maxLines = 1,")
        )
        assertTrue(
            "the line says what Launch will do, so Launch under the cursor is what shows the rest of it",
            status.contains("highlighted = revealing,") &&
                // Launch has the cursor whenever the page is idle: a line that never rests is a page that never rests.
                status.contains("passes = 2,") &&
                overview.contains("revealing = primaryFocused,") &&
                overview.contains("onPrimaryFocus = { primaryFocused = it },") &&
                overview.contains(".onFocusChanged { onPrimaryFocus(it.isFocused) }")
        )
        assertTrue(
            "upright there is room under the hero for a third line, and a finger has no cursor to reveal with",
            overview.contains("maxLines = if (portrait) 3 else 2,")
        )
        assertTrue(
            "the lamp belongs to the first line; centred on two lines it pointed at neither",
            status.contains("verticalAlignment = Alignment.Top,") && status.contains(".padding(top = lampDrop.coerceAtLeast(0.dp))")
        )
    }

    @Test
    fun whatTheCursorStandsOnShowsItsWholeLabel() {
        val overview = read("NovaGameDetailOverview.kt")
        val action = overview.section("private fun NovaGameDetailAction(", "private fun LaunchProfileReviewNotice(")
        assertTrue(
            "two actions share a row upright, and \"Reset Game Profile\" did not fit its half",
            action.contains("overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,") &&
                action.contains("modifier = if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,")
        )
        val gauge = overview.section("private fun NovaGameDetailBeatGauge(", "private fun novaSameTitle(")
        assertTrue(
            "the name a fuzzy match found is the whole message of the correction line",
            gauge.contains("overflow = if (correctionFocused) TextOverflow.Clip else TextOverflow.Ellipsis,") &&
                gauge.contains(".then(if (correctionFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)")
        )
    }

    @Test
    fun theTitleStepsDownBeforeItIsCut() {
        val title = read("NovaGameDetailOverview.kt")
            .section("private fun NovaGameDetailTitle(", "private fun NovaGameDetailStatusLine(")
        assertTrue(
            "nothing points at the title to show the rest of it, so a long name shrinks to fit two lines",
            title.contains("autoSize = TextAutoSize.StepBased(") &&
                title.contains("minFontSize = NOVA_GAME_DETAIL_TITLE_MIN,") &&
                title.contains("maxFontSize = NOVA_GAME_DETAIL_TITLE_MAX,") &&
                title.contains("maxLines = 2,")
        )
        assertTrue(
            "the title took the body text's 24sp line under a 38sp face, so a name on two lines ran its " +
                "second line into its first; in em the line follows whichever size was chosen",
            title.contains("lineHeight = 1.08.em,")
        )
    }

    @Test
    fun theGaugeIsAsWideAsItsFiguresNeed() {
        val gauge = read("NovaGameDetailOverview.kt")
            .section("private fun NovaGameDetailBeatGauge(", "private fun novaSameTitle(")
        assertTrue(
            "fixed at 330dp, a large font scale pushed the 100% estimate off the row with nothing to say so",
            gauge.contains(".widthIn(min = NOVA_GAUGE_WIDTH)") &&
                gauge.contains("FlowRow(") &&
                !gauge.contains("Modifier.width(NOVA_GAUGE_WIDTH)")
        )
        assertTrue(
            "the bar follows the figures, and a long matched name does not get to stretch it",
            gauge.contains(".onSizeChanged { figuresWidth = with(density) { it.width.toDp() } }") &&
                gauge.split(".width(figuresWidth)").size == 3
        )
        assertFalse(
            "a column sized by its widest child would let the correction line set the bar's width",
            gauge.contains("IntrinsicSize")
        )
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
