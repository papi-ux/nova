package com.papi.nova.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.novaInPlaceImeOptions
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
            "the studio's search field lost the results it narrows behind a full screen of keyboard",
            read("NovaArtworkStudio.kt").contains("NovaInPlaceKeyboard {\n    OutlinedTextField(")
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
        assertEquals(
            "the current match and the selected match take no cursor, so they run past twice and settle",
            2, Regex("basicMarquee\\(iterations = 2\\)").findAll(studio).count()
        )
        assertTrue(
            "a candidate is under the cursor when its button is, and then shows its whole title",
            studio.contains(".onFocusChanged { underCursor = it.hasFocus }") &&
                studio.contains("highlighted = underCursor,")
        )
        assertFalse(studio.contains("candidate.title,\n                    color = colors.textPrimary,\n                    fontSize = 13.sp,\n                    fontWeight = FontWeight.Medium,\n                    maxLines = 2,\n                    overflow = TextOverflow.Ellipsis,"))
    }

    private fun read(name: String): String =
        String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/$name")), StandardCharsets.UTF_8)
}
