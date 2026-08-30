package com.papi.nova.ui

import com.papi.nova.shared.polaris.model.PolarisGame
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
    COMPACT,
    STAGE;

    fun next(): NovaLibraryLayoutMode = when (this) {
        GRID -> COMPACT
        COMPACT -> STAGE
        STAGE -> GRID
    }
}

internal data class NovaPortraitPosterSize(
    val widthDp: Int,
    val heightDp: Int,
)

internal data class NovaPosterPresentationSpec(
    val focusedScale: Float,
    val unfocusedAlpha: Float,
    val focusGutterDp: Int,
)

data class NovaLibraryOptionsState(
    val sortMode: NovaLibrarySortMode = NovaLibrarySortMode.LIBRARY_ORDER,
    val layoutMode: NovaLibraryLayoutMode = NovaLibraryLayoutMode.GRID,
    val showPosterTitles: Boolean = false
)

enum class NovaLibraryWindowClass {
    PHONE_PORTRAIT,
    HANDHELD_LANDSCAPE,
    TV_LANDSCAPE
}

data class NovaLibraryLayoutSpec(
    val windowClass: NovaLibraryWindowClass,
    val gridColumns: Int,
    val gameCardHeightDp: Int,
    val stageUsesVerticalGrid: Boolean,
    val stagePosterColumns: Int,
    val stageHeroHeightDp: Int,
    val stagePosterRailHeightDp: Int,
    val stageChromeBudgetDp: Int,
    val stageUsesCompactHero: Boolean,
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

enum class NovaLibraryHeroSecondaryAction {
    END_SESSION
}

enum class NovaLibraryRecoveryAction {
    RETRY,
    MANAGE_LIBRARY,
    CLEAR_FILTERS
}

data class NovaLibraryRecoveryUiState(
    val eyebrow: String,
    val title: String,
    val message: String,
    val primaryActionLabel: String,
    val primaryAction: NovaLibraryRecoveryAction,
    val detail: String? = null,
    val secondaryActionLabel: String? = null,
    val secondaryAction: NovaLibraryRecoveryAction? = null
)

data class NovaLibraryHeroState(
    val game: PolarisGame?,
    val title: String,
    val subtitle: String,
    val caption: String,
    val eyebrow: String,
    val actionLabel: String,
    val badges: List<String>,
    val reason: NovaLibraryHeroReason,
    val primaryAction: NovaLibraryHeroPrimaryAction,
    val supportingLine: String,
    val artworkFallbackTitle: String,
    val artworkFallbackSubtitle: String,
    val secondaryActionLabel: String? = null,
    val secondaryAction: NovaLibraryHeroSecondaryAction? = null
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
    val streamFps: Float,
    val streamMode: String = "",
    val mirrorDesktop: Boolean = false,
    val forcePrivateAfterSteamClose: Boolean = false
) {
    val watchOnly: Boolean
        get() = !ownedByClient

    companion object {
        private val STREAM_MODE_PATTERN = Regex("""^\s*(\d+)x(\d+)x(\d+(?:\.\d+)?)\s*$""")
        private val STREAM_RESOLUTION_PATTERN = Regex("""^\s*(\d+)x(\d+)\s*$""")

        fun from(status: PolarisSessionStatus?): NovaLibraryActiveSessionUiState? {
            if (status == null || status.isShuttingDown || status.gameId <= 0) {
                return null
            }
            if (!status.isResumable || (!status.ownedByClient && !status.streamingActive)) {
                return null
            }

            val fallbackStreamProfile = parseStreamProfile(
                status.syncStatus.applied.displayMode
                    .ifBlank { status.syncStatus.effective.displayMode }
                    .ifBlank { status.profileState.currentProfile.displayMode }
            )
            val streamProfile = if (!status.ownedByClient && status.streamingActive) {
                val liveResolution = parseStreamResolution(status.capture.resolution)
                StreamProfile(
                    width = liveResolution.width.takeIf { it > 0 } ?: fallbackStreamProfile.width,
                    height = liveResolution.height.takeIf { it > 0 } ?: fallbackStreamProfile.height,
                    fps = status.encoder.sessionTargetFps.toFloat().takeIf { it > 0f }
                        ?: fallbackStreamProfile.fps
                )
            } else {
                fallbackStreamProfile
            }
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
                streamFps = streamProfile.fps,
                // Resume Existing is an exact continuation of the active
                // generation, not a new topology choice. Preserve canonical
                // owner semantics so the deterministic resume envelope can be
                // validated by Polaris. Viewer Watch remains read-only and
                // lets Polaris pin the owner's active semantics.
                streamMode = if (status.ownedByClient) {
                    status.displayMode.selection
                        .ifBlank { status.syncStatus.applied.streamDisplayMode }
                        .ifBlank { status.syncStatus.effective.streamDisplayMode }
                } else {
                    ""
                },
                mirrorDesktop = status.ownedByClient && status.displayMode.mirrorDesktop,
                forcePrivateAfterSteamClose =
                    status.ownedByClient && status.displayMode.forcePrivateAfterSteamClose
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

        private fun parseStreamResolution(resolution: String): StreamProfile {
            val match = STREAM_RESOLUTION_PATTERN.matchEntire(resolution) ?: return StreamProfile()
            return StreamProfile(
                width = match.groupValues[1].toIntOrNull() ?: 0,
                height = match.groupValues[2].toIntOrNull() ?: 0
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
    private const val GRID_CONTENT_PADDING_DP = 10
    private const val LANDSCAPE_GRID_BOTTOM_CONTENT_PADDING_DP = 24
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
    private const val LANDSCAPE_SCREEN_PADDING_DP = 8
    private const val PORTRAIT_SCREEN_PADDING_DP = 8
    private const val LANDSCAPE_CONTENT_SPACING_DP = 6
    private const val LANDSCAPE_CONTROLLER_HINT_BOTTOM_PADDING_DP = 48

    /**
     * The cinematic stage reserves less than the grid/compact shells. Those lay poster
     * rows out under an overlaid hint bar and need the bar height plus breathing room;
     * the stage instead anchors a single rail above a deliberately light three-hint
     * footer, so the extra gutter only pushed the rail away from its baseline.
     */
    private const val STAGE_CONTROLLER_HINT_FOOTER_DP = 40
    private const val PORTRAIT_CONTROLLER_HINT_BOTTOM_PADDING_DP = 40

    fun posterAspectRatio(): Float = 2f / 3f

    /**
     * Cinematic stage poster width as a fraction of the viewport, taken from the Polaris
     * concept (a 200px poster on a 1920px stage). This is the single source of truth for
     * rail density: it holds the same visual proportion on every display, which is why the
     * landscape rail no longer derives its card size from a per-window-class column count.
     */
    const val STAGE_POSTER_WIDTH_FRACTION = 0.105f

    fun stageRailPosterWidthDp(availableWidthDp: Int): Int =
        (availableWidthDp * STAGE_POSTER_WIDTH_FRACTION).toInt().coerceAtLeast(2)

    internal fun posterPresentationSpec(
        mode: NovaLibraryLayoutMode,
    ): NovaPosterPresentationSpec = when (mode) {
        NovaLibraryLayoutMode.STAGE -> NovaPosterPresentationSpec(
            focusedScale = 1.10f,
            unfocusedAlpha = 0.76f,
            focusGutterDp = 6,
        )
        NovaLibraryLayoutMode.GRID -> NovaPosterPresentationSpec(
            focusedScale = 1.08f,
            unfocusedAlpha = 0.84f,
            focusGutterDp = 8,
        )
        NovaLibraryLayoutMode.COMPACT -> NovaPosterPresentationSpec(
            focusedScale = 1.06f,
            unfocusedAlpha = 0.82f,
            focusGutterDp = 6,
        )
    }

    fun portraitPosterHeightDp(widthDp: Int): Int =
        portraitPosterSizeForWidth(widthDp).heightDp

    internal fun portraitPosterSizeForWidth(widthDp: Int): NovaPortraitPosterSize {
        require(widthDp >= 2) { "Poster width must be at least 2dp" }
        val commonUnits = widthDp.toLong() / 2L
        require(commonUnits <= Int.MAX_VALUE.toLong() / 3L) {
            "Poster width exceeds the exact 2:3 integer range"
        }
        return NovaPortraitPosterSize(
            widthDp = (commonUnits * 2L).toInt(),
            heightDp = (commonUnits * 3L).toInt(),
        )
    }

    internal fun minimumPortraitPosterRailHeightDp(
        presentationSpec: NovaPosterPresentationSpec,
    ): Int {
        require(presentationSpec.focusedScale.isFinite() && presentationSpec.focusedScale > 0f) {
            "Focused scale must be finite and positive"
        }
        require(presentationSpec.focusGutterDp >= 0) { "Focus gutter must be non-negative" }
        val minimum = kotlin.math.ceil(
            3.0 * presentationSpec.focusedScale.toDouble() +
                presentationSpec.focusGutterDp.toDouble() * 2.0,
        )
        require(minimum <= Int.MAX_VALUE.toDouble()) { "Poster rail minimum overflows Int" }
        return minimum.toInt()
    }

    internal fun portraitPosterSizeForRail(
        railHeightDp: Int,
        presentationSpec: NovaPosterPresentationSpec,
    ): NovaPortraitPosterSize {
        val minimumRailHeightDp = minimumPortraitPosterRailHeightDp(presentationSpec)
        require(railHeightDp >= minimumRailHeightDp) {
            "Poster rail must be at least ${minimumRailHeightDp}dp"
        }
        val availablePosterHeightDp =
            railHeightDp.toLong() - presentationSpec.focusGutterDp.toLong() * 2L
        val maxPosterHeightDp = kotlin.math.floor(
            availablePosterHeightDp.toDouble() / presentationSpec.focusedScale.toDouble(),
        ).toLong()
        val commonUnits = maxPosterHeightDp / 3L
        require(commonUnits in 1L..(Int.MAX_VALUE.toLong() / 3L)) {
            "Poster rail exceeds the exact 2:3 integer range"
        }
        return NovaPortraitPosterSize(
            widthDp = (commonUnits * 2L).toInt(),
            heightDp = (commonUnits * 3L).toInt(),
        )
    }

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
                    actionLabel = "View All Games",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS,
                    supportingLine = "View all • ${games.size} games ready",
                    artworkFallbackTitle = "No recent games",
                    artworkFallbackSubtitle = "Continue when ready"
                )
            } else if (emptyState == NovaLibraryEmptyState.SOURCE && games.isNotEmpty()) {
                NovaLibraryHeroState(
                    game = null,
                    title = "No games from this source",
                    subtitle = "Your library has ${games.size} games ready.",
                    caption = "Clear the source filter or manage your Polaris library.",
                    eyebrow = "Source filter",
                    actionLabel = "Clear Source",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS,
                    supportingLine = "Clear source • ${games.size} games ready",
                    artworkFallbackTitle = "No games from this source",
                    artworkFallbackSubtitle = "Source filter"
                )
            } else {
                NovaLibraryHeroState(
                    game = null,
                    title = "No matching games",
                    subtitle = "Clear search or filters to browse your full library.",
                    caption = "No match for the current search or filters.",
                    eyebrow = "Filtered library",
                    actionLabel = "Clear Filters",
                    badges = emptyList(),
                    reason = NovaLibraryHeroReason.EMPTY,
                    primaryAction = NovaLibraryHeroPrimaryAction.CLEAR_FILTERS,
                    supportingLine = "Clear filters • Library",
                    artworkFallbackTitle = "No matching games",
                    artworkFallbackSubtitle = "Filtered library"
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
            actionLabel = "Manage Library",
            badges = listOf("Polaris ready"),
            reason = NovaLibraryHeroReason.EMPTY,
            primaryAction = NovaLibraryHeroPrimaryAction.MANAGE_LIBRARY,
            supportingLine = "Connect • Polaris",
            artworkFallbackTitle = "Nova Library",
            artworkFallbackSubtitle = "No games yet"
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
        val streamDetail = streamDetail(session)
        val viewerDetail = viewerDetail(session.viewerCount)
        val ownerDetail = session.ownerDeviceName.ifBlank { "Polaris session" }
        val supportingLine = buildList {
            add(if (session.ownedByClient) "Resume" else "Watch")
            add(ownerDetail)
            add(streamDetail.ifBlank { viewerDetail })
        }.filter { it.isNotBlank() }.joinToString(" • ")
        return NovaLibraryHeroState(
            game = matchingGame,
            title = session.gameName.ifBlank { "Active session" },
            subtitle = ownerDetail,
            caption = if (session.ownedByClient) {
                "Resume this stream, or end it if the host game is stale."
            } else {
                "Watch-only view; owner stays in control."
            },
            eyebrow = if (session.ownedByClient) "Resume your stream" else "Watch active stream",
            actionLabel = if (session.ownedByClient) "Resume Stream" else "Watch Stream",
            badges = badges,
            reason = NovaLibraryHeroReason.ACTIVE_SESSION,
            primaryAction = if (session.ownedByClient) {
                NovaLibraryHeroPrimaryAction.RESUME
            } else {
                NovaLibraryHeroPrimaryAction.WATCH
            },
            supportingLine = supportingLine,
            artworkFallbackTitle = session.gameName.ifBlank { "Active session" },
            artworkFallbackSubtitle = listOf("Active session", ownerDetail)
                .filter { it.isNotBlank() }
                .joinToString(" • "),
            secondaryActionLabel = if (session.ownedByClient) "End Session" else null,
            secondaryAction = if (session.ownedByClient) {
                NovaLibraryHeroSecondaryAction.END_SESSION
            } else {
                null
            }
        )
    }

    private fun streamDetail(session: NovaLibraryActiveSessionUiState): String {
        return if (session.streamWidth > 0 && session.streamHeight > 0 && session.streamFps > 0f) {
            "${session.streamWidth}×${session.streamHeight} ${session.streamFps.toInt()}fps"
        } else {
            ""
        }
    }

    private fun viewerDetail(viewerCount: Int): String {
        return when {
            viewerCount <= 0 -> ""
            viewerCount == 1 -> "1 viewer"
            else -> "$viewerCount viewers"
        }
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
        val subtitle = game.sourceRuntimeLabel.ifBlank { game.sourceLabel.ifBlank { "Nova library" } }
        val actionContext = when (reason) {
            NovaLibraryHeroReason.LAST_PLAYED -> "Continue"
            NovaLibraryHeroReason.FIRST_FILTERED -> if (eyebrow == "Filtered library") "Filtered" else "Ready"
            NovaLibraryHeroReason.FIRST_LIBRARY_GAME -> "Ready"
            NovaLibraryHeroReason.ACTIVE_SESSION -> "Resume"
            NovaLibraryHeroReason.EMPTY -> "Library"
        }
        val fallbackSubtitle = when (reason) {
            NovaLibraryHeroReason.LAST_PLAYED -> "Recent on this host"
            NovaLibraryHeroReason.FIRST_FILTERED -> eyebrow
            NovaLibraryHeroReason.FIRST_LIBRARY_GAME -> "Ready when you are"
            NovaLibraryHeroReason.ACTIVE_SESSION -> "Active session"
            NovaLibraryHeroReason.EMPTY -> "Nova library"
        }
        return NovaLibraryHeroState(
            game = game,
            title = game.name,
            subtitle = subtitle,
            caption = caption,
            eyebrow = eyebrow,
            // This opens the game's window; it does not start a stream. It said
            // "Launch" while its action was OPEN_DETAIL -- the one hero whose label
            // disagreed with what it does. The action is the deliberate half: the
            // detail window is where you decide how to play, so the hero gets you there.
            actionLabel = "Open",
            badges = badges,
            reason = reason,
            primaryAction = NovaLibraryHeroPrimaryAction.OPEN_DETAIL,
            supportingLine = listOf(actionContext, subtitle)
                .filter { it.isNotBlank() }
                .joinToString(" • "),
            artworkFallbackTitle = game.name,
            artworkFallbackSubtitle = fallbackSubtitle
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

    fun emptyRecoveryState(
        emptyState: NovaLibraryEmptyState,
        totalCount: Int,
        sourceName: String?
    ): NovaLibraryRecoveryUiState {
        val gameCount = gameCountText(totalCount)
        return when (emptyState) {
            NovaLibraryEmptyState.DEFAULT -> NovaLibraryRecoveryUiState(
                eyebrow = "Library state",
                title = "No games yet",
                message = "Polaris is reachable, but this host does not have any games in the Nova library yet.",
                primaryActionLabel = "Manage Library",
                primaryAction = NovaLibraryRecoveryAction.MANAGE_LIBRARY
            )
            NovaLibraryEmptyState.RECENT -> NovaLibraryRecoveryUiState(
                eyebrow = "Continue empty",
                title = "No recent games",
                message = "Your library has $gameCount ready. Launch one once and it will appear in Continue.",
                primaryActionLabel = "View All Games",
                primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS
            )
            NovaLibraryEmptyState.SOURCE -> {
                val sourceLabel = sourceDisplayName(sourceName)
                NovaLibraryRecoveryUiState(
                    eyebrow = "Source empty",
                    title = "No $sourceLabel games",
                    message = "That source has no games in this Polaris library view. Clear it to return to $gameCount.",
                    primaryActionLabel = "Clear Source",
                    primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS
                )
            }
            NovaLibraryEmptyState.FILTERED -> NovaLibraryRecoveryUiState(
                eyebrow = "No matches",
                title = "No matches",
                message = "Search or filters returned nothing. Clear constraints to return to $gameCount.",
                primaryActionLabel = "Clear Filters",
                primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS
            )
        }
    }

    fun loadFailureRecoveryState(message: String): NovaLibraryRecoveryUiState {
        val detail = message.takeIf { it.isNotBlank() }
        val normalized = message.lowercase()
        val offline = listOf(
            "unknownhost",
            "unable to resolve",
            "connectexception",
            "failed to connect",
            "connection refused",
            "timed out",
            "timeout",
            "no route",
            "network is unreachable",
            "host unreachable"
        ).any { normalized.contains(it) }
        if (offline) {
            return NovaLibraryRecoveryUiState(
                eyebrow = "Connection",
                title = "Host offline",
                message = "Nova cannot reach this host right now. Wake the PC or check the network, then retry.",
                primaryActionLabel = "Retry",
                primaryAction = NovaLibraryRecoveryAction.RETRY,
                detail = detail
            )
        }

        val polarisUnavailable = listOf(
            "404",
            "not found",
            "405",
            "501",
            "unsupported",
            "capability",
            "capabilities",
            "polaris/v1/games"
        ).any { normalized.contains(it) }
        if (polarisUnavailable) {
            return NovaLibraryRecoveryUiState(
                eyebrow = "Polaris",
                title = "Polaris unavailable",
                message = "The host answered, but the Polaris library API did not. Start or repair Polaris, then return to Nova.",
                primaryActionLabel = "Manage Server",
                primaryAction = NovaLibraryRecoveryAction.MANAGE_LIBRARY,
                detail = detail
            )
        }

        return NovaLibraryRecoveryUiState(
            eyebrow = "Recovery",
            title = "Couldn't load library",
            message = "Check Polaris and try again.",
            primaryActionLabel = "Retry",
            primaryAction = NovaLibraryRecoveryAction.RETRY,
            detail = detail
        )
    }

    fun launchFailureRecoveryState(message: String): NovaLibraryRecoveryUiState {
        return NovaLibraryRecoveryUiState(
            eyebrow = "Launch recovery",
            title = "Launch blocked",
            message = "Nova could not start the stream before leaving Library. Review host and library setup, then try again.",
            primaryActionLabel = "Manage Server",
            primaryAction = NovaLibraryRecoveryAction.MANAGE_LIBRARY,
            detail = message.takeIf { it.isNotBlank() }
        )
    }

    private fun gameCountText(totalCount: Int): String {
        return when (totalCount) {
            1 -> "1 game"
            else -> "$totalCount games"
        }
    }

    private fun sourceDisplayName(sourceName: String?): String {
        val raw = sourceName.orEmpty().trim()
        if (raw.isBlank()) {
            return "source"
        }
        return when (raw.lowercase()) {
            "steam" -> "Steam"
            "heroic" -> "Heroic"
            "lutris" -> "Lutris"
            "gog" -> "GOG"
            "epic" -> "Epic"
            else -> raw
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                }
                .ifBlank { "source" }
        }
    }

    fun layoutSpec(
        widthDp: Int,
        heightDp: Int,
        layoutMode: NovaLibraryLayoutMode,
        largeText: Boolean = false,
    ): NovaLibraryLayoutSpec {
        val windowClass = when {
            heightDp > widthDp -> NovaLibraryWindowClass.PHONE_PORTRAIT
            widthDp >= 1280 -> NovaLibraryWindowClass.TV_LANDSCAPE
            else -> NovaLibraryWindowClass.HANDHELD_LANDSCAPE
        }
        val gridColumns = when (windowClass) {
            NovaLibraryWindowClass.PHONE_PORTRAIT -> when (layoutMode) {
                NovaLibraryLayoutMode.COMPACT -> 4
                else -> 3
            }
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> when (layoutMode) {
                NovaLibraryLayoutMode.COMPACT -> 6
                else -> 5
            }
            NovaLibraryWindowClass.TV_LANDSCAPE -> when (layoutMode) {
                NovaLibraryLayoutMode.COMPACT -> 9
                else -> 7
            }
        }
        val gameCardHeightDp = when (windowClass) {
            NovaLibraryWindowClass.PHONE_PORTRAIT -> if (layoutMode == NovaLibraryLayoutMode.COMPACT) 104 else 168
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> if (layoutMode == NovaLibraryLayoutMode.COMPACT) 88 else 112
            NovaLibraryWindowClass.TV_LANDSCAPE -> if (layoutMode == NovaLibraryLayoutMode.COMPACT) 136 else 180
        }
        val stageHeroHeightDp = when (windowClass) {
            NovaLibraryWindowClass.PHONE_PORTRAIT -> if (largeText) 380 else 320
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> if (largeText) 280 else 300
            NovaLibraryWindowClass.TV_LANDSCAPE -> if (largeText) 600 else 520
        }
        val stagePosterColumns = when (windowClass) {
            NovaLibraryWindowClass.PHONE_PORTRAIT -> 2
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> if (largeText) 4 else 3
            NovaLibraryWindowClass.TV_LANDSCAPE -> if (largeText) 7 else 5
        }
        val stagePosterRailHeightDp = when (windowClass) {
            NovaLibraryWindowClass.PHONE_PORTRAIT -> 300
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> if (largeText) 200 else 172
            NovaLibraryWindowClass.TV_LANDSCAPE -> 320
        }
        return NovaLibraryLayoutSpec(
            windowClass = windowClass,
            gridColumns = gridColumns,
            gameCardHeightDp = gameCardHeightDp,
            stageUsesVerticalGrid = windowClass == NovaLibraryWindowClass.PHONE_PORTRAIT,
            stagePosterColumns = stagePosterColumns,
            stageHeroHeightDp = stageHeroHeightDp,
            stagePosterRailHeightDp = stagePosterRailHeightDp,
            stageChromeBudgetDp = stageHeroHeightDp + stagePosterRailHeightDp,
            stageUsesCompactHero = false,
        )
    }

    fun stageLayoutSpecForViewport(
        widthDp: Int,
        heightDp: Int,
        largeText: Boolean,
    ): NovaLibraryLayoutSpec {
        val base = layoutSpec(
            widthDp = widthDp,
            heightDp = heightDp,
            layoutMode = NovaLibraryLayoutMode.STAGE,
            largeText = largeText,
        )
        if (base.windowClass == NovaLibraryWindowClass.PHONE_PORTRAIT) return base

        // Budget hero + rail against the real viewport so the top-anchored hero
        // and the bottom-anchored rail cannot draw over each other.
        val minimumHeroHeightDp = when (base.windowClass) {
            NovaLibraryWindowClass.HANDHELD_LANDSCAPE -> if (largeText) 96 else 88
            NovaLibraryWindowClass.TV_LANDSCAPE -> if (largeText) 576 else 440
            NovaLibraryWindowClass.PHONE_PORTRAIT -> return base
        }
        val railHeightDp = minOf(
            base.stagePosterRailHeightDp,
            (heightDp - minimumHeroHeightDp).coerceAtLeast(0),
        )
        // The hero band no longer paints a scrim (NovaLibraryCinematicBackdrop owns the
        // stage gradients), so it simply reserves the space above the rail. Letting it
        // absorb the remainder keeps hero + rail exactly filling the viewport instead of
        // leaving a dead gap on tall displays.
        val heroHeightDp = (heightDp - railHeightDp).coerceAtLeast(0)
        return base.copy(
            stageHeroHeightDp = heroHeightDp,
            stagePosterRailHeightDp = railHeightDp,
            stageChromeBudgetDp = heroHeightDp + railHeightDp,
            stageUsesCompactHero =
                base.stageUsesCompactHero ||
                    heroHeightDp < base.stageHeroHeightDp ||
                    railHeightDp < base.stagePosterRailHeightDp,
        )
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
                widthDp >= 520 -> 3
                else -> 2
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
            NovaLibraryLayoutMode.STAGE -> 1
            NovaLibraryLayoutMode.GRID -> baseColumns
            NovaLibraryLayoutMode.COMPACT -> (baseColumns + 1).coerceAtMost(9)
        }
    }

    fun stageCardWidthDp(
        availableWidthDp: Int,
        isLandscape: Boolean,
        largeText: Boolean = false,
        posterColumns: Int? = null,
    ): Int {
        val columns = posterColumns ?: if (availableWidthDp >= 1280) 8 else if (largeText) 4 else 5
        return ((availableWidthDp - 24 - 16 * (columns - 1)) / columns)
            .coerceAtLeast(if (isLandscape) 96 else 84)
    }

    /**
     * End inset for the poster rail. The focused card scales up about its centre, so a flat
     * gutter left the first and last posters crowded against the screen edge. Tracks the
     * concept's 54px-on-1920 margin.
     */
    fun stageHorizontalContentPaddingDp(
        availableWidthDp: Int,
        cardWidthDp: Int
    ): Int = (availableWidthDp * 0.028f).toInt().coerceIn(8, 48)

    fun stageRestoreIndex(gameIds: List<String>, restoreGameId: String?): Int {
        if (gameIds.isEmpty() || restoreGameId == null) return 0
        return gameIds.indexOf(restoreGameId).takeIf { it >= 0 } ?: 0
    }

    fun stageSettledSelectionIndex(gameIds: List<String>, focusedGameId: String?, centeredIndex: Int?): Int? =
        focusedGameId?.let(gameIds::indexOf)?.takeIf { it >= 0 }
            ?: centeredIndex?.takeIf { it in gameIds.indices }

    fun stageFocusOwnerAfterChange(
        currentOwnerId: String?,
        gameId: String,
        isFocused: Boolean,
    ): String? = if (isFocused) gameId else currentOwnerId

    fun stageCardNeedsTextScrim(showPosterTitles: Boolean, compactRailCard: Boolean, hasMetadata: Boolean): Boolean =
        showPosterTitles || (!compactRailCard && hasMetadata)

    fun stageAdjacentIndex(currentIndex: Int, delta: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        return (currentIndex + delta).coerceIn(0, itemCount - 1)
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

    fun gridContentPaddingDp(): Int = GRID_CONTENT_PADDING_DP

    fun gridBottomContentPaddingDp(isLandscape: Boolean): Int {
        return if (isLandscape) LANDSCAPE_GRID_BOTTOM_CONTENT_PADDING_DP else GRID_CONTENT_PADDING_DP
    }

    fun gameCardHeightDp(compact: Boolean, isLandscape: Boolean): Int {
        return gameCardHeightDp(
            layoutMode = if (compact) NovaLibraryLayoutMode.COMPACT else NovaLibraryLayoutMode.GRID,
            isLandscape = isLandscape
        )
    }

    fun gameCardHeightDp(layoutMode: NovaLibraryLayoutMode, isLandscape: Boolean): Int {
        return when (layoutMode) {
            NovaLibraryLayoutMode.STAGE -> if (isLandscape) 148 else 320
            NovaLibraryLayoutMode.GRID -> if (isLandscape) 112 else 168
            NovaLibraryLayoutMode.COMPACT -> if (isLandscape) 88 else 104
        }
    }

    fun heroHeightDp(compact: Boolean): Int {
        return if (compact) 76 else 164
    }

    fun screenPaddingDp(isLandscape: Boolean): Int {
        return if (isLandscape) LANDSCAPE_SCREEN_PADDING_DP else PORTRAIT_SCREEN_PADDING_DP
    }

    fun landscapeContentSpacingDp(): Int = LANDSCAPE_CONTENT_SPACING_DP

    fun landscapeToolbarHeightDp(largeText: Boolean = false): Int = if (largeText) 74 else 60

    fun stageRailVerticalContentPaddingDp(): Int = 4

    fun landscapeStageViewportHeightDp(
        screenHeightDp: Int,
        safeVerticalInsetsDp: Int,
        largeText: Boolean = false,
    ): Int = (
        screenHeightDp -
            safeVerticalInsetsDp.coerceAtLeast(0) -
            screenPaddingDp(isLandscape = true) * 2 -
            landscapeToolbarHeightDp(largeText) -
            landscapeContentSpacingDp()
        ).coerceAtLeast(0)

    fun controllerHintBarBottomPaddingDp(isLandscape: Boolean): Int {
        return if (isLandscape) {
            LANDSCAPE_CONTROLLER_HINT_BOTTOM_PADDING_DP
        } else {
            PORTRAIT_CONTROLLER_HINT_BOTTOM_PADDING_DP
        }
    }

    fun stageControllerHintFooterHeightDp(): Int =
        STAGE_CONTROLLER_HINT_FOOTER_DP

    fun shouldRenderStageContent(
        layoutMode: NovaLibraryLayoutMode,
        filteredGamesEmpty: Boolean,
        heroReason: NovaLibraryHeroReason,
    ): Boolean = layoutMode == NovaLibraryLayoutMode.STAGE &&
        (!filteredGamesEmpty || heroReason == NovaLibraryHeroReason.ACTIVE_SESSION)

    fun shouldShowLoadFailure(
        loadErrorMessage: String?,
        allGamesEmpty: Boolean,
        heroReason: NovaLibraryHeroReason,
    ): Boolean = loadErrorMessage != null &&
        allGamesEmpty &&
        heroReason != NovaLibraryHeroReason.ACTIVE_SESSION

    fun stageFocusedGame(
        hero: NovaLibraryHeroState,
        filteredGames: List<PolarisGame>,
        restoreFocusGameId: String?,
    ): PolarisGame? {
        return filteredGames.firstOrNull { it.id == restoreFocusGameId }
            ?: hero.game
            ?: filteredGames.firstOrNull()
    }

    @Suppress("UNUSED_PARAMETER")
    fun showStandaloneHomeHero(
        layoutMode: NovaLibraryLayoutMode,
        hasActiveSession: Boolean,
    ): Boolean = layoutMode != NovaLibraryLayoutMode.STAGE

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

    fun showLandscapeControlRail(): Boolean {
        // Runtime device class detection for where the permanent landscape control rail is desirable.
        // Preserve existing call sites (no-arg function) to keep source-guard tests stable.
        // Use a JVM system property (set in test or at app runtime) to opt into device classes where
        // a permanent control rail is appropriate: e.g. 'retroid', 'tv', 'shield', 'controller'.
        val deviceClass = System.getProperty("nova.device.class")?.lowercase() ?: ""
        return when (deviceClass) {
            "retroid", "tv", "shield", "controller", "gamepad" -> true
            "phone", "tablet", "handheld", "mobile" -> false
            else -> false
        }
    }

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
