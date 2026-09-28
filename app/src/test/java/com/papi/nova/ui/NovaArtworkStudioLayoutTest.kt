package com.papi.nova.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.novaInPlaceImeOptions
import com.papi.nova.ui.compose.novaInPlaceInputType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Artwork Studio as a screen of its own. It was written as a card to sit among other rows,
 * and as the destination it still wore the card: a border, two layers of padding and a second
 * "Artwork Studio" header under the window's own. papi, 2026-09-21: "maximize the layout in the
 * artwork studio, like remove the border".
 */
class NovaArtworkStudioLayoutTest {

    @Test
    fun theDestinationDropsTheCardAndTheCardIsStillThereForARow() {
        val studio = read("NovaArtworkStudio.kt")
        assertTrue(
            "as the destination: no padding, no clip, no border, and no header to open it with",
            studio.contains("modifier = if (fillsDestination) {\n            Modifier.fillMaxWidth()\n        } else {") &&
                studio.contains("if (!fillsDestination) Row(") &&
                studio.contains("if (fillsDestination) expanded = true")
        )
        assertTrue(
            "one row among many, it is the card it was",
            studio.contains(".border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.hero))") &&
                studio.contains("fillsDestination: Boolean = false,")
        )
        assertTrue(
            "Cancel folded the card away, which in the destination left an empty window",
            studio.contains("if (!fillsDestination) expanded = false")
        )
        assertTrue(
            "a focus ring is drawn outside its control, so the full-width body keeps room for one",
            studio.contains("Modifier.padding(horizontal = NOVA_STUDIO_RING_ROOM)")
        )
        assertTrue(
            "the game page opens it as the destination and tells it the height it has",
            read("NovaGameDetailContent.kt").contains(
                "NovaArtworkStudio(\n                    initiallyExpanded = true,\n                    fillsDestination = true,\n                    fitHeight = bodyHeight,"
            )
        )
    }

    @Test
    fun theTwoPreviewsShareTheHeightTheScreenHas() {
        // The Retroid's body after the window opened to its margins: both previews fit, labels and all.
        val retroid = novaStudioCompositionHeight(364.dp)
        assertEquals(148.dp, retroid)
        assertTrue("two previews, two labels and the gap fit the body", (retroid * 2 + 40.dp) <= 364.dp)
        // A tablet or a television gets a preview worth the glass, up to the point a hero crop stops being one.
        assertEquals(240.dp, novaStudioCompositionHeight(720.dp))
        // A short landscape phone keeps a preview that still reads, and scrolls.
        assertEquals(132.dp, novaStudioCompositionHeight(250.dp))
        assertEquals(156.dp, novaStudioCompositionHeight(Dp.Unspecified))
        assertEquals(156.dp, novaStudioCompositionHeight(0.dp))
    }

    @Test
    fun typingHappensWhereTheFieldStands() {
        val asked = EditorInfo.IME_ACTION_SEARCH
        val options = novaInPlaceImeOptions(asked)
        assertEquals("what the field asked for is kept", asked, options and EditorInfo.IME_MASK_ACTION)
        assertTrue(options and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(options and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        assertTrue(
            "the studio's search field lost the results it narrows behind a full screen of keyboard; " +
                "it is NovaTextField now, which types in place",
            read("NovaArtworkStudio.kt").contains("NovaTextField(") &&
                read("panel/NovaTextField.kt").contains("NovaInPlaceKeyboard(signedNumber = kind == NovaFieldKind.SignedNumber) {")
        )
        assertEquals(
            "a number that may go below zero asks for the minus key, and nothing else changes",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            novaInPlaceInputType(InputType.TYPE_CLASS_NUMBER, signedNumber = true),
        )
        assertEquals(InputType.TYPE_CLASS_NUMBER, novaInPlaceInputType(InputType.TYPE_CLASS_NUMBER, signedNumber = false))
        assertEquals(
            "a text field is never turned into a number",
            InputType.TYPE_CLASS_TEXT,
            novaInPlaceInputType(InputType.TYPE_CLASS_TEXT, signedNumber = true),
        )
        val panels = read("NovaGameDetailDestinations.kt")
        assertTrue(
            "a keyboard in landscape leaves a third of the screen, and the controller hints are not for typing",
            panels.contains("if (!WindowInsets.isImeVisible) {\n                NovaGameDetailDestinationHints(selfInset = false)")
        )
        val addServer = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/preferences/AddComputerManually.kt")),
            StandardCharsets.UTF_8,
        )
        assertTrue(
            "Add Server's address field opened the same full screen of keyboard",
            addServer.contains("hostText.imeOptions = novaInPlaceImeOptions(EditorInfo.IME_ACTION_DONE)")
        )
    }

    @Test
    fun aTitleTooLongForItsLineIsStillReadable() {
        val studio = read("NovaArtworkStudio.kt")
        assertFalse(
            "the current match and the selected match take no cursor, so a title too long for its line " +
                "used to run past in a marquee: text moving by itself on screen, cut at rest. Both wrap " +
                "now, whole on as many lines as they need (R13)",
            studio.contains("basicMarquee")
        )
        val summary = studio.section("private fun NovaArtworkStudioMatchSummary(", "private fun NovaArtworkStudioComparison(")
        assertFalse(summary.contains("maxLines") || summary.contains("TextOverflow"))
        val picker = studio.section("private fun NovaArtworkChoicePicker(", "internal fun novaStudioChoiceColumns(")
        assertFalse(
            "the selected match's title wraps rather than being cut",
            picker.section("candidate.title,", "NovaActionButton(").contains("maxLines")
        )
        val identity = studio.section("private fun NovaArtworkIdentityPicker(", "private fun NovaArtworkChoicePicker(")
        assertTrue(
            "a candidate's whole title is on screen whether or not the cursor is on it, and so is its year and source",
            identity.contains("text = candidate.title,") && !identity.contains("NovaRevealingText(") &&
                !identity.contains("maxLines")
        )
    }

    @Test
    fun theAlternativesWrapIntoRowsRatherThanRunningOffTheColumn() {
        val studio = read("NovaArtworkStudio.kt")
        val picker = studio.section("private fun NovaArtworkChoicePicker(", "internal fun novaStudioChoiceColumns(")
        assertFalse(
            "a strip that scrolls sideways ends mid-item at the column's edge (R13)",
            studio.contains("horizontalScroll")
        )
        assertTrue(
            "the alternatives are rows of equal cells, and a short last row keeps the cells' width",
            picker.contains(".chunked(columns)") && picker.contains("modifier = Modifier.weight(1f),") &&
                picker.contains("repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }")
        )
        assertTrue(
            "which kind is on show changes in its own row with the one check, not four buttons that cut their labels",
            picker.contains("NovaValueRow(") && picker.contains("current = state.activeKind,") &&
                !picker.contains("selected = kind == state.activeKind")
        )
        assertEquals(
            "every row of buttons shares equal cells while each label fits, and wraps into fewer a row " +
                "rather than cutting a label: Apply Selected Artwork ended in an ellipsis at 130%",
            // Four rows, and the layout's own definition.
            5, Regex("NovaStudioButtonCells\\(").findAll(studio).count()
        )
        assertEquals(3, novaStudioCellsPerRow(count = 3, width = 600, gap = 8, widest = 180))
        assertEquals("a label too wide for a third takes half a row", 2, novaStudioCellsPerRow(count = 3, width = 600, gap = 8, widest = 220))
        assertEquals(2, novaStudioCellsPerRow(count = 4, width = 290, gap = 6, widest = 100))
        assertEquals(1, novaStudioCellsPerRow(count = 4, width = 290, gap = 6, widest = 200))
        assertEquals(1, novaStudioCellsPerRow(count = 1, width = 100, gap = 6, widest = 300))
        // Cells stay at least the strip's old 112dp, 8dp apart.
        assertEquals(1, novaStudioChoiceColumns(200.dp))
        assertEquals(2, novaStudioChoiceColumns(232.dp))
        assertEquals(2, novaStudioChoiceColumns(300.dp))
        assertEquals(3, novaStudioChoiceColumns(352.dp))
        assertEquals(1, novaStudioChoiceColumns(40.dp))
        assertEquals(1, novaStudioChoiceColumns(Dp.Infinity))
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
