package com.papi.nova.ui

import com.papi.nova.api.PolarisGame

enum class NovaLibraryPrimaryFilter {
    ALL,
    RECENT,
    SOURCES,
    HDR,
    MORE
}

data class NovaLibraryFilterState(
    val primary: NovaLibraryPrimaryFilter = NovaLibraryPrimaryFilter.ALL,
    val source: String = "",
    val category: String = "",
    val genre: String = ""
) {
    val hasActiveConstraint: Boolean
        get() = primary != NovaLibraryPrimaryFilter.ALL
}

enum class NovaLibraryEmptyState {
    DEFAULT,
    RECENT,
    FILTERED
}

data class NovaLibrarySummary(
    val totalCount: Int,
    val recentCount: Int,
    val hdrCount: Int
)

data class NovaLibraryUiModel(
    val allGames: List<PolarisGame>,
    val filteredGames: List<PolarisGame>,
    val recentGames: List<PolarisGame>,
    val summary: NovaLibrarySummary,
    val emptyState: NovaLibraryEmptyState,
    val resultCount: Int
)

object NovaLibraryUiStateMapper {
    private const val RECENT_LIMIT = 6

    fun build(
        games: List<PolarisGame>,
        search: String,
        filterState: NovaLibraryFilterState
    ): NovaLibraryUiModel {
        val filtered = filterGames(games, search, filterState)
        return NovaLibraryUiModel(
            allGames = games,
            filteredGames = filtered,
            recentGames = recentGames(games),
            summary = summary(games),
            emptyState = emptyState(search, filterState),
            resultCount = filtered.size
        )
    }

    fun filterGames(
        games: List<PolarisGame>,
        search: String,
        filterState: NovaLibraryFilterState
    ): List<PolarisGame> {
        val searched = if (search.isBlank()) {
            games
        } else {
            games.filter { it.name.contains(search, ignoreCase = true) }
        }

        return when (filterState.primary) {
            NovaLibraryPrimaryFilter.RECENT -> searched
                .filter { it.lastLaunched > 0 }
                .sortedByDescending { it.lastLaunched }
            NovaLibraryPrimaryFilter.SOURCES -> searched.filter { it.source == filterState.source }
            NovaLibraryPrimaryFilter.HDR -> searched.filter { it.hdrSupported }
            NovaLibraryPrimaryFilter.MORE -> when {
                filterState.category.isNotBlank() -> searched.filter { it.category == filterState.category }
                filterState.genre.isNotBlank() -> searched.filter { game ->
                    game.genres.any { it.equals(filterState.genre, ignoreCase = true) }
                }
                else -> searched
            }
            NovaLibraryPrimaryFilter.ALL -> searched
        }
    }

    fun recentGames(games: List<PolarisGame>): List<PolarisGame> {
        return games
            .filter { it.lastLaunched > 0 }
            .sortedByDescending { it.lastLaunched }
            .take(RECENT_LIMIT)
    }

    fun summary(games: List<PolarisGame>): NovaLibrarySummary {
        return NovaLibrarySummary(
            totalCount = games.size,
            recentCount = games.count { it.lastLaunched > 0 },
            hdrCount = games.count { it.hdrSupported }
        )
    }

    fun emptyState(
        search: String,
        filterState: NovaLibraryFilterState
    ): NovaLibraryEmptyState {
        return when {
            filterState.primary == NovaLibraryPrimaryFilter.RECENT -> NovaLibraryEmptyState.RECENT
            search.isNotBlank() || filterState.hasActiveConstraint -> NovaLibraryEmptyState.FILTERED
            else -> NovaLibraryEmptyState.DEFAULT
        }
    }

    fun gridColumns(widthDp: Int, isLandscape: Boolean): Int {
        return if (isLandscape) {
            when {
                widthDp >= 1200 -> 5
                widthDp >= 960 -> 4
                widthDp >= 720 -> 3
                else -> 2
            }
        } else {
            when {
                widthDp >= 960 -> 5
                widthDp >= 720 -> 4
                widthDp >= 600 -> 3
                else -> 2
            }
        }
    }

    fun sourceFilters(games: List<PolarisGame>): List<String> {
        return games
            .map { it.source }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ sourceSortOrder(it) }, { it }))
    }

    fun categoryFilters(games: List<PolarisGame>): List<String> {
        return listOf("fast_action", "cinematic", "desktop", "vr")
            .filter { category -> games.any { it.category == category } }
    }

    fun genreFilters(games: List<PolarisGame>): List<String> {
        return games
            .flatMap { it.genres }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sorted()
            .take(10)
    }

    private fun sourceSortOrder(source: String): Int {
        return when (source) {
            "steam" -> 0
            "lutris" -> 1
            "heroic" -> 2
            else -> 3
        }
    }
}
