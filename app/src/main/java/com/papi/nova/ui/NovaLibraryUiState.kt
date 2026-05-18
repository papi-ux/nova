package com.papi.nova.ui

import com.papi.nova.api.PolarisGame
import com.papi.nova.api.PolarisSessionStatus

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

data class NovaLibraryActiveSessionUiState(
    val gameId: Int,
    val gameUuid: String,
    val gameName: String,
    val ownerDeviceName: String,
    val ownedByClient: Boolean,
    val viewerCount: Int,
    val virtualDisplay: Boolean,
    val displayModeExplicit: Boolean,
    val streamWidth: Int,
    val streamHeight: Int,
    val streamFps: Float
) {
    val watchOnly: Boolean
        get() = !ownedByClient

    companion object {
        private val STREAM_MODE_PATTERN = Regex("""^\s*(\d+)x(\d+)x(\d+(?:\.\d+)?)\s*$""")

        fun from(status: PolarisSessionStatus?): NovaLibraryActiveSessionUiState? {
            if (status == null || status.isShuttingDown || status.gameId <= 0) {
                return null
            }
            if (!status.isResumable) {
                return null
            }

            val streamProfile = parseStreamProfile(
                status.syncStatus.applied.displayMode
                    .ifBlank { status.syncStatus.effective.displayMode }
                    .ifBlank { status.profileState.currentProfile.displayMode }
            )
            return NovaLibraryActiveSessionUiState(
                gameId = status.gameId,
                gameUuid = status.gameUuid,
                gameName = status.game,
                ownerDeviceName = status.ownerDeviceName,
                ownedByClient = status.ownedByClient,
                viewerCount = status.viewerCount.coerceAtLeast(0),
                virtualDisplay = status.displayMode.virtualDisplay ||
                    status.syncStatus.applied.streamDisplayMode.equals("host_virtual_display", ignoreCase = true) ||
                    status.syncStatus.applied.streamDisplayMode.equals("virtual_display", ignoreCase = true),
                displayModeExplicit = status.hasExplicitDisplayModeChoice,
                streamWidth = streamProfile.width,
                streamHeight = streamProfile.height,
                streamFps = streamProfile.fps
            )
        }

        private fun parseStreamProfile(displayMode: String): StreamProfile {
            val match = STREAM_MODE_PATTERN.matchEntire(displayMode) ?: return StreamProfile()
            return StreamProfile(
                width = match.groupValues[1].toIntOrNull() ?: 0,
                height = match.groupValues[2].toIntOrNull() ?: 0,
                fps = match.groupValues[3].toFloatOrNull() ?: 0f
            )
        }

        private data class StreamProfile(
            val width: Int = 0,
            val height: Int = 0,
            val fps: Float = 0f
        )
    }
}

object NovaLibraryUiStateMapper {
    private const val RECENT_LIMIT = 6
    private const val LANDSCAPE_OUTER_PADDING_DP = 20
    private const val LANDSCAPE_RAIL_GAP_DP = 10
    const val RECENT_RAIL_VISIBLE_COLUMNS = 4
    private const val RECENT_RAIL_HORIZONTAL_PADDING_DP = 24
    private const val GAME_CARD_GAP_DP = 10
    private const val MIN_RECENT_RAIL_CARD_WIDTH_DP = 72

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
        if (search.isBlank() && filterState.primary == NovaLibraryPrimaryFilter.ALL) {
            return games
        }

        val searched = if (search.isBlank()) {
            games.asSequence()
        } else {
            games.asSequence().filter { it.name.contains(search, ignoreCase = true) }
        }

        return when (filterState.primary) {
            NovaLibraryPrimaryFilter.RECENT -> searched
                .filter { it.lastLaunched > 0 }
                .sortedByDescending { it.lastLaunched }
                .toList()
            NovaLibraryPrimaryFilter.SOURCES -> searched
                .filter { it.source == filterState.source }
                .toList()
            NovaLibraryPrimaryFilter.HDR -> searched
                .filter { it.hdrSupported }
                .toList()
            NovaLibraryPrimaryFilter.MORE -> when {
                filterState.category.isNotBlank() -> searched
                    .filter { it.category == filterState.category }
                    .toList()
                filterState.genre.isNotBlank() -> searched
                    .filter { game ->
                        game.genres.any { it.equals(filterState.genre, ignoreCase = true) }
                    }
                    .toList()
                else -> searched.toList()
            }
            NovaLibraryPrimaryFilter.ALL -> searched.toList()
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
                else -> 3
            }
        } else {
            when {
                widthDp >= 960 -> 5
                widthDp >= 720 -> 4
                widthDp >= 600 -> 3
                else -> 3
            }
        }
    }

    fun gridColumnsForScreen(widthDp: Int, isLandscape: Boolean): Int {
        val contentWidth = contentWidthDp(widthDp, isLandscape)
        return if (isLandscape) {
            when {
                contentWidth >= 1320 -> 6
                contentWidth >= 900 -> 4
                contentWidth >= 660 -> 3
                else -> 3
            }
        } else {
            gridColumns(contentWidth, isLandscape = false)
        }
    }

    fun recentRailCardWidthDp(
        availableWidthDp: Int,
        visibleColumns: Int = RECENT_RAIL_VISIBLE_COLUMNS
    ): Int {
        val columns = visibleColumns.coerceAtLeast(1)
        val gapWidth = GAME_CARD_GAP_DP * (columns - 1)
        return ((availableWidthDp - RECENT_RAIL_HORIZONTAL_PADDING_DP - gapWidth) / columns)
            .coerceAtLeast(MIN_RECENT_RAIL_CARD_WIDTH_DP)
    }

    fun gameCardHeightDp(compact: Boolean, isLandscape: Boolean): Int {
        return when {
            compact -> 112
            isLandscape -> 138
            else -> 168
        }
    }

    fun contentWidthDp(widthDp: Int, isLandscape: Boolean): Int {
        if (!isLandscape) return widthDp
        return (widthDp - LANDSCAPE_OUTER_PADDING_DP - LANDSCAPE_RAIL_GAP_DP - railWidthDp(widthDp))
            .coerceAtLeast(0)
    }

    fun railWidthDp(widthDp: Int): Int {
        return if (widthDp >= 1200) 268 else 236
    }

    fun filterChipWidthDp(filter: NovaLibraryPrimaryFilter): Int {
        return when (filter) {
            NovaLibraryPrimaryFilter.ALL -> 112
            NovaLibraryPrimaryFilter.RECENT -> 132
            NovaLibraryPrimaryFilter.SOURCES -> 144
            NovaLibraryPrimaryFilter.HDR -> 112
            NovaLibraryPrimaryFilter.MORE -> 120
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
