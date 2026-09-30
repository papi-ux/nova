package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.roundToInt

class NovaLibraryLayoutV2Test {
    @Test
    fun freshOptionsStateDefaultsToPlainPosterArtwork() {
        assertFalse(NovaLibraryOptionsState().showPosterTitles)
    }

    @Test
    fun productionModesCycleGridCompactStageOnly() {
        assertEquals(
            listOf(
                NovaLibraryLayoutMode.GRID,
                NovaLibraryLayoutMode.COMPACT,
                NovaLibraryLayoutMode.STAGE,
            ),
            NovaLibraryLayoutMode.entries,
        )
        assertEquals(NovaLibraryLayoutMode.COMPACT, NovaLibraryLayoutMode.GRID.next())
        assertEquals(NovaLibraryLayoutMode.STAGE, NovaLibraryLayoutMode.COMPACT.next())
        assertEquals(NovaLibraryLayoutMode.GRID, NovaLibraryLayoutMode.STAGE.next())
        assertEquals(NovaLibraryLayoutMode.GRID, NovaLibraryOptionsState().layoutMode)
        assertFalse("poster titles should be opt-in", NovaLibraryOptionsState().showPosterTitles)
    }

    @Test
    fun rp6LandscapeLeadsWithCinematicPortraitStageAndCompactGridDensity() {
        val geometry = novaLibraryStageGeometry(833, 354, 1f)
        assertEquals(NovaPortraitPosterSize(224, 336), geometry.selected)
        assertEquals(NovaPortraitPosterSize(120, 180), geometry.neighbour)
        val grid = NovaLibraryUiStateMapper.layoutSpec(833, 390, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(833, 390, NovaLibraryLayoutMode.COMPACT)
        assertEquals(5, grid.gridColumns)
        assertEquals(6, compact.gridColumns)
    }

    @Test
    fun captionReserveFitsSeparatelyRoundedLinesArtworkAndPaddingAcrossDensities() {
        for (density in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2.625f, 3.5f, 4f)) {
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val lineHeightPx = 17f * fontScale * density
                val paddingPx = (6f * density).roundToInt()
                val captionHeight = novaLibraryStageCaptionHeightDp(lineHeightPx, paddingPx, density)
                val geometry = novaLibraryStageGeometry(833, 354, fontScale, captionHeight)
                val cardPx = ((geometry.neighbour.heightDp + captionHeight) * density).roundToInt()
                val artworkWidthPx = (geometry.neighbour.widthDp * density).roundToInt()
                val artworkHeightPx = (artworkWidthPx / (2f / 3f)).roundToInt()
                val availableTextPx = cardPx - artworkHeightPx - paddingPx
                assertTrue("density=$density scale=$fontScale reserves both rounded lines: $availableTextPx",
                    availableTextPx >= 2 * ceil(lineHeightPx).toInt())
            }
        }
    }

    @Test
    fun measuredFractionalDensityCaptionAndLargeTextBoundaryRemainReadable() {
        val density = 2.625f
        val caption = novaLibraryStageCaptionHeightDp(17f * 1.3f * density, (6f * density).roundToInt(), density)
        assertEquals(52, caption)
        // The native red had a 480px card, 347px art and 16px padding: only 117px.
        val geometry = novaLibraryStageGeometry(833, 354, 1.3f, caption)
        val cardPx = ((geometry.neighbour.heightDp + caption) * density).roundToInt()
        assertTrue(cardPx - 347 - 16 >= 118)

        val largeCaption = novaLibraryStageCaptionHeightDp(17f * 2f * density, (6f * density).roundToInt(), density)
        assertEquals(76, largeCaption)
        val large = novaLibraryStageGeometry(833, 354, 2f, largeCaption)
        assertEquals(NovaPortraitPosterSize(86, 129), large.neighbour)
        assertEquals(103, large.infoHeightDp)
        assertTrue(large.infoHeightDp >= 47f * 2f + 8)
        assertEquals(large.selected.heightDp, large.infoHeightDp + 16 + large.neighbour.heightDp + largeCaption)
        // Caption rounding must not alter the plain-art large-text layout.
        assertEquals(NovaPortraitPosterSize(88, 132), novaLibraryStageGeometry(833, 354, 2f).neighbour)
    }

    @Test
    fun pixelPortraitReflowsRatherThanScalingTheLandscapeStage() {
        val grid = NovaLibraryUiStateMapper.layoutSpec(430, 932, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(430, 932, NovaLibraryLayoutMode.COMPACT)
        assertEquals(NovaLibraryWindowClass.PHONE_PORTRAIT, grid.windowClass)
        assertEquals(3, grid.gridColumns)
        assertEquals(4, compact.gridColumns)
        assertTrue(compact.gameCardHeightDp < grid.gameCardHeightDp)
    }

    @Test
    fun tvGetsDeliberateDistanceReadableReflow() {
        val geometry = novaLibraryStageGeometry(1280, 606, 1f)
        assertEquals(NovaPortraitPosterSize(384, 576), geometry.selected)
        assertEquals(NovaPortraitPosterSize(160, 240), geometry.neighbour)
        val grid = NovaLibraryUiStateMapper.layoutSpec(1920, 1080, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(1920, 1080, NovaLibraryLayoutMode.COMPACT)
        assertEquals(7, grid.gridColumns)
        assertEquals(9, compact.gridColumns)
    }

    @Test
    fun activeSessionChromeIsIntegratedIntoStageInsteadOfStackedAboveIt() {
        for (mode in NovaLibraryLayoutMode.entries) {
            assertTrue(NovaLibraryUiStateMapper.showStandaloneHomeHero(mode, true))
        }
        assertFalse(NovaLibraryUiStateMapper.showStandaloneHomeHero(NovaLibraryLayoutMode.STAGE, false))
        assertFalse(NovaLibraryUiStateMapper.showStandaloneHomeHero(NovaLibraryLayoutMode.GRID, false))
        assertTrue(NovaLibraryUiStateMapper.showStandaloneHomeHero(NovaLibraryLayoutMode.COMPACT, false))
    }

    @Test
    fun rp6ProductionShellBudgetsFooterBeforeFittingTheFocusedPosterRail() {
        assertEquals(0, NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp())
        val g = novaLibraryStageGeometry(833, 354, 1f)
        assertTrue(g.selected.heightDp + g.positionHeightDp <= 354)
        assertEquals(g.selected.heightDp, g.infoHeightDp + 16 + g.neighbour.heightDp)
    }

    @Test
    fun largeTextStageReflowsAgainstProductionBudgetsAtRp6PixelAndTvSizes() {
        for ((width, height) in listOf(833 to 354, 915 to 298, 960 to 426, 1280 to 606)) {
            val normal = novaLibraryStageGeometry(width, height, 1f)
            for (scale in listOf(1.3f, 1.5f, 2f)) {
                val large = novaLibraryStageGeometry(width, height, scale)
                assertTrue(large.selected.heightDp + large.positionHeightDp <= height)
                assertTrue(large.neighbour.heightDp <= normal.neighbour.heightDp)
                assertTrue(large.infoHeightDp >= 47f * scale + 8)
                assertEquals(large.selected.widthDp * 3, large.selected.heightDp * 2)
                assertEquals(large.neighbour.widthDp * 3, large.neighbour.heightDp * 2)
            }
        }
    }

    @Test
    fun focusedStageCardWinsOverViewportCenterAfterProgrammaticScroll() {
        val ids = listOf("alpha", "bravo", "charlie")
        assertEquals(1, NovaLibraryUiStateMapper.stageSettledSelectionIndex(ids, "bravo", 2))
        assertEquals(2, NovaLibraryUiStateMapper.stageSettledSelectionIndex(ids, null, 2))
        assertEquals(2, NovaLibraryUiStateMapper.stageSettledSelectionIndex(ids, "missing", 2))
    }

    @Test
    fun transientFocusLossKeepsSelectionOwnerUntilAnotherCardFocuses() {
        val afterLoss = NovaLibraryUiStateMapper.stageFocusOwnerAfterChange(
            currentOwnerId = "bravo",
            gameId = "bravo",
            isFocused = false,
        )
        assertEquals("bravo", afterLoss)
        assertEquals(
            "charlie",
            NovaLibraryUiStateMapper.stageFocusOwnerAfterChange(
                currentOwnerId = afterLoss,
                gameId = "charlie",
                isFocused = true,
            ),
        )
        assertEquals(
            "charlie",
            NovaLibraryUiStateMapper.stageFocusOwnerAfterChange(
                currentOwnerId = "charlie",
                gameId = "bravo",
                isFocused = false,
            ),
        )
    }

    @Test
    fun stageCardScrimAppearsOnlyWhenItBacksVisibleText() {
        assertFalse(NovaLibraryUiStateMapper.stageCardNeedsTextScrim(false, true, false))
        assertTrue(NovaLibraryUiStateMapper.stageCardNeedsTextScrim(true, true, false))
        assertTrue(NovaLibraryUiStateMapper.stageCardNeedsTextScrim(false, false, true))
    }

    @Test
    fun cinematicLandscapeReservesPortraitPosterRailAndUsesPersistentFooter() {
        val shield = novaLibraryStageGeometry(960, 426, 1f)
        assertEquals(NovaPortraitPosterSize(272, 408), shield.selected)
        assertEquals(NovaPortraitPosterSize(144, 216), shield.neighbour)
        val phone = novaLibraryStageGeometry(915, 298, 1f)
        assertEquals(NovaPortraitPosterSize(186, 279), phone.selected)
        assertEquals(NovaPortraitPosterSize(90, 135), phone.neighbour)
        assertEquals(12, phone.posterGapDp)
    }


    @Test
    fun mapperOwnsOneExactPortraitAspectRatioContract() {
        assertEquals(2f / 3f, NovaLibraryUiStateMapper.posterAspectRatio(), 0f)
        val size = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(108)
        assertEquals(NovaPortraitPosterSize(widthDp = 108, heightDp = 162), size)
        assertEquals(size.widthDp * 3, size.heightDp * 2)
    }

    @Test
    fun portraitPosterWidthRoundsDownSafelyWithoutDistortingTwoByThreeRatio() {
        val expectedWidths = mapOf(
            2 to 2,
            3 to 2,
            4 to 4,
            5 to 4,
            111 to 110,
            112 to 112,
            113 to 112,
        )

        expectedWidths.forEach { (requestedWidthDp, expectedWidthDp) ->
            val size = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(requestedWidthDp)
            assertEquals(expectedWidthDp, size.widthDp)
            assertTrue(size.widthDp > 0)
            assertTrue(size.heightDp > 0)
            assertEquals(size.widthDp * 3, size.heightDp * 2)
            assertTrue(size.widthDp <= requestedWidthDp)
            assertTrue(requestedWidthDp - size.widthDp <= 1)
        }
        listOf(Int.MIN_VALUE, -8, 0, 1, Int.MAX_VALUE).forEach { invalidWidthDp ->
            assertThrows(IllegalArgumentException::class.java) {
                NovaLibraryUiStateMapper.portraitPosterSizeForWidth(invalidWidthDp)
            }
        }
    }

    @Test
    fun posterPresentationContractsAreSpecificToEachProductionLayout() {
        assertEquals(
            NovaPosterPresentationSpec(
                focusedScale = 1f,
                unfocusedAlpha = 1f,
                focusGutterDp = 0,
            ),
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE),
        )
        assertEquals(
            NovaPosterPresentationSpec(
                focusedScale = 1.08f,
                unfocusedAlpha = 0.84f,
                focusGutterDp = 8,
            ),
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.GRID),
        )
        assertEquals(
            NovaPosterPresentationSpec(
                focusedScale = 1.06f,
                unfocusedAlpha = 0.82f,
                focusGutterDp = 6,
            ),
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.COMPACT),
        )
    }

    @Test
    fun stageRailPosterUsesLargestExactTwoByThreeSizeWithFocusedScaleHeadroom() {
        val p = NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        assertEquals(1f, p.focusedScale, 0f)
        assertEquals(1f, p.unfocusedAlpha, 0f)
        assertEquals(0, p.focusGutterDp)
        for (height in listOf(134, 148, 182, 188, 304)) {
            val size = NovaLibraryUiStateMapper.portraitPosterSizeForRail(height, p)
            assertEquals((height / 3) * 3, size.heightDp)
            assertEquals(size.widthDp * 3, size.heightDp * 2)
        }
    }

    @Test
    fun tinyValidStageRailBudgetsStayNonzeroAndMonotonic() {
        val presentation = NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        val railHeights = listOf(24, 25, 27, 30)
        val sizes = railHeights.map { railHeightDp ->
            NovaLibraryUiStateMapper.portraitPosterSizeForRail(railHeightDp, presentation).also { size ->
                assertTrue(size.widthDp > 0)
                assertTrue(size.heightDp > 0)
                assertEquals(size.widthDp * 3, size.heightDp * 2)
                assertTrue(
                    size.heightDp * presentation.focusedScale + 2 * presentation.focusGutterDp <=
                        railHeightDp + 0.0001f,
                )
            }
        }

        sizes.zipWithNext().forEach { (smallerRailSize, largerRailSize) ->
            assertTrue(largerRailSize.widthDp >= smallerRailSize.widthDp)
            assertTrue(largerRailSize.heightDp >= smallerRailSize.heightDp)
        }
        assertTrue(sizes.last().heightDp > sizes.first().heightDp)
        listOf(Int.MIN_VALUE, -1, 0, 2).forEach { invalidRailHeightDp ->
            assertThrows(IllegalArgumentException::class.java) {
                NovaLibraryUiStateMapper.portraitPosterSizeForRail(
                    railHeightDp = invalidRailHeightDp,
                    presentationSpec = presentation,
                )
            }
        }
    }

    /**
     * The showcase used to spend 212dp of the RP6's 390 on chrome and leave the
     * grid 178dp, which is one 168dp poster row and a 9dp sliver of the next. Six
     * covers from a 24 game library, with the second row reading as clipped.
     */
    @Test
    fun rp6LandscapeGivesTheGridItsScreenBackAndFitsWholeRows() {
        val viewportDp = NovaLibraryUiStateMapper.landscapeGridViewportHeightDp(
            screenHeightDp = 390,
            safeVerticalInsetsDp = 0,
            largeText = false,
        )
        assertEquals(308, viewportDp)

        val chromeDp = 390 - viewportDp
        assertTrue(
            "one strip plus padding should cost well under a third of the screen, was $chromeDp",
            chromeDp <= 120,
        )

        // No panel frames the grid: its width is the strip's less side padding that lines the
        // artwork up with the bar, and its height is the whole viewport, the focus rise off the top.
        val stripWidthDp = 833 - NovaLibraryUiStateMapper.screenPaddingDp(isLandscape = true) * 2
        fun contentWidthDp(mode: NovaLibraryLayoutMode) =
            stripWidthDp - NovaLibraryUiStateMapper.gridSidePaddingDp(mode) * 2

        val compact = NovaLibraryUiStateMapper.gridViewportSpec(
            contentWidthDp = contentWidthDp(NovaLibraryLayoutMode.COMPACT),
            viewportHeightDp = viewportDp,
            layoutMode = NovaLibraryLayoutMode.COMPACT,
            windowClass = NovaLibraryWindowClass.HANDHELD_LANDSCAPE,
        )
        assertTrue(
            "compact calls itself materially denser, so it has to show more than one row",
            compact.fullRows >= 2,
        )
        assertTrue("compact should still look scrollable", compact.peekDp >= 16)
        assertEquals(compact.posterWidthDp * 3, compact.posterHeightDp * 2)
        assertTrue(
            "compact covers visible went from 6, was ${compact.columns * compact.fullRows}",
            compact.columns * compact.fullRows >= 14,
        )

        val grid = NovaLibraryUiStateMapper.gridViewportSpec(
            contentWidthDp = contentWidthDp(NovaLibraryLayoutMode.GRID),
            viewportHeightDp = viewportDp,
            layoutMode = NovaLibraryLayoutMode.GRID,
            windowClass = NovaLibraryWindowClass.HANDHELD_LANDSCAPE,
        )
        assertTrue("grid keeps the larger artwork", grid.posterWidthDp > compact.posterWidthDp)
        assertTrue("grid shows a real peek of the next row, not a sliver", grid.peekDp >= 48)
        assertEquals(grid.posterWidthDp * 3, grid.posterHeightDp * 2)
        assertEquals(
            "a focused first-row poster stays whole: the top inset is its focus rise",
            NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.COMPACT, compact.posterHeightDp),
            compact.topInsetDp,
        )
        assertEquals(
            NovaLibraryUiStateMapper.posterFocusRiseDp(NovaLibraryLayoutMode.GRID, grid.posterHeightDp),
            grid.topInsetDp,
        )
    }

    /**
     * The hint bar is drawn over the grid now, so the shell reserves nothing for
     * it. The guarantee that a poster row never ends up under the bar moved into
     * the grid's own bottom content padding, and that is what has to hold.
     */
    @Test
    fun overlaidHintBarStillClearsTheLastPosterRow() {
        assertEquals(0, NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true))
        assertTrue(
            "clearance must still cover the bar plus breathing room",
            NovaLibraryUiStateMapper.landscapeHintClearanceDp() >=
                NovaLibraryUiStateMapper.controllerHintBarMinHeightDp() + 8,
        )
    }

}
