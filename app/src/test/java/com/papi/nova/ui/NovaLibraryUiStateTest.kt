package com.papi.nova.ui

import com.papi.nova.api.PolarisGame
import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibraryUiStateTest {
    @Test
    fun searchAndSourceFilterNarrowsGames() {
        val games = listOf(
            game("portal", "Portal 2", source = "steam"),
            game("hades", "Hades", source = "steam"),
            game("celeste", "Celeste", source = "heroic")
        )

        val filtered = NovaLibraryUiStateMapper.filterGames(
            games = games,
            search = "por",
            filterState = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.SOURCES,
                source = "steam"
            )
        )

        assertEquals(listOf("Portal 2"), filtered.map { it.name })
    }

    @Test
    fun modelFilteringKeepsSearchSourceAndRecentOrdering() {
        val games = listOf(
            game("game-steam-action", "Action Game", source = "steam", lastLaunched = 30),
            game("game-heroic-action", "Action Game Heroic", source = "heroic", lastLaunched = 50),
            game("game-steam-rpg", "RPG Game", source = "steam", lastLaunched = 40),
            game("tool-steam", "Desktop Tool", source = "steam", lastLaunched = 10)
        )

        val sourceModel = NovaLibraryUiStateMapper.build(
            games = games,
            search = "game",
            filterState = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.SOURCES,
                source = "steam"
            )
        )
        val recentModel = NovaLibraryUiStateMapper.build(
            games = games,
            search = "game",
            filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.RECENT)
        )

        assertEquals(
            listOf("game-steam-action", "game-steam-rpg"),
            sourceModel.filteredGames.map { it.id }
        )
        assertEquals(
            listOf("game-heroic-action", "game-steam-rpg", "game-steam-action"),
            recentModel.filteredGames.map { it.id }
        )
    }

    @Test
    fun recentFilterSortsNewestFirst() {
        val games = listOf(
            game("old", "Old Game", lastLaunched = 10),
            game("never", "Never Played"),
            game("new", "New Game", lastLaunched = 30)
        )

        val filtered = NovaLibraryUiStateMapper.filterGames(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.RECENT)
        )

        assertEquals(listOf("New Game", "Old Game"), filtered.map { it.name })
    }

    @Test
    fun defaultLibraryOptionsPreserveGridLayoutAndLibraryOrder() {
        val games = listOf(
            game("b", "Beta", source = "heroic", lastLaunched = 90, hdrSupported = true),
            game("a", "Alpha", source = "steam", lastLaunched = 10),
            game("c", "Charlie", source = "lutris")
        )

        val model = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState()
        )

        assertEquals(NovaLibrarySortMode.LIBRARY_ORDER, model.optionsState.sortMode)
        assertEquals(NovaLibraryLayoutMode.GRID, model.optionsState.layoutMode)
        assertEquals(listOf("Beta", "Alpha", "Charlie"), model.filteredGames.map { it.name })
    }

    @Test
    fun librarySortModesReorderCurrentResultsWithoutChangingFilters() {
        val games = listOf(
            game("portal", "Portal 2", source = "steam", lastLaunched = 10),
            game("wukong", "Black Myth: Wukong", source = "heroic", lastLaunched = 80, hdrSupported = true),
            game("hades", "Hades", source = "steam", lastLaunched = 40),
            game("desktop", "Desktop", source = "lutris")
        )

        fun sortedNames(sortMode: NovaLibrarySortMode): List<String> = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(),
            optionsState = NovaLibraryOptionsState(sortMode = sortMode)
        ).filteredGames.map { it.name }

        assertEquals(
            listOf("Black Myth: Wukong", "Hades", "Portal 2", "Desktop"),
            sortedNames(NovaLibrarySortMode.RECENT)
        )
        assertEquals(
            listOf("Black Myth: Wukong", "Desktop", "Hades", "Portal 2"),
            sortedNames(NovaLibrarySortMode.NAME_ASC)
        )
        assertEquals(
            listOf("Portal 2", "Hades", "Desktop", "Black Myth: Wukong"),
            sortedNames(NovaLibrarySortMode.NAME_DESC)
        )
        assertEquals(
            listOf("Portal 2", "Hades", "Desktop", "Black Myth: Wukong"),
            sortedNames(NovaLibrarySortMode.SOURCE)
        )
        assertEquals(
            listOf("Black Myth: Wukong", "Desktop", "Hades", "Portal 2"),
            sortedNames(NovaLibrarySortMode.HDR_FIRST)
        )
    }

    @Test
    fun moreFilterSupportsCategoryAndGenre() {
        val games = listOf(
            game("desktop", "Desktop", category = "desktop", genres = listOf("Utility")),
            game("rpg", "RPG", category = "cinematic", genres = listOf("RPG", "Adventure")),
            game("racer", "Racer", category = "fast_action", genres = listOf("Racing"))
        )

        val categoryFiltered = NovaLibraryUiStateMapper.filterGames(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.MORE,
                category = "desktop"
            )
        )
        val genreFiltered = NovaLibraryUiStateMapper.filterGames(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.MORE,
                genre = "rpg"
            )
        )

        assertEquals(listOf("Desktop"), categoryFiltered.map { it.name })
        assertEquals(listOf("RPG"), genreFiltered.map { it.name })
    }

    @Test
    fun uiModelBuildsCountsRecentRailHeroAndEmptyStates() {
        val games = listOf(
            game("portal", "Portal", lastLaunched = 20, hdrSupported = true),
            game("hades", "Hades", lastLaunched = 10),
            game("celeste", "Celeste")
        )

        val model = NovaLibraryUiStateMapper.build(
            games = games,
            search = "missing",
            filterState = NovaLibraryFilterState()
        )
        val recentModel = NovaLibraryUiStateMapper.build(
            games = games.map { it.copy(lastLaunched = 0) },
            search = "",
            filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.RECENT)
        )

        assertEquals(3, model.summary.totalCount)
        assertEquals(2, model.summary.recentCount)
        assertEquals(1, model.summary.hdrCount)
        assertEquals(listOf("Portal", "Hades"), model.recentGames.map { it.name })
        assertEquals("No matching games", model.hero.title)
        assertEquals(NovaLibraryHeroReason.EMPTY, model.hero.reason)
        assertEquals("No match for the current search or filters.", model.hero.caption)
        assertEquals("Clear filters", model.hero.actionLabel)
        assertEquals(NovaLibraryHeroPrimaryAction.CLEAR_FILTERS, model.hero.primaryAction)
        assertTrue(model.hero.badges.isEmpty())
        assertEquals(NovaLibraryEmptyState.FILTERED, model.emptyState)
        assertEquals(NovaLibraryEmptyState.RECENT, recentModel.emptyState)
    }

    @Test
    fun heroHomeStatePrioritizesOwnedActiveSessionWithResumeAndEndActions() {
        val games = listOf(
            game("recent", "Recent Game", lastLaunched = 100),
            game("active", "Active Game", lastLaunched = 10)
        )
        val activeSession = NovaLibraryActiveSessionUiState(
            gameId = 24,
            gameUuid = "active",
            gameName = "Active Game",
            ownerDeviceName = "Retroid Pocket",
            ownedByClient = true,
            viewerCount = 1,
            virtualDisplay = true,
            displayModeExplicit = true,
            streamWidth = 1920,
            streamHeight = 1080,
            streamFps = 60f
        )

        val hero = NovaLibraryUiStateMapper.heroState(
            games = games,
            filteredGames = games,
            activeSession = activeSession
        )

        assertEquals("Active Game", hero.title)
        assertEquals(NovaLibraryHeroReason.ACTIVE_SESSION, hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.RESUME, hero.primaryAction)
        assertEquals("Retroid Pocket", hero.subtitle)
        assertEquals("Resume stream", hero.actionLabel)
        assertEquals("End session", hero.secondaryActionLabel)
        assertEquals("Resume • Retroid Pocket • 1920×1080 60fps", hero.supportingLine)
        assertEquals("Active Game", hero.artworkFallbackTitle)
        assertEquals("Active session • Retroid Pocket", hero.artworkFallbackSubtitle)
        assertTrue(hero.badges.contains("Active session"))
        assertTrue(hero.badges.contains("Virtual display"))
        assertEquals("Resume this stream, or end it if the host game is stale.", hero.caption)
        assertTrue(hero.badges.contains("1920×1080 60fps"))
        assertFalse(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 600,
                heroReason = hero.reason,
                recentCount = 2
            )
        )
    }

    @Test
    fun heroPrefersFilteredGameOverGlobalRecentWhenConstraintsAreActive() {
        val games = listOf(
            game("recent", "Recent Game", source = "steam", lastLaunched = 100),
            game("match", "Filtered Match", source = "heroic", lastLaunched = 0)
        )

        val model = NovaLibraryUiStateMapper.build(
            games = games,
            search = "filtered",
            filterState = NovaLibraryFilterState()
        )

        assertEquals(listOf("match"), model.filteredGames.map { it.id })
        assertEquals("Filtered Match", model.hero.title)
        assertEquals("match", model.hero.game?.id)
        assertEquals(NovaLibraryHeroReason.FIRST_FILTERED, model.hero.reason)
        assertEquals("Filtered library", model.hero.eyebrow)
        assertEquals("Launch", model.hero.actionLabel)
        assertEquals("Filters active - clear to browse every game.", model.hero.caption)
    }

    @Test
    fun heroUsesRecentEmptyStateWhenRecentFilterHasNoHistory() {
        val games = listOf(
            game("portal", "Portal", source = "steam"),
            game("hades", "Hades", source = "heroic")
        )

        val model = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(primary = NovaLibraryPrimaryFilter.RECENT)
        )

        assertTrue(model.filteredGames.isEmpty())
        assertEquals(NovaLibraryEmptyState.RECENT, model.emptyState)
        assertEquals(NovaLibraryHeroReason.EMPTY, model.hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.CLEAR_FILTERS, model.hero.primaryAction)
        assertEquals("No recent games", model.hero.title)
        assertEquals("Continue when ready", model.hero.eyebrow)
        assertEquals("Your library has 2 games ready.", model.hero.subtitle)
        assertEquals("Launch any game once and it will appear in Continue.", model.hero.caption)
        assertEquals("View all games", model.hero.actionLabel)
        assertTrue(model.hero.badges.isEmpty())
    }

    @Test
    fun heroUsesSourceEmptyStateWhenSelectedSourceHasNoResults() {
        val games = listOf(
            game("hades", "Hades", source = "heroic"),
            game("celeste", "Celeste", source = "lutris")
        )

        val model = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState(
                primary = NovaLibraryPrimaryFilter.SOURCES,
                source = "steam"
            )
        )

        assertTrue(model.filteredGames.isEmpty())
        assertEquals(NovaLibraryEmptyState.SOURCE, model.emptyState)
        assertEquals(NovaLibraryHeroReason.EMPTY, model.hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.CLEAR_FILTERS, model.hero.primaryAction)
        assertEquals("No games from this source", model.hero.title)
        assertEquals("Source filter", model.hero.eyebrow)
        assertEquals("Your library has 2 games ready.", model.hero.subtitle)
        assertEquals("Clear the source filter or manage your Polaris library.", model.hero.caption)
        assertEquals("Clear source", model.hero.actionLabel)
        assertTrue(model.hero.badges.isEmpty())
    }

    @Test
    fun heroHomeStateUsesWatchActionForOtherClientSessionWithoutResumeCopy() {
        val activeSession = NovaLibraryActiveSessionUiState(
            gameId = 42,
            gameUuid = "desktop",
            gameName = "Desktop",
            ownerDeviceName = "Pixel",
            ownedByClient = false,
            viewerCount = 2,
            virtualDisplay = false,
            displayModeExplicit = false,
            streamWidth = 0,
            streamHeight = 0,
            streamFps = 0f
        )

        val hero = NovaLibraryUiStateMapper.heroState(
            games = emptyList(),
            filteredGames = emptyList(),
            activeSession = activeSession
        )

        assertEquals("Desktop", hero.title)
        assertEquals(NovaLibraryHeroReason.ACTIVE_SESSION, hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.WATCH, hero.primaryAction)
        assertEquals("Watch stream", hero.actionLabel)
        assertEquals("Watch-only view; owner stays in control.", hero.caption)
        assertEquals("Watch • Pixel • 2 viewers", hero.supportingLine)
        assertEquals("Active session • Pixel", hero.artworkFallbackSubtitle)
        assertFalse(hero.actionLabel.contains("Resume"))
        assertFalse(hero.supportingLine.contains("Resume"))
        assertTrue(hero.badges.contains("2 viewers"))
    }

    @Test
    fun heroHomeStatePromotesMostRecentGameWhenNoActiveSession() {
        val games = listOf(
            game("older", "Older Game", source = "heroic", lastLaunched = 20),
            game("recent", "Recent Game", source = "steam", lastLaunched = 100),
            game("never", "Never Played", source = "lutris")
        )

        val hero = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState()
        ).hero

        assertEquals("Recent Game", hero.title)
        assertEquals("recent", hero.game?.id)
        assertEquals(NovaLibraryHeroReason.LAST_PLAYED, hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.OPEN_DETAIL, hero.primaryAction)
        assertEquals("Continue playing", hero.eyebrow)
        assertEquals("Launch", hero.actionLabel)
        assertNull(hero.secondaryActionLabel)
        assertEquals("Continue • Steam", hero.supportingLine)
        assertEquals("Recent Game", hero.artworkFallbackTitle)
        assertEquals("Recent on this host", hero.artworkFallbackSubtitle)
        assertEquals("Recent on this host.", hero.caption)
    }

    @Test
    fun heroArtworkFallbackRemainsUsefulWhenCoverIsMissing() {
        val games = listOf(
            game(
                "missing-art",
                "Artless Wonder",
                source = "steam",
                lastLaunched = 7,
                coverUrl = ""
            )
        )

        val hero = NovaLibraryUiStateMapper.build(
            games = games,
            search = "",
            filterState = NovaLibraryFilterState()
        ).hero

        assertEquals("Artless Wonder", hero.title)
        assertEquals("", hero.game?.coverUrl)
        assertEquals("Artless Wonder", hero.artworkFallbackTitle)
        assertEquals("Recent on this host", hero.artworkFallbackSubtitle)
        assertTrue(hero.supportingLine.contains("Continue"))
    }

    @Test
    fun recoveryCopyProvidesOneClearCtaForEmptyLibraryStates() {
        val default = NovaLibraryUiStateMapper.emptyRecoveryState(
            NovaLibraryEmptyState.DEFAULT,
            totalCount = 0,
            sourceName = null
        )
        val recent = NovaLibraryUiStateMapper.emptyRecoveryState(
            NovaLibraryEmptyState.RECENT,
            totalCount = 19,
            sourceName = null
        )
        val source = NovaLibraryUiStateMapper.emptyRecoveryState(
            NovaLibraryEmptyState.SOURCE,
            totalCount = 19,
            sourceName = "steam"
        )
        val filtered = NovaLibraryUiStateMapper.emptyRecoveryState(
            NovaLibraryEmptyState.FILTERED,
            totalCount = 19,
            sourceName = null
        )

        assertEquals("No games yet", default.title)
        assertEquals("Manage library", default.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.MANAGE_LIBRARY, default.primaryAction)
        assertNull(default.secondaryActionLabel)

        assertEquals("No recent games", recent.title)
        assertEquals("View all games", recent.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.CLEAR_FILTERS, recent.primaryAction)
        assertNull(recent.secondaryActionLabel)

        assertEquals("No Steam games", source.title)
        assertEquals("Clear source", source.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.CLEAR_FILTERS, source.primaryAction)
        assertNull(source.secondaryActionLabel)

        assertEquals("No matches", filtered.title)
        assertEquals("Clear filters", filtered.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.CLEAR_FILTERS, filtered.primaryAction)
        assertNull(filtered.secondaryActionLabel)
    }

    @Test
    fun recoveryCopyDistinguishesOfflinePolarisUnavailableAndGenericLoadFailures() {
        val offline = NovaLibraryUiStateMapper.loadFailureRecoveryState("java.net.ConnectException: Failed to connect")
        val unavailable = NovaLibraryUiStateMapper.loadFailureRecoveryState("HTTP 404 polaris/v1/games")
        val generic = NovaLibraryUiStateMapper.loadFailureRecoveryState("Unexpected JSON")

        assertEquals("Host offline", offline.title)
        assertEquals("Retry", offline.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.RETRY, offline.primaryAction)
        assertEquals("java.net.ConnectException: Failed to connect", offline.detail)
        assertNull(offline.secondaryActionLabel)

        assertEquals("Polaris unavailable", unavailable.title)
        assertEquals("Manage server", unavailable.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.MANAGE_LIBRARY, unavailable.primaryAction)
        assertEquals("HTTP 404 polaris/v1/games", unavailable.detail)
        assertNull(unavailable.secondaryActionLabel)

        assertEquals("Couldn't load library", generic.title)
        assertEquals("Retry", generic.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.RETRY, generic.primaryAction)
        assertEquals("Unexpected JSON", generic.detail)
        assertNull(generic.secondaryActionLabel)
    }

    @Test
    fun recoveryCopyProvidesOneClearCtaForFailedLaunch() {
        val state = NovaLibraryUiStateMapper.launchFailureRecoveryState("Missing Polaris session details")

        assertEquals("Launch blocked", state.title)
        assertEquals("Manage server", state.primaryActionLabel)
        assertEquals(NovaLibraryRecoveryAction.MANAGE_LIBRARY, state.primaryAction)
        assertEquals("Missing Polaris session details", state.detail)
        assertNull(state.secondaryActionLabel)
    }

    @Test
    fun heroFallsBackToFirstFilteredGameThenEmptyLibraryAction() {
        val games = listOf(
            game("a", "Alpha", source = "steam"),
            game("b", "Beta", source = "heroic")
        )
        val filtered = listOf(games[1])

        val hero = NovaLibraryUiStateMapper.heroState(
            games = games,
            filteredGames = filtered,
            activeSession = null
        )
        val emptyHero = NovaLibraryUiStateMapper.heroState(
            games = emptyList(),
            filteredGames = emptyList(),
            activeSession = null
        )

        assertEquals("Beta", hero.title)
        assertEquals(NovaLibraryHeroReason.FIRST_FILTERED, hero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.OPEN_DETAIL, hero.primaryAction)
        assertEquals("Heroic", hero.subtitle)
        assertEquals("Ready when you are", hero.eyebrow)
        assertEquals("Launch", hero.actionLabel)
        assertEquals("Choose profile, display, and stream settings.", hero.caption)

        assertEquals(NovaLibraryHeroReason.EMPTY, emptyHero.reason)
        assertEquals(NovaLibraryHeroPrimaryAction.MANAGE_LIBRARY, emptyHero.primaryAction)
        assertEquals("Build your library", emptyHero.title)
        assertEquals("Manage library", emptyHero.actionLabel)
        assertEquals("Manage Library in Polaris to add games and launch metadata.", emptyHero.caption)
    }

    @Test
    fun gridColumnsMatchCurrentBreakpoints() {
        assertEquals(2, NovaLibraryUiStateMapper.gridColumns(widthDp = 430, isLandscape = false))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 540, isLandscape = false))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 600, isLandscape = false))
        assertEquals(4, NovaLibraryUiStateMapper.gridColumns(widthDp = 720, isLandscape = false))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumns(widthDp = 960, isLandscape = false))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 600, isLandscape = true))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 720, isLandscape = true))
        assertEquals(4, NovaLibraryUiStateMapper.gridColumns(widthDp = 960, isLandscape = true))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumns(widthDp = 1200, isLandscape = true))
    }

    @Test
    fun landscapeControlsMoveToDrawerSoGridUsesFullWidth() {
        assertFalse(NovaLibraryUiStateMapper.showLandscapeControlRail())
        assertEquals(813, NovaLibraryUiStateMapper.contentWidthDp(widthDp = 833, isLandscape = true))
        assertEquals(4, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 833, isLandscape = true))
        assertEquals(2, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 430, isLandscape = false))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 720, isLandscape = true))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 960, isLandscape = false))
    }

    @Test
    fun gridLayoutModeControlsDensityFromTheDrawer() {
        assertEquals(
            4,
            NovaLibraryUiStateMapper.gridColumnsForScreen(
                widthDp = 833,
                isLandscape = true,
                layoutMode = NovaLibraryLayoutMode.GRID
            )
        )
        assertEquals(
            5,
            NovaLibraryUiStateMapper.gridColumnsForScreen(
                widthDp = 833,
                isLandscape = true,
                layoutMode = NovaLibraryLayoutMode.COMPACT_GRID
            )
        )
        assertEquals(
            1,
            NovaLibraryUiStateMapper.gridColumnsForScreen(
                widthDp = 833,
                isLandscape = true,
                layoutMode = NovaLibraryLayoutMode.LIST
            )
        )
        assertEquals(156, NovaLibraryUiStateMapper.gameCardHeightDp(NovaLibraryLayoutMode.GRID, isLandscape = true))
        assertEquals(112, NovaLibraryUiStateMapper.gameCardHeightDp(NovaLibraryLayoutMode.COMPACT_GRID, isLandscape = true))
        assertEquals(88, NovaLibraryUiStateMapper.gameCardHeightDp(NovaLibraryLayoutMode.LIST, isLandscape = true))
    }

    @Test
    fun libraryLayoutModesCycleForTheYShortcut() {
        assertEquals(NovaLibraryLayoutMode.COMPACT_GRID, NovaLibraryLayoutMode.GRID.next())
        assertEquals(NovaLibraryLayoutMode.LIST, NovaLibraryLayoutMode.COMPACT_GRID.next())
        assertEquals(NovaLibraryLayoutMode.GRID, NovaLibraryLayoutMode.LIST.next())
        assertEquals(
            listOf(
                NovaLibraryLayoutMode.GRID,
                NovaLibraryLayoutMode.COMPACT_GRID,
                NovaLibraryLayoutMode.LIST
            ),
            NovaLibraryLayoutMode.entries
        )
    }

    @Test
    fun layoutMetricsMakeRetroidLandscapeGameSelectionPrimary() {
        assertEquals(112, NovaLibraryUiStateMapper.gameCardHeightDp(compact = true, isLandscape = false))
        assertEquals(76, NovaLibraryUiStateMapper.heroHeightDp(compact = true))
        assertTrue(
            "compact landscape resume hero should read as a short console home strip, not a second feature panel",
            NovaLibraryUiStateMapper.heroHeightDp(compact = true) <= 80
        )
        assertEquals(156, NovaLibraryUiStateMapper.gameCardHeightDp(compact = false, isLandscape = true))
        assertEquals(168, NovaLibraryUiStateMapper.gameCardHeightDp(compact = false, isLandscape = false))
    }

    @Test
    fun compactRetroidLandscapeChromeBudgetLeavesGameWallPrimaryAboveFooter() {
        val persistentChromeBudget =
            (NovaLibraryUiStateMapper.screenPaddingDp(isLandscape = true) * 2) +
                (NovaLibraryUiStateMapper.landscapeContentSpacingDp() * 2) +
                NovaLibraryUiStateMapper.heroHeightDp(compact = true) +
                NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true)

        assertTrue(
            "Retroid landscape should not waste game-wall height on outer padding",
            NovaLibraryUiStateMapper.screenPaddingDp(isLandscape = true) <= 8
        )
        assertTrue(
            "Retroid landscape should use tight toolbar/hero/grid gaps",
            NovaLibraryUiStateMapper.landscapeContentSpacingDp() <= 6
        )
        assertTrue(
            "Retroid landscape footer reserve should clear the controller hint bar without eating another game row",
            NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = true) <= 32
        )
        assertEquals(
            "portrait footer reserve should stay unchanged while the compact landscape shell is tightened",
            40,
            NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape = false)
        )
        assertTrue(
            "compact landscape persistent chrome should leave the game grid as the visual primary surface",
            persistentChromeBudget <= 140
        )
    }

    @Test
    fun landscapeRecentRailDoesNotDuplicateResumeHero() {
        assertFalse(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 500,
                heroReason = NovaLibraryHeroReason.FIRST_FILTERED,
                recentCount = 4
            )
        )
        assertFalse(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 600,
                heroReason = NovaLibraryHeroReason.LAST_PLAYED,
                recentCount = 4
            )
        )
        assertFalse(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 600,
                heroReason = NovaLibraryHeroReason.ACTIVE_SESSION,
                recentCount = 4
            )
        )
        assertFalse(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 600,
                heroReason = NovaLibraryHeroReason.FIRST_FILTERED,
                recentCount = 0
            )
        )
        assertTrue(
            NovaLibraryUiStateMapper.showLandscapeRecentRail(
                screenHeightDp = 600,
                heroReason = NovaLibraryHeroReason.FIRST_FILTERED,
                recentCount = 4
            )
        )
    }

    @Test
    fun landscapeRailScrollPaddingKeepsFocusedFiltersClearOfSafeArea() {
        assertTrue(NovaLibraryUiStateMapper.railScrollBottomPaddingDp() >= 96)
    }

    @Test
    fun landscapeRailSpacingKeepsBottomFiltersVisibleOnRetroid() {
        assertTrue(NovaLibraryUiStateMapper.railVerticalSpacingDp() <= 4)
    }

    @Test
    fun landscapeRailPrimaryFiltersUseCompactRowsOnRetroidWidth() {
        val retroidRailWidth = NovaLibraryUiStateMapper.railWidthDp(widthDp = 833)
        val filterCount = NovaLibraryPrimaryFilter.entries.size

        assertEquals(2, NovaLibraryUiStateMapper.railFilterColumns(retroidRailWidth))
        assertEquals(3, NovaLibraryUiStateMapper.railFilterRows(filterCount, retroidRailWidth))
        assertTrue(
            "Retroid landscape rail should show every primary filter in roughly three compact rows instead of clipping the bottom filter below the fold",
            NovaLibraryUiStateMapper.railFilterGridHeightDp(filterCount, retroidRailWidth) <= 124
        )
    }

    @Test
    fun landscapeRailActionsAndFiltersFitRetroidInitialViewport() {
        val retroidRailWidth = NovaLibraryUiStateMapper.railWidthDp(widthDp = 833)
        val actionCount = 4
        val filterCount = NovaLibraryPrimaryFilter.entries.size
        val actionAndFilterStackHeight = NovaLibraryUiStateMapper.railActionBlockHeightDp(actionCount, retroidRailWidth) +
            NovaLibraryUiStateMapper.railVerticalSpacingDp() +
            NovaLibraryUiStateMapper.railFilterGridHeightDp(filterCount, retroidRailWidth)

        assertEquals(3, NovaLibraryUiStateMapper.railActionColumns(retroidRailWidth))
        assertEquals(2, NovaLibraryUiStateMapper.railActionRows(actionCount, retroidRailWidth))
        assertTrue(
            "Retroid landscape rail should keep Refresh/Options/System/Switch plus All/Recent/Sources/HDR/More compact enough for the initial rail viewport",
            actionAndFilterStackHeight <= 206
        )
    }

    @Test
    fun filterChipWidthsKeepPortraitLabelsVisible() {
        val widths = NovaLibraryPrimaryFilter.entries.associateWith {
            NovaLibraryUiStateMapper.filterChipWidthDp(it)
        }

        assertTrue(widths.values.all { it >= 112 })
        assertTrue(widths.getValue(NovaLibraryPrimaryFilter.SOURCES) > widths.getValue(NovaLibraryPrimaryFilter.ALL))
        assertTrue(widths.getValue(NovaLibraryPrimaryFilter.RECENT) > widths.getValue(NovaLibraryPrimaryFilter.HDR))
    }

    @Test
    fun activeSessionUsesOwnedStreamingStatusForResume() {
        val session = NovaLibraryActiveSessionUiState.from(
            PolarisSessionStatus(
                state = "streaming",
                streamingActive = true,
                game = "Indiana Jones and the Great Circle",
                gameId = 777,
                gameUuid = "indy-uuid",
                ownerDeviceName = "Retroid Pocket",
                viewerCount = 1,
                ownedByClient = true
            )
        )

        requireNotNull(session)
        assertEquals(777, session.gameId)
        assertEquals("indy-uuid", session.gameUuid)
        assertEquals("Indiana Jones and the Great Circle", session.gameName)
        assertEquals("Retroid Pocket", session.ownerDeviceName)
        assertEquals(1, session.viewerCount)
        assertTrue(session.ownedByClient)
        assertFalse(session.watchOnly)
    }

    @Test
    fun activeSessionUsesPausedPolarisStatusForResume() {
        val session = NovaLibraryActiveSessionUiState.from(
            PolarisSessionStatus(
                state = "paused",
                streamingActive = false,
                game = "Indiana Jones and the Great Circle",
                gameId = 777,
                gameUuid = "indy-uuid",
                ownerDeviceName = "Retroid Pocket",
                ownedByClient = true
            )
        )

        requireNotNull(session)
        assertEquals(777, session.gameId)
        assertEquals("indy-uuid", session.gameUuid)
        assertEquals("Indiana Jones and the Great Circle", session.gameName)
        assertTrue(session.ownedByClient)
        assertFalse(session.watchOnly)
    }

    @Test
    fun activeSessionUsesWatchPolicyForOtherClientOwner() {
        val session = NovaLibraryActiveSessionUiState.from(
            PolarisSessionStatus(
                state = "streaming",
                game = "Desktop",
                gameId = 42,
                gameUuid = "desktop-uuid",
                ownerDeviceName = "Pixel",
                clientRole = "viewer",
                viewerCount = 2,
                ownedByClient = false,
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    explicitChoice = true,
                    virtualDisplay = true
                ),
                syncStatus = PolarisSessionStatus.SyncStatus(
                    applied = PolarisSessionStatus.SyncValues(
                        streamDisplayMode = "host_virtual_display",
                        displayMode = "1920x1080x30"
                    )
                )
            )
        )

        requireNotNull(session)
        assertFalse(session.ownedByClient)
        assertTrue(session.watchOnly)
        assertEquals(2, session.viewerCount)
        assertTrue(session.virtualDisplay)
        assertTrue(session.displayModeExplicit)
        assertEquals(1920, session.streamWidth)
        assertEquals(1080, session.streamHeight)
        assertEquals(30.0f, session.streamFps, 0.001f)
    }

    @Test
    fun activeSessionFallsBackWhenAppliedStreamProfileIsMissing() {
        val session = NovaLibraryActiveSessionUiState.from(
            PolarisSessionStatus(
                state = "streaming",
                streamingActive = true,
                game = "Portal",
                gameId = 24,
                gameUuid = "portal-uuid",
                ownedByClient = true,
                syncStatus = PolarisSessionStatus.SyncStatus(
                    effective = PolarisSessionStatus.SyncValues(displayMode = "1280x720x60")
                )
            )
        )

        requireNotNull(session)
        assertEquals(1280, session.streamWidth)
        assertEquals(720, session.streamHeight)
        assertEquals(60.0f, session.streamFps, 0.001f)
    }

    @Test
    fun activeSessionIgnoresShutdownIdleAndMissingGame() {
        assertNull(
            NovaLibraryActiveSessionUiState.from(
                PolarisSessionStatus(
                    state = "tearing_down",
                    shutdownRequested = true,
                    game = "Indy",
                    gameId = 777
                )
            )
        )
        assertNull(
            NovaLibraryActiveSessionUiState.from(
                PolarisSessionStatus(state = "streaming", game = "Indy", gameId = 0)
            )
        )
        assertNull(
            NovaLibraryActiveSessionUiState.from(
                PolarisSessionStatus(state = "idle", game = "Indy", gameId = 777)
            )
        )
    }

    private fun game(
        id: String,
        name: String,
        source: String = "steam",
        category: String = "",
        genres: List<String> = emptyList(),
        lastLaunched: Long = 0,
        hdrSupported: Boolean = false,
        coverUrl: String = ""
    ) = PolarisGame(
        id = id,
        name = name,
        source = source,
        launcherSource = source,
        category = category,
        genres = genres,
        lastLaunched = lastLaunched,
        hdrSupported = hdrSupported,
        coverUrl = coverUrl
    )
}
