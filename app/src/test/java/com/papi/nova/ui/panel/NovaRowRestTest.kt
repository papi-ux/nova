package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One box style (papi, 2026-09-28: "some of the boxes are mismatched"). Every selectable row, in
 * every panel, rests as the same tile: the theme's tile fill under a 1dp hairline of its tile
 * border, cut to the row corner, as the approved Play Setup mockup draws them. Focus is the one
 * focus look on top of it. A card that is itself the tile draws its rows bare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaRowRestTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aRowRestsAsTheThemesTileUnderAHairline() {
        var rest: NovaRowRest? = null
        var surfaces: NovaLibrarySurfaces? = null
        rule.setPanelContent {
            rest = novaRowRest
            surfaces = LocalNovaLibrarySurfaces.current
        }
        rule.waitForIdle()
        assertEquals("the tile fill", surfaces!!.tile, rest!!.fill)
        assertEquals("the tile border", surfaces!!.tileBorder, rest!!.border)
        assertEquals("a hairline", NovaPanelMetrics.Hairline, rest!!.borderWidth)
        assertTrue("a tile that shows", rest!!.fill.alpha > 0f && rest!!.border.alpha > 0f)
    }

    @Test
    fun rowsInsideACardThatIsTheTileRestBare() {
        var rest: NovaRowRest? = null
        rule.setPanelContent { NovaNestedRows { rest = novaRowRest } }
        rule.waitForIdle()
        assertEquals("no tile inside a tile", 0f, rest!!.fill.alpha)
        assertEquals(0f, rest!!.border.alpha)
        assertEquals(0.dp, rest!!.borderWidth)
    }

    @Test
    fun everyFocusRingedSurfaceSaysHowItRests() {
        val offenders = sources().flatMap { file ->
            val text = file.readText()
            Regex("""\.novaFocusRing\(""").findAll(text)
                .mapNotNull { match -> topLevelArgs(text, match.range.last + 1) }
                .filter { args -> args.size < 2 }
                .map { args -> "${file.relativeTo(MAIN).path}: novaFocusRing(${args.joinToString()})" }
                .toList()
        }
        assertEquals(
            "a focus ring with nothing at rest draws a borderless row among tiles; a selectable row " +
                "passes rest = novaRowRest, and a button, field or pill its own rest",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theRowsOfEveryPanelRestAsTheTile() {
        val rows = mapOf(
            "ui/panel/NovaRows.kt" to "restFill = if (filled) colors.accent else rest.fill",
            "ui/panel/NovaValueRow.kt" to ".novaFocusRing(shape, rest = novaRowRest)",
            "ui/panel/NovaCommonPages.kt" to ".novaFocusRing(shape, rest = novaRowRest)",
            "ui/panel/NovaSplitConfirm.kt" to "restFill = if (row) novaRowRest.fill else Color.Unspecified",
            "ui/NovaGameDetailDestinations.kt" to "rest = novaRowRest,",
            "ui/NovaPlaySetupPages.kt" to ".novaFocusRing(shape, rest = novaRowRest)",
            "preferences/NovaDisplayRoleComposer.kt" to ".novaFocusRing(DisplayRoleRowShape, rest = novaRowRest)",
            "preferences/NovaSettingsScreen.kt" to ".novaFocusRing(shape, rest = novaRowRest)",
        )
        rows.forEach { (path, pin) ->
            assertTrue("$path rests its rows as the one tile", File(MAIN, path).readText().contains(pin))
        }
    }

    @Test
    fun theCommandCentersRowsAndCardsAreTheSameTile() {
        val commandCenter = File(MAIN, "ui/NovaQuickMenuContent.kt").readText()
        assertEquals(
            "the session strip and every row and card rest as the one tile",
            2,
            Regex("""\.novaFocusRing\(shape, rest = novaRowRest\)""").findAll(commandCenter).count(),
        )
        assertTrue(
            "a card that shows and does nothing is the tile, and its rows rest bare inside it",
            commandCenter.contains(".background(rest.fill, shape)") && commandCenter.contains("NovaNestedRows(content)"),
        )
        assertFalse(
            "no borderless row sits between the cards any more, and no box keeps an alpha of its own",
            commandCenter.contains("flat") || commandCenter.contains("NovaInGameOverlayAlpha.Nested"),
        )
    }

    /** The arguments of the call whose parenthesis opens before [from], split at the top level. */
    private fun topLevelArgs(text: String, from: Int): List<String>? {
        var depth = 1
        var start = from
        val args = mutableListOf<String>()
        for (index in from until text.length) {
            when (text[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (--depth == 0) {
                    text.substring(start, index).trim().takeIf { it.isNotEmpty() }?.let(args::add)
                    return args
                }
                ',' -> if (depth == 1) {
                    args += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        return null
    }

    private fun sources(): List<File> =
        MAIN.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // The modifier itself, and its overload that takes a rest.
            .filterNot { it.name == "NovaPanelTokens.kt" }
            .sortedBy { it.path }
            .toList()

    private companion object {
        val MAIN = File("src/main/java/com/papi/nova")
    }
}
