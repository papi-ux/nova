package com.papi.nova.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val stage = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = 833,
            heightDp = 390,
            largeText = false,
        )
        val grid = NovaLibraryUiStateMapper.layoutSpec(833, 390, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(833, 390, NovaLibraryLayoutMode.COMPACT)

        assertEquals(NovaLibraryWindowClass.HANDHELD_LANDSCAPE, stage.windowClass)
        assertFalse(stage.stageUsesVerticalGrid)
        assertEquals(5, grid.gridColumns)
        assertEquals(112, grid.gameCardHeightDp)
        assertEquals(6, compact.gridColumns)
        assertTrue(compact.gameCardHeightDp < grid.gameCardHeightDp)
        assertTrue(stage.stageHeroHeightDp >= 112)
        assertTrue(stage.stagePosterRailHeightDp >= 120)
        assertTrue(
            "stage chrome must fit the complete RP6 landscape viewport",
            stage.stageChromeBudgetDp <= 390,
        )
    }

    @Test
    fun pixelPortraitReflowsRatherThanScalingTheLandscapeStage() {
        val stage = NovaLibraryUiStateMapper.layoutSpec(
            widthDp = 430,
            heightDp = 932,
            layoutMode = NovaLibraryLayoutMode.STAGE,
        )
        val grid = NovaLibraryUiStateMapper.layoutSpec(430, 932, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(430, 932, NovaLibraryLayoutMode.COMPACT)

        assertEquals(NovaLibraryWindowClass.PHONE_PORTRAIT, stage.windowClass)
        assertTrue(stage.stageUsesVerticalGrid)
        assertEquals(2, stage.stagePosterColumns)
        assertEquals(3, grid.gridColumns)
        assertEquals(4, compact.gridColumns)
        assertTrue(grid.gameCardHeightDp > 112)
        assertTrue(compact.gameCardHeightDp < grid.gameCardHeightDp)
        assertTrue(stage.stageHeroHeightDp in 280..360)
        assertTrue(stage.stageChromeBudgetDp <= 932)
    }

    @Test
    fun tvGetsDeliberateDistanceReadableReflow() {
        val stage = NovaLibraryUiStateMapper.layoutSpec(
            widthDp = 1920,
            heightDp = 1080,
            layoutMode = NovaLibraryLayoutMode.STAGE,
        )
        val grid = NovaLibraryUiStateMapper.layoutSpec(1920, 1080, NovaLibraryLayoutMode.GRID)
        val compact = NovaLibraryUiStateMapper.layoutSpec(1920, 1080, NovaLibraryLayoutMode.COMPACT)

        assertEquals(NovaLibraryWindowClass.TV_LANDSCAPE, stage.windowClass)
        assertFalse(stage.stageUsesVerticalGrid)
        // Rail density is the cinematic poster proportion, not a column count: the rail
        // must be able to host a full-height poster at that proportion.
        val tvPosterWidth = NovaLibraryUiStateMapper.stageRailPosterWidthDp(1920)
        val tvPoster = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(tvPosterWidth)
        val tvPresentation =
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        assertTrue(
            "TV rail must host a full-height cinematic poster",
            tvPoster.heightDp + 2 * tvPresentation.focusGutterDp <= stage.stagePosterRailHeightDp,
        )
        assertEquals(7, grid.gridColumns)
        assertEquals(9, compact.gridColumns)
        assertTrue(grid.gameCardHeightDp >= 168)
        assertTrue(compact.gameCardHeightDp >= 132)
        assertTrue(stage.stageHeroHeightDp >= 480)
        assertTrue(stage.stageChromeBudgetDp <= 1080)
    }

    @Test
    fun activeSessionChromeIsIntegratedIntoStageInsteadOfStackedAboveIt() {
        assertFalse(
            NovaLibraryUiStateMapper.showStandaloneHomeHero(
                layoutMode = NovaLibraryLayoutMode.STAGE,
                hasActiveSession = true,
            )
        )
        assertFalse(
            NovaLibraryUiStateMapper.showStandaloneHomeHero(
                layoutMode = NovaLibraryLayoutMode.STAGE,
                hasActiveSession = false,
            )
        )
        assertTrue(
            NovaLibraryUiStateMapper.showStandaloneHomeHero(
                layoutMode = NovaLibraryLayoutMode.GRID,
                hasActiveSession = true,
            )
        )
        assertTrue(
            NovaLibraryUiStateMapper.showStandaloneHomeHero(
                layoutMode = NovaLibraryLayoutMode.COMPACT,
                hasActiveSession = false,
            )
        )
    }

    @Test
    fun rp6ProductionShellBudgetsFooterBeforeFittingTheFocusedPosterRail() {
        assertEquals(60, NovaLibraryUiStateMapper.landscapeToolbarHeightDp(false))
        assertEquals(74, NovaLibraryUiStateMapper.landscapeToolbarHeightDp(true))
        val normalViewport = NovaLibraryUiStateMapper.landscapeStageViewportHeightDp(390, 0, false)
        val largeViewport = NovaLibraryUiStateMapper.landscapeStageViewportHeightDp(390, 8, true)
        assertEquals(308, normalViewport)
        assertEquals(286, largeViewport)
        assertEquals(4, NovaLibraryUiStateMapper.stageRailVerticalContentPaddingDp())

        val productionStageHeight =
            largeViewport - NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()
        assertEquals(246, productionStageHeight)
        val stage = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = 817,
            heightDp = productionStageHeight,
            largeText = true,
        )
        assertTrue(stage.stageUsesCompactHero)
        assertEquals(150, stage.stagePosterRailHeightDp)
        assertEquals(96, stage.stageHeroHeightDp)
        assertEquals(productionStageHeight, stage.stageChromeBudgetDp)

        val presentation =
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        val posterSize = NovaLibraryUiStateMapper.portraitPosterSizeForRail(
            railHeightDp = stage.stagePosterRailHeightDp,
            presentationSpec = presentation,
        )
        assertEquals(NovaPortraitPosterSize(widthDp = 82, heightDp = 123), posterSize)
        assertEquals(posterSize.widthDp * 3, posterSize.heightDp * 2)
        assertTrue(
            posterSize.heightDp * presentation.focusedScale + 2 * presentation.focusGutterDp <=
                stage.stagePosterRailHeightDp + 0.0001f,
        )
    }

    @Test
    fun largeTextStageReflowsAgainstProductionBudgetsAtRp6PixelAndTvSizes() {
        val rp6StageHeight = NovaLibraryUiStateMapper.landscapeStageViewportHeightDp(
            screenHeightDp = 390,
            safeVerticalInsetsDp = 8,
            largeText = true,
        ) - NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()
        val rp6 = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = 817,
            heightDp = rp6StageHeight,
            largeText = true,
        )
        val pixel = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = 430,
            heightDp = 932,
            largeText = true,
        )
        val tvStageHeight = NovaLibraryUiStateMapper.landscapeStageViewportHeightDp(
            screenHeightDp = 1080,
            safeVerticalInsetsDp = 0,
            largeText = true,
        ) - NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()
        val tv = NovaLibraryUiStateMapper.stageLayoutSpecForViewport(
            widthDp = 1904,
            heightDp = tvStageHeight,
            largeText = true,
        )
        assertEquals(246, rp6StageHeight)
        assertEquals(rp6StageHeight, rp6.stageChromeBudgetDp)
        assertEquals(2, pixel.stagePosterColumns)
        assertTrue(pixel.stageChromeBudgetDp <= 932)
        assertEquals(944, tvStageHeight)
        assertEquals(320, tv.stagePosterRailHeightDp)
        assertEquals(624, tv.stageHeroHeightDp)
        assertEquals(tvStageHeight, tv.stageChromeBudgetDp)
        // A 1904dp stage now gives the hero real room, so it is not the compact variant.
        assertFalse(tv.stageUsesCompactHero)
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
        val spec = NovaLibraryUiStateMapper.layoutSpec(
            widthDp = 960,
            heightDp = 540,
            layoutMode = NovaLibraryLayoutMode.STAGE,
        )
        val handheldPosterWidth = NovaLibraryUiStateMapper.stageRailPosterWidthDp(960)
        assertTrue(
            "stage poster should hold the ~10% cinematic proportion, was $handheldPosterWidth",
            handheldPosterWidth in 96..104,
        )
        // The rail must be able to host a poster at the cinematic proportion.
        val railPoster = NovaLibraryUiStateMapper.portraitPosterSizeForWidth(
            NovaLibraryUiStateMapper.stageRailPosterWidthDp(960),
        )
        val railPresentation =
            NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        assertTrue(
            "rail must host a proportional poster, was ${'$'}{spec.stagePosterRailHeightDp}",
            railPoster.heightDp + 2 * railPresentation.focusGutterDp <=
                spec.stagePosterRailHeightDp,
        )
        assertEquals(
            NovaPortraitPosterSize(widthDp = 112, heightDp = 168),
            NovaLibraryUiStateMapper.portraitPosterSizeForWidth(112),
        )
        // The stage reserves less than the grid/compact shells: those lay poster rows out
        // beneath an overlaid hint bar, while the stage anchors one rail above a light
        // three-hint footer.
        assertTrue(NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp() in 36..44)
        assertTrue(
            NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp() <
                NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true),
        )
        assertEquals(
            540 - 16 - NovaLibraryUiStateMapper.screenPaddingDp(true) * 2 -
                NovaLibraryUiStateMapper.landscapeToolbarHeightDp() -
                NovaLibraryUiStateMapper.landscapeContentSpacingDp(),
            NovaLibraryUiStateMapper.landscapeStageViewportHeightDp(
                screenHeightDp = 540,
                safeVerticalInsetsDp = 16,
            ),
        )
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
                focusedScale = 1.10f,
                unfocusedAlpha = 0.76f,
                focusGutterDp = 6,
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
        val presentation = NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.STAGE)
        val expectedSizes = mapOf(
            134 to NovaPortraitPosterSize(widthDp = 72, heightDp = 108),
            148 to NovaPortraitPosterSize(widthDp = 82, heightDp = 123),
            182 to NovaPortraitPosterSize(widthDp = 102, heightDp = 153),
            188 to NovaPortraitPosterSize(widthDp = 106, heightDp = 159),
            304 to NovaPortraitPosterSize(widthDp = 176, heightDp = 264),
        )

        expectedSizes.forEach { (railHeightDp, expectedSize) ->
            val size = NovaLibraryUiStateMapper.portraitPosterSizeForRail(railHeightDp, presentation)
            val focusedFootprintDp =
                size.heightDp * presentation.focusedScale + 2 * presentation.focusGutterDp
            val nextFocusedFootprintDp =
                (size.heightDp + 3) * presentation.focusedScale + 2 * presentation.focusGutterDp

            assertEquals(expectedSize, size)
            assertEquals(size.widthDp * 3, size.heightDp * 2)
            assertTrue(
                "focused poster footprint $focusedFootprintDp must fit rail $railHeightDp",
                focusedFootprintDp <= railHeightDp + 0.0001f,
            )
            assertTrue(
                "next exact 2:3 unit must overflow rail $railHeightDp",
                nextFocusedFootprintDp > railHeightDp - 0.0001f,
            )
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
        listOf(Int.MIN_VALUE, -1, 0, 15).forEach { invalidRailHeightDp ->
            assertThrows(IllegalArgumentException::class.java) {
                NovaLibraryUiStateMapper.portraitPosterSizeForRail(
                    railHeightDp = invalidRailHeightDp,
                    presentationSpec = presentation,
                )
            }
        }
    }
}
