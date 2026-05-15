package com.papi.nova.ui

import com.papi.nova.api.PolarisGame
import org.junit.Assert.assertEquals
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
