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

enum class NovaLibrarySortMode {
    LIBRARY_ORDER,
    RECENT,
    NAME_ASC,
    NAME_DESC,
    SOURCE,
    HDR_FIRST
}

enum class NovaLibraryLayoutMode {
    GRID,
    COMPACT_GRID
}

data class NovaLibraryOptionsState(
    val sortMode: NovaLibrarySortMode = NovaLibrarySortMode.LIBRARY_ORDER,
    val layoutMode: NovaLibraryLayoutMode = NovaLibraryLayoutMode.GRID
)

enum class NovaLibraryEmptyState {
    DEFAULT,
    RECENT,
    SOURCE,
    FILTERED
}

data class NovaLibrarySummary(
    val totalCount: Int,
    val recentCount: Int,
    val hdrCount: Int
)

enum class NovaLibraryHeroReason {
    ACTIVE_SESSION,
    LAST_PLAYED,
    FIRST_FILTERED,
    FIRST_LIBRARY_GAME,
    EMPTY
}

enum class NovaLibraryHeroPrimaryAction {
    RESUME,
    WATCH,
    OPEN_DETAIL,
    MANAGE_LIBRARY,
    CLEAR_FILTERS
}

data class NovaLibraryHeroState(
    val game: PolarisGame?,
    val title: String,
    val subtitle: String,
    val caption: String,
    val eyebrow: String,
    val actionLabel: String,
    val badges: List<String>,
    val reason: NovaLibraryHeroReason,
    val primaryAction: NovaLibraryHeroPrimaryAction
)

data class NovaLibraryUiModel(
    val allGames: List<PolarisGame>,
    val filteredGames: List<PolarisGame>,
    val recentGames: List<PolarisGame>,
    val optionsState: NovaLibraryOptionsState,
    val hero: NovaLibraryHeroState,
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
    private const val RAIL_SCROLL_BOTTOM_PADDING_DP = 96
    private const val RAIL_VERTICAL_SPACING_DP = 4
    private const val FILTER_CHIP_HEIGHT_DP = 38
    private const val RAIL_FILTER_GRID_SPACING_DP = 4
    private const val RAIL_FILTER_GRID_TWO_COLUMN_MIN_WIDTH_DP = 200
    private const val RAIL_ACTION_BUTTON_MIN_HEIGHT_DP = 38
    private const val RAIL_ACTION_GRID_SPACING_DP = 8
    private const val RAIL_ACTION_GRID_THREE_COLUMN_MIN_WIDTH_DP = 200
    private const val LANDSCAPE_RECENT_RAIL_MIN_HEIGHT_DP = 560

    fun build(
        games: List<PolarisGame>,
        search: String,
        filterState: NovaLibraryFilterState,
        optionsState: NovaLibraryOptionsState = NovaLibraryOptionsState(),
        activeSession: NovaLibraryActiveSessionUiState? = null
    ): NovaLibraryUiModel {
        val filtered = filterGames(games, search, filterState, optionsState)
        val emptyState = emptyState(search, filterState)
        return NovaLibraryUiModel(
            allGames = games,
            filteredGames = filtered,
            recentGames = recentGames(games),
            optionsState = optionsState,
            hero = heroState(
                games = games,
                filteredGames = filtered,
                activeSession = activeSession,
                constraintsActive = search.isNotBlank() || filterState.hasActiveConstraint,
                emptyState = emptyState
            ),
            summary = summary(games),
            emptyState = emptyState,
            resultCount = filtered.size
        )
    }

    fun heroState(
        games: List<PolarisGame>,
        filteredGames: List<PolarisGame>,
        activeSession: NovaLibraryActiveSessionUiState?,
        constraintsActive: Boolean = false,
        emptyState: NovaLibraryEmptyState = if (constraintsActive) {
            NovaLibraryEmptyState.FILTERED
        } else {
            NovaLibraryEmptyState.DEFAULT
        }
    ): NovaLibraryHeroState {
        if (activeSession != null) {
            return activeSessionHero(activeSession, games)
        }

        val filtered = filteredGames.firstOrNull()
        if (constraintsActive) {
            return if (filtered != null) {
                gameHero(
                    game = filtered,
                    reason = NovaLibraryHeroReason.FIRST_FILTERED,
                    eyebrow = "Filtered library",
                    caption = "Filters active - clear to browse every game."
                )
            } else if (emptyState == NovaLibraryEmptyState.RECENT && games.isNotEmpty()) {
                NovaLibraryHeroState(
                    game = null,
                    title = "No recent games",
                    subtitle = "Your library has ${games.size} games ready.",
                    caption = "Launch any game once and it will appear in Continue.",
                    eyebrow = "Continue when ready",
                    actionLabel = "View all games",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS
                )
            } else if (emptyState == NovaLibraryEmptyState.SOURCE && games.isNotEmpty()) {
                NovaLibraryHeroState(
                    game = null,
                    title = "No games from this source",
                    subtitle = "Your library has ${games.size} games ready.",
                    caption = "Clear the source filter or manage your Polaris library.",
                    eyebrow = "Source filter",
                    actionLabel = "Clear source",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS
                )
            } else {
                NovaLibraryHeroState(
                    game = null,
                    title = "No matching games",
                    subtitle = "Clear search or filters to browse your full library.",
                    caption = "No match for the current search or filters.",
                    eyebrow = "Filtered library",
                    actionLabel = "Clear filters",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS
                )
            }
        }

        val recent = recentGames(games).firstOrNull()
        if (recent != null) {
            return gameHero(
                game = recent,
                reason = NovaLibraryHeroReason.LAST_PLAYED,
                eyebrow = "Continue playing",
                caption = "Recent on this host."
            )
        }

        if (filtered != null) {
            return gameHero(
                game = filtered,
                reason = NovaLibraryHeroReason.FIRST_FILTERED,
                eyebrow = "Ready when you are",
                caption = "Choose profile, display, and stream settings."
            )
        }

        val firstGame = games.firstOrNull()
        if (firstGame != null) {
            return gameHero(
                game = firstGame,
                reason = NovaLibraryHeroReason.FIRST_LIBRARY_GAME,
                eyebrow = "Ready when you are",
                caption = "Choose profile, display, and stream settings."
            )
        }

        return NovaLibraryHeroState(
            game = null,
            title = "Build your library",
            subtitle = "Connect Polaris to bring your games into Nova.",
            caption = "Manage Library in Polaris to add games and launch metadata.",
            eyebrow = "No games yet",
            actionLabel = "Manage library",
            badges = listOf("Polaris ready"),
            reason = NovaLibraryHeroReason.EMPTY,
            primaryAction = NovaLibraryHeroPrimaryAction.MANAGE_LIBRARY
        )
    }

    private fun activeSessionHero(
        session: NovaLibraryActiveSessionUiState,
        games: List<PolarisGame>
    ): NovaLibraryHeroState {
        val matchingGame = games.firstOrNull { game ->
            game.id == session.gameUuid || game.appId == session.gameId || game.name.equals(session.gameName, ignoreCase = true)
        }
        val badges = buildList {
            add("Active session")
            if (session.virtualDisplay) add("Virtual display")
            if (session.streamWidth > 0 && session.streamHeight > 0 && session.streamFps > 0f) {
                add("${session.streamWidth}×${session.streamHeight} ${session.streamFps.toInt()}fps")
            }
            if (session.viewerCount > 0) {
                add(if (session.viewerCount == 1) "1 viewer" else "${session.viewerCount} viewers")
            }
        }
        return NovaLibraryHeroState(
            game = matchingGame,
            title = session.gameName.ifBlank { "Active session" },
            subtitle = session.ownerDeviceName.ifBlank { "Polaris session" },
            caption = if (session.ownedByClient) {
                "Resume the current display and quality profile."
            } else {
                "Watch-only view; owner stays in control."
            },
            eyebrow = if (session.ownedByClient) "Resume your stream" else "Watch active stream",
            actionLabel = if (session.ownedByClient) "Resume stream" else "Watch stream",
            badges = badges,
            reason = NovaLibraryHeroReason.ACTIVE_SESSION,
            primaryAction = if (session.ownedByClient) {
                NovaLibraryHeroPrimaryAction.RESUME
            } else {
                NovaLibraryHeroPrimaryAction.WATCH
            }
        )
    }

    private fun gameHero(
        game: PolarisGame,
        reason: NovaLibraryHeroReason,
        eyebrow: String,
        caption: String
    ): NovaLibraryHeroState {
        val badges = buildList {
            if (game.lastLaunched > 0) add("Recent")
            if (game.hdrSupported) add("HDR")
            if (game.categoryLabel.isNotBlank()) add(game.categoryLabel)
            if (game.sourceLabel.isNotBlank()) add(game.sourceLabel)
            if (game.runtimeLabel.isNotBlank()) add(game.runtimeLabel)
        }.distinct()
        return NovaLibraryHeroState(
            game = game,
            title = game.name,
            subtitle = game.sourceRuntimeLabel.ifBlank { game.sourceLabel.ifBlank { "Nova library" } },
            caption = caption,
            eyebrow = eyebrow,
            actionLabel = "Launch options",
            badges = badges,
            reason = reason,
            primaryAction = NovaLibraryHeroPrimaryAction.OPEN_DETAIL
        )
    }

    fun filterGames(
        games: List<PolarisGame>,
        search: String,
        filterState: NovaLibraryFilterState,
        optionsState: NovaLibraryOptionsState = NovaLibraryOptionsState()
    ): List<PolarisGame> {
        if (
            search.isBlank() &&
            filterState.primary == NovaLibraryPrimaryFilter.ALL &&
            optionsState.sortMode == NovaLibrarySortMode.LIBRARY_ORDER
        ) {
            return games
        }

        val searched = if (search.isBlank()) {
            games.asSequence()
        } else {
            games.asSequence().filter { it.name.contains(search, ignoreCase = true) }
        }

        val filtered = when (filterState.primary) {
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
        return sortGames(filtered, optionsState.sortMode)
    }

    fun sortGames(
        games: List<PolarisGame>,
        sortMode: NovaLibrarySortMode
    ): List<PolarisGame> {
        return when (sortMode) {
            NovaLibrarySortMode.LIBRARY_ORDER -> games
            NovaLibrarySortMode.RECENT -> games.sortedWith(
                compareByDescending<PolarisGame> { it.lastLaunched > 0 }
                    .thenByDescending { it.lastLaunched }
                    .thenBy { it.name.lowercase() }
            )
            NovaLibrarySortMode.NAME_ASC -> games.sortedBy { it.name.lowercase() }
            NovaLibrarySortMode.NAME_DESC -> games.sortedByDescending { it.name.lowercase() }
            NovaLibrarySortMode.SOURCE -> games.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<PolarisGame>> { sourceSortOrder(it.value.source.lowercase()) }
                        .thenBy { it.value.source.lowercase() }
                        .thenBy { it.index }
                )
                .map { it.value }
            NovaLibrarySortMode.HDR_FIRST -> games.sortedWith(
                compareByDescending<PolarisGame> { it.hdrSupported }
                    .thenBy { it.name.lowercase() }
            )
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
            filterState.primary == NovaLibraryPrimaryFilter.SOURCES && filterState.source.isNotBlank() -> {
                NovaLibraryEmptyState.SOURCE
            }
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

    fun gridColumnsForScreen(
        widthDp: Int,
        isLandscape: Boolean,
        layoutMode: NovaLibraryLayoutMode = NovaLibraryLayoutMode.GRID
    ): Int {
        val contentWidth = contentWidthDp(widthDp, isLandscape)
        val baseColumns = if (isLandscape) {
            when {
                contentWidth >= 1320 -> 6
                contentWidth >= 1080 -> 5
                contentWidth >= 800 -> 4
                contentWidth >= 660 -> 3
                else -> 3
            }
        } else {
            gridColumns(contentWidth, isLandscape = false)
        }
        return when (layoutMode) {
            NovaLibraryLayoutMode.GRID -> baseColumns
            NovaLibraryLayoutMode.COMPACT_GRID -> (baseColumns + 1).coerceAtMost(6)
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
        return gameCardHeightDp(
            layoutMode = if (compact) NovaLibraryLayoutMode.COMPACT_GRID else NovaLibraryLayoutMode.GRID,
            isLandscape = isLandscape
        )
    }

    fun gameCardHeightDp(layoutMode: NovaLibraryLayoutMode, isLandscape: Boolean): Int {
        return when (layoutMode) {
            NovaLibraryLayoutMode.COMPACT_GRID -> 112
            NovaLibraryLayoutMode.GRID -> if (isLandscape) 156 else 168
        }
    }

    fun heroHeightDp(compact: Boolean): Int {
        return if (compact) 76 else 164
    }

    fun showLandscapeRecentRail(
        screenHeightDp: Int,
        heroReason: NovaLibraryHeroReason,
        recentCount: Int
    ): Boolean {
        if (recentCount <= 0 || screenHeightDp < LANDSCAPE_RECENT_RAIL_MIN_HEIGHT_DP) {
            return false
        }
        return when (heroReason) {
            NovaLibraryHeroReason.ACTIVE_SESSION,
            NovaLibraryHeroReason.LAST_PLAYED -> false
            NovaLibraryHeroReason.FIRST_FILTERED,
            NovaLibraryHeroReason.FIRST_LIBRARY_GAME,
            NovaLibraryHeroReason.EMPTY -> true
        }
    }

    fun contentWidthDp(
        widthDp: Int,
        isLandscape: Boolean,
        includeControlRail: Boolean = showLandscapeControlRail()
    ): Int {
        if (!isLandscape) return widthDp
        val reservedWidth = if (includeControlRail) {
            LANDSCAPE_OUTER_PADDING_DP + LANDSCAPE_RAIL_GAP_DP + railWidthDp(widthDp)
        } else {
            LANDSCAPE_OUTER_PADDING_DP
        }
        return (widthDp - reservedWidth).coerceAtLeast(0)
    }

    fun showLandscapeControlRail(): Boolean = false

    fun railWidthDp(widthDp: Int): Int {
        return if (widthDp >= 1200) 268 else 236
    }

    fun railScrollBottomPaddingDp(): Int = RAIL_SCROLL_BOTTOM_PADDING_DP

    fun railVerticalSpacingDp(): Int = RAIL_VERTICAL_SPACING_DP

    fun filterChipHeightDp(): Int = FILTER_CHIP_HEIGHT_DP

    fun railFilterGridSpacingDp(): Int = RAIL_FILTER_GRID_SPACING_DP

    fun railActionButtonMinHeightDp(): Int = RAIL_ACTION_BUTTON_MIN_HEIGHT_DP

    fun railActionGridSpacingDp(): Int = RAIL_ACTION_GRID_SPACING_DP

    fun railActionColumns(availableWidthDp: Int): Int {
        return if (availableWidthDp >= RAIL_ACTION_GRID_THREE_COLUMN_MIN_WIDTH_DP) 3 else 2
    }

    fun railActionRows(actionCount: Int, availableWidthDp: Int): Int {
        val count = actionCount.coerceAtLeast(0)
        if (count == 0) return 0
        val columns = railActionColumns(availableWidthDp)
        return (count + columns - 1) / columns
    }

    fun railActionBlockHeightDp(actionCount: Int, availableWidthDp: Int): Int {
        val rows = railActionRows(actionCount, availableWidthDp)
        if (rows == 0) return 0
        return (rows * RAIL_ACTION_BUTTON_MIN_HEIGHT_DP) + ((rows - 1) * RAIL_VERTICAL_SPACING_DP)
    }

    fun railFilterColumns(availableWidthDp: Int): Int {
        return if (availableWidthDp >= RAIL_FILTER_GRID_TWO_COLUMN_MIN_WIDTH_DP) 2 else 1
    }

    fun railFilterRows(filterCount: Int, availableWidthDp: Int): Int {
        val count = filterCount.coerceAtLeast(0)
        if (count == 0) return 0
        val columns = railFilterColumns(availableWidthDp)
        return (count + columns - 1) / columns
    }

    fun railFilterGridHeightDp(filterCount: Int, availableWidthDp: Int): Int {
        val rows = railFilterRows(filterCount, availableWidthDp)
        if (rows == 0) return 0
        return (rows * FILTER_CHIP_HEIGHT_DP) + ((rows - 1) * RAIL_FILTER_GRID_SPACING_DP)
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
