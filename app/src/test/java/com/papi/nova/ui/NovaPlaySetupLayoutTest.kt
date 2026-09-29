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
 * Play Setup as the approved mockup draws it (papi, 2026-09-28): the plan card pinned on top, the
 * rows under it one tile each, and every choice made where it is shown.
 *
 * It replaces the legend under the rows, which explained the row under the cursor from a screen
 * away. Found while fixing nova#302 on a Retroid Pocket 6: with seven rows the legend sat a screen
 * below the Resolution row, and one tap on that row changed the game to 2880x1620 with none of its
 * alternatives ever shown. The RP6 walk of 2026-09-28 then found it drawing four empty boxes for
 * Frame Rate. What the legend said now lives in the focused row's caption, in the notes of an
 * option page, and in the plan card's preview.
 */
class NovaPlaySetupLayoutTest {

    @Test
    fun noLegendIsLeftToExplainARowFromAScreenAway() {
        val setup = read("NovaPlaySetup.kt")
        assertFalse(
            "the legend, its cards and its place drawer are gone, and nothing draws an empty explainer " +
                "box for a choice with nothing to say",
            listOf("NovaPlaySetupComparison", "NovaPlaySetupPinnedLegend", "NovaPlaySetupPlaceLegend", "NovaFirstThatFits", "NovaPlaySetupDestinations")
                .any { setup.contains(it) || read("NovaGameDetailContent.kt").contains(it) || read("NovaLibraryPanels.kt").contains(it) }
        )
        assertFalse(
            "a row points nothing at itself any more: there is no explained row to keep in step",
            listOf("NovaGameDetailActivity.kt", "NovaGameDetailContent.kt", "NovaLibraryPanels.kt", "NovaHostSetupRows.kt")
                .any { read(it).contains("explainedRow") || read(it).contains("onExplain") }
        )
        assertFalse(
            "a screen reader was read the source text of an escaped template once; none may come back",
            setup.contains("${'$'}{'${'$'}'}")
        )
    }

    @Test
    fun thePlanCardIsPinnedAboveTheRowsAndTheRowsScrollWithTheirEdgesFaded() {
        val body = read("NovaPlaySetup.kt").section("internal fun NovaPlaySetupBody(", "internal fun NovaPlaySetupPlanCard(")
        assertTrue(
            "the card is drawn before the rows and outside their scroll, so no number of rows can push it away",
            body.indexOf("card()") in 0 until body.indexOf(".verticalScroll(scroll)")
        )
        assertTrue(
            "the rows scroll only when a large font makes them taller than the panel, fading the cut edge",
            body.contains(".novaScrollEdgeFade(scroll)\n                .verticalScroll(scroll)") &&
                body.contains(".weight(1f, fill = false)")
        )
    }

    @Test
    fun playSetupRowsShowTheirChoicesWhereverTheyChange() {
        // nova#302: one tap on Resolution changed the game with none of its alternatives shown. Now
        // Resolution opens a page listing every size with what it means (R2), and a row that changes
        // in place shows ‹ value › while it has focus, or every stop of a scale.
        val activity = read("NovaGameDetailActivity.kt")
        val resolution = activity.section("row = NovaPlaySetupRow.RESOLUTION,", "row = NovaPlaySetupRow.FRAME_RATE,")
        assertTrue(
            "Resolution and Video Codec open their pages",
            resolution.contains("opensPage = true,") &&
                read("NovaVideoCodecOverrides.kt").contains("opensPage = true,")
        )
        val row = read("NovaPlaySetup.kt").section("internal fun NovaPlaySetupSettingRow(", "private fun NovaPlaySetupStrip(")
        assertTrue(
            "a focused row that changes in place shows its chevrons, and a scale shows every stop",
            row.contains("changes && focused && state.ordered -> NovaPlaySetupStrip(") &&
                row.contains("NovaPlaySetupChevron(BackGlyph, colors.accent")
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
    fun theFocusedRowsCaptionIsInsideItsOwnTile() {
        val row = read("NovaPlaySetup.kt").section("internal fun NovaPlaySetupSettingRow(", "private fun NovaPlaySetupStrip(")
        assertTrue(
            "a focused row says what its value means on its own second line, so nothing below it moves when " +
                "focus does, as the legend's rows did when it grew and shrank",
            row.contains("if ((focused || !state.enabled) && (state.caption.isNotBlank() || setHere)) {") &&
                row.contains(".heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))")
        )
        assertFalse(
            "a caption is never cut to a count of lines (R13)",
            row.contains("maxLines") || row.contains("TextOverflow")
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
    fun thePlanIsWholeOnItsPageAndNothingInItMovesByItself() {
        val setup = read("NovaPlaySetup.kt")
        val fact = setup.section("internal fun NovaPlaySetupFact(", "internal fun NovaPlaySetupPlanStatement(")
        assertFalse("a fact's key, value and detail wrap whole", fact.contains("maxLines") || fact.contains("NovaRevealingText("))
        assertFalse(
            "nothing in Play Setup takes turns showing its lines on a timer (R13)",
            setup.contains("LaunchedEffect") || setup.contains("delay(") || setup.contains("NOVA_PLAY_SETUP_READ_REST_MS")
        )
        val pages = read("NovaPlaySetupPages.kt")
        assertTrue(
            "the plan's page holds every part of the plan, each a stop, so the cursor scrolls to all of it",
            pages.contains("data class Plan(override val title: String, val plan: NovaPlaySetupPlan) : PlaySetupPage") &&
                pages.contains("NovaPlaySetupReadStop(Modifier.novaInitialFocus()) { NovaPlaySetupPlanStatement(page.plan) }") &&
                pages.contains("page.plan.facts.forEach { fact ->")
        )
        assertTrue(
            "both hosts of Play Setup's pages draw the plan's page, and the plan card opens it",
            read("NovaGameDetailContent.kt").contains("is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)") &&
                read("NovaLibraryActivity.kt").contains("is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)") &&
                read("NovaGameDetailContent.kt").contains("playSetupPanel.push(PlaySetupPage.Plan(planTitle, shownPlan))")
        )
    }

    @Test
    fun thePlanCardSaysItsNumbersAndWhatHoldsItBackOnOneLine() {
        val plan = NovaPlaySetupPlan(
            mode = "Private Stream",
            lines = listOf("1920×1080 · 60 FPS · HEVC", "Nothing outside this game changes."),
            facts = listOf(
                NovaPlaySetupFact(key = "Last session", value = "Smooth", tone = NovaPlaySetupTone.GOOD),
                NovaPlaySetupFact(key = "Limited by", value = "Network", detail = "12 ms jitter", tone = NovaPlaySetupTone.WARN),
            ),
        )
        assertEquals("1920×1080 · 60 FPS · HEVC · Limited by: Network", novaPlaySetupPlanSummary(plan))
        assertEquals("1920×1080 · 60 FPS · HEVC", novaPlaySetupPlanSummary(plan.copy(facts = plan.facts.take(1))))
        assertNull(novaPlaySetupPlanSummary(plan.copy(lines = emptyList(), facts = emptyList())))
    }

    @Test
    fun thePreviewSwapsThePartTheChoiceChangesAndNothingElse() {
        val line = "1920×1080 @ 120 FPS · 200.0 Mbps · PyroWave (client choice) · SDR"
        assertEquals(
            "3840×2160 @ 120 FPS · 200.0 Mbps · PyroWave (client choice) · SDR",
            novaPlaySetupPreviewLine(line, NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, "3840×2160")),
        )
        assertEquals(
            "1920×1080 @ 120 FPS · 200.0 Mbps · HEVC · SDR",
            novaPlaySetupPreviewLine(line, NovaPlaySetupPreview(NovaPlaySetupPreviewPart.CODEC, "HEVC")),
        )
        assertEquals(
            "a line that names no size gains the new one at its front rather than keep an old one",
            "2880×1620 · Launch Auto",
            novaPlaySetupPreviewLine("Launch Auto", NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, "2880×1620")),
        )
        assertEquals("1280x720 · H264", novaPlaySetupPreviewLine("1280x720 · H264", NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, "")))
    }

    @Test
    fun theRootDrawsEveryRowOnceAndPagesHoldTheirBands() {
        fun rows(vararg kinds: NovaPlaySetupRow) = kinds.map { NovaPlaySetupRowState(it, it.name, "", "", emptyList()) }
        assertEquals(
            "where a game opens is the first band of the Where It Runs page, and the encoder a band of Video Codec",
            listOf(NovaPlaySetupRow.WHERE_IT_RUNS, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.VIDEO_CODEC),
            novaPlaySetupRootRows(
                rows(NovaPlaySetupRow.PLAY_IN, NovaPlaySetupRow.WHERE_IT_RUNS, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.VIDEO_CODEC, NovaPlaySetupRow.ENCODER),
            ).map { it.row },
        )
        assertEquals(
            "a Space game has no mode to choose, so its places are its Where It Runs row",
            listOf(NovaPlaySetupRow.PLAY_IN, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.FRAME_RATE),
            novaPlaySetupRootRows(rows(NovaPlaySetupRow.PLAY_IN, NovaPlaySetupRow.RESOLUTION, NovaPlaySetupRow.FRAME_RATE)).map { it.row },
        )
    }

    @Test
    fun aStepPassesOverWhatCannotBeChosenAndAScaleStopsAtItsEnds() {
        val options = listOf(
            NovaPlaySetupOption("Auto", "", onSelect = {}),
            NovaPlaySetupOption("30", "", enabled = false),
            NovaPlaySetupOption("60", "", onSelect = {}),
            NovaPlaySetupOption("Read only", ""),
        )
        assertEquals(2, novaPlaySetupStep(options, 0, 1, wrap = false))
        assertNull("an ordered row stops at its end", novaPlaySetupStep(options, 2, 1, wrap = false))
        assertEquals("an unordered one wraps", 0, novaPlaySetupStep(options, 2, 1, wrap = true))
        assertNull(novaPlaySetupStep(options, 0, -1, wrap = false))
    }

    @Test
    fun aPageOpensOnTheCurrentOptionOrTheFirstThatCanBeChosen() {
        val a = NovaPlaySetupOption("This Device", "", current = true, onSelect = {})
        val b = NovaPlaySetupOption("2x", "", onSelect = {})
        val c = NovaPlaySetupOption("3x", "", enabled = false)
        assertEquals("0:This Device", novaPlaySetupInitialOption(listOf(NovaPlaySetupBand(null, listOf(a, b, c)))))
        assertEquals("0:2x", novaPlaySetupInitialOption(listOf(NovaPlaySetupBand(null, listOf(c, b.copy(), a.copy(current = false))))))
        assertEquals(
            "a band's options are keyed by their band, so two bands may share a name",
            "1:Host default",
            novaPlaySetupInitialOption(
                listOf(
                    NovaPlaySetupBand(null, listOf(c)),
                    NovaPlaySetupBand("Encoder", listOf(NovaPlaySetupOption("Host default", "", current = true, onSelect = {}))),
                ),
            ),
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
