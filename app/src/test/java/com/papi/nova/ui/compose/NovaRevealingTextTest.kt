package com.papi.nova.ui.compose

import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A description that is cut has to be readable somewhere, or it is not worth printing. papi,
 * 2026-09-21: "if theres a description and its running off, theres no point if the user cant
 * view the detail". The highlighted tile shows all of its text, inside the room it already has.
 */
class NovaRevealingTextTest {

    @Test
    fun theRevealMovesALineAtATimeAndEndsExactlyAtTheEnd() {
        // Three hidden lines of 32px: one stop per line, the last one the end itself.
        assertEquals(listOf(32, 64, 96), novaRevealStops(distancePx = 96, linePx = 32f))
        // One hidden line is one move.
        assertEquals(listOf(32), novaRevealStops(distancePx = 32, linePx = 32f))
        assertEquals(listOf(20), novaRevealStops(distancePx = 20, linePx = 32f))
        // A remainder too small to be worth a move of its own rides with the step before it.
        assertEquals(listOf(32, 70), novaRevealStops(distancePx = 70, linePx = 32f))
        assertEquals(listOf(32, 64, 80), novaRevealStops(distancePx = 80, linePx = 32f))
        assertEquals(emptyList<Int>(), novaRevealStops(distancePx = 0, linePx = 32f))
        assertEquals(listOf(96), novaRevealStops(distancePx = 96, linePx = 0f))
    }

    @Test
    fun textThatFitsNeverMovesAndTheTileKeepsItsHeight() {
        val source = read("main/java/com/papi/nova/ui/compose/NovaRevealingText.kt")
        assertTrue(
            "only text the cut actually shortened is swapped for the scrolling one",
            source.contains("onTextLayout = { overflows = it.hasVisualOverflow }") &&
                source.contains("if (!highlighted || !overflows || maxLines == Int.MAX_VALUE) {")
        )
        assertTrue(
            "the scrolling text stands in exactly the lines the cut text had, so nothing around it moves",
            source.contains("val room = with(density) { novaRevealRoom(lineHeight.toDp(), maxLines) }") &&
                source.contains(".height(room)")
        )
        assertTrue(
            "it is driven, not dragged: a finger on the text still belongs to the list the tile is in",
            source.contains(".verticalScroll(scroll, enabled = false)")
        )
    }

    @Test
    fun theTilesThatCutTheirTextRevealItWhenHighlighted() {
        val row = read("main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
            .section("internal fun NovaSteamChoiceRow(", "if (value.isNotBlank()) {")
        assertTrue(
            "a row or a card under the cursor shows its whole caption, and a name too long for it runs past",
            row.contains("NovaRevealingText(") && row.contains("highlighted = focused,") &&
                row.contains("Modifier.basicMarquee(iterations = Int.MAX_VALUE)")
        )
        val setup = read("main/java/com/papi/nova/ui/NovaPlaySetup.kt")
        assertTrue(
            "the cursor never stops on a legend card, so the current choice is the one that plays, twice",
            setup.contains("highlighted = option.current,") && setup.contains("passes = 2,")
        )
        assertTrue(
            "a place the game cannot open in says why in a caption, so the cursor may stand on it to read it",
            setup.section("internal fun NovaPlaySetupDestinations(", "internal fun novaPlaySetupConsequenceLines(")
                .contains("focusableWhenDisabled = true,") &&
                read("main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
                    .contains(".focusable(enabled = actionable || focusableWhenDisabled)")
        )
    }

    @Test
    fun theRoomIsOneLineCountedNotOneBigSizeConverted() {
        assertEquals(32.dp, novaRevealRoom(16.dp, maxLines = 2))
        assertEquals(48.dp, novaRevealRoom(16.dp, maxLines = 3))
        val source = read("main/java/com/papi/nova/ui/compose/NovaRevealingText.kt")
        assertTrue(
            "above a font scale of one Android scales 32sp less than it scales 16sp, so the room came out " +
                "shorter than the two lines it replaced and the page moved under a reveal",
            source.contains("novaRevealRoom(lineHeight.toDp(), maxLines)") &&
                !source.contains("(lineHeight * maxLines).toDp()")
        )
    }

    private fun read(path: String): String =
        String(Files.readAllBytes(Path.of("src/$path")), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}
