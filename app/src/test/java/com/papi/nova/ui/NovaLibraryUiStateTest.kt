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
    fun uiModelBuildsCountsRecentRailAndEmptyStates() {
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
        assertEquals(NovaLibraryEmptyState.FILTERED, model.emptyState)
        assertEquals(NovaLibraryEmptyState.RECENT, recentModel.emptyState)
    }

    @Test
    fun gridColumnsMatchCurrentBreakpoints() {
        assertEquals(2, NovaLibraryUiStateMapper.gridColumns(widthDp = 540, isLandscape = false))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 600, isLandscape = false))
        assertEquals(4, NovaLibraryUiStateMapper.gridColumns(widthDp = 720, isLandscape = false))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumns(widthDp = 960, isLandscape = false))
        assertEquals(2, NovaLibraryUiStateMapper.gridColumns(widthDp = 600, isLandscape = true))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumns(widthDp = 720, isLandscape = true))
        assertEquals(4, NovaLibraryUiStateMapper.gridColumns(widthDp = 960, isLandscape = true))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumns(widthDp = 1200, isLandscape = true))
    }

    @Test
    fun landscapeGridColumnsUseContentWidthAfterRail() {
        assertEquals(4, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 1200, isLandscape = true))
        assertEquals(3, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 960, isLandscape = true))
        assertEquals(2, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 720, isLandscape = true))
        assertEquals(5, NovaLibraryUiStateMapper.gridColumnsForScreen(widthDp = 960, isLandscape = false))
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
        hdrSupported: Boolean = false
    ) = PolarisGame(
        id = id,
        name = name,
        source = source,
        launcherSource = source,
        category = category,
        genres = genres,
        lastLaunched = lastLaunched,
        hdrSupported = hdrSupported
    )
}
