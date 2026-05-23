package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComposeSourceGuardTest {
    @Test
    fun libraryFilterSheetContentIsScrollable() {
        val filterSheet = readNovaLibraryActivity().section(
            "private fun NovaLibraryFilterSheet(",
            "private fun NovaSelectableChip("
        )

        assertTrue(
            "filter sheet should keep long source/category/genre lists reachable",
            filterSheet.contains(".verticalScroll(rememberScrollState())")
        )
    }

    @Test
    fun libraryQuickOptionsSheetExposesSortAndLayoutControls() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryFocusedBackdrop("
        )

        assertTrue(
            "library activity should keep quick options as durable Compose state",
            activity.contains("private var optionsState by mutableStateOf(NovaLibraryOptionsState())") &&
                activity.contains("private var activeOptionsSheet by mutableStateOf(false)")
        )
        assertTrue(
            "remembered library model should be keyed by options state so sort changes are cheap and deliberate",
            activity.contains("remember(games, searchQuery, filterState, activeSession, optionsState)") &&
                activity.contains("optionsState = optionsState")
        )
        assertTrue(
            "library shell should pass an explicit Options opener into rail/header actions",
            activity.contains("onOpenOptions = ::openLibraryOptionsSheet") &&
                activity.contains("onOpenOptions = onOpenOptions")
        )
        assertTrue(
            "quick options sheet should be rendered as an exclusive modal branch with source/more filter sheets",
            screen.contains("activeOptionsSheet ->") &&
                screen.contains("NovaLibraryOptionsSheet(") &&
                screen.contains("activeFilterSheet != null ->")
        )
        assertTrue(
            "quick options sheet composable should exist before source/more filter sheet",
            activity.contains("private fun NovaLibraryOptionsSheet(")
        )
        val optionsSheet = activity.section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )
        assertTrue(
            "quick options sheet should use a real modal sheet with scrollable focusable content",
            optionsSheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)") &&
                optionsSheet.contains(".verticalScroll(rememberScrollState())") &&
                optionsSheet.contains("NovaLibrarySortMode.entries") &&
                optionsSheet.contains("NovaLibraryLayoutMode.entries")
        )
        assertTrue(
            "quick options sheet should expose Sort and Layout sections rather than hiding browsing decisions in the rail",
            optionsSheet.contains("R.string.nova_library_options_sort_title") &&
                optionsSheet.contains("R.string.nova_library_options_layout_title") &&
                optionsSheet.contains("onSortMode(sortMode)") &&
                optionsSheet.contains("onLayoutMode(layoutMode)")
        )
        assertTrue(
            "compact grid should be wired into the actual library card density",
            activity.contains("model.optionsState.layoutMode == NovaLibraryLayoutMode.COMPACT_GRID") &&
                activity.contains("compact = compactCards")
        )
        assertTrue(
            "quick options strings should cover the GameNative-inspired Sort/Layout surface",
            strings.contains("name=\"nova_library_options_title\">Library Options") &&
                strings.contains("name=\"nova_library_options_sort_recent\">Recent") &&
                strings.contains("name=\"nova_library_options_sort_name_asc\">Name A-Z") &&
                strings.contains("name=\"nova_library_options_sort_name_desc\">Name Z-A") &&
                strings.contains("name=\"nova_library_options_sort_source\">Source") &&
                strings.contains("name=\"nova_library_options_sort_hdr_first\">HDR first") &&
                strings.contains("name=\"nova_library_options_layout_compact_grid\">Compact grid")
        )
    }

    @Test
    fun librarySystemMenuSheetExposesTopLevelSafeActions() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryFocusedBackdrop("
        )
        val keyHandler = activity.section(
            "override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {",
            "override fun onStop()"
        )

        assertTrue(
            "system menu sheet composable should exist before browsing option/filter sheets",
            activity.contains("private fun NovaSystemMenuSheet(")
        )
        val systemSheet = activity.section(
            "private fun NovaSystemMenuSheet(",
            "private fun NovaLibraryOptionsSheet("
        )

        assertTrue(
            "library activity should keep the Nova system menu as durable Compose state",
            activity.contains("private var activeSystemMenu by mutableStateOf(false)") &&
                activity.contains("onOpenSystemMenu = ::openLibrarySystemMenu") &&
                activity.contains("onDismissSystemMenu = ::dismissLibrarySystemMenu") &&
                activity.contains("private fun openLibrarySystemMenu()") &&
                activity.contains("private fun dismissLibrarySystemMenu()")
        )
        assertTrue(
            "library screen should render only one top-level modal at a time so sheets cannot stack",
            screen.contains("when {") &&
                screen.contains("activeSystemMenu ->") &&
                screen.contains("NovaSystemMenuSheet(") &&
                screen.contains("activeOptionsSheet ->") &&
                screen.contains("NovaLibraryOptionsSheet(") &&
                screen.contains("activeFilterSheet != null ->") &&
                screen.contains("NovaLibraryFilterSheet(")
        )
        assertTrue(
            "system menu should dismiss via modal onDismissRequest so Back/B and scrim close it before leaving the library",
            systemSheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)") &&
                systemSheet.contains("onDismissRequest = onDismiss") &&
                systemSheet.contains(".verticalScroll(rememberScrollState())") &&
                activity.contains("dismissActiveLibraryOverlay()") &&
                keyHandler.contains("keyCode == KeyEvent.KEYCODE_BUTTON_B && dismissActiveLibraryOverlay()")
        )
        assertTrue(
            "system menu should clear options/filter overlays on open and gate rail shortcuts while any modal is active",
            activity.contains("private val hasActiveLibraryOverlay") &&
                activity.contains("activeOptionsSheet = false") &&
                activity.contains("activeFilterSheet = null") &&
                keyHandler.contains("if (hasActiveLibraryOverlay)") &&
                keyHandler.contains("KeyEvent.KEYCODE_BUTTON_L1") &&
                keyHandler.contains("KeyEvent.KEYCODE_BUTTON_R1") &&
                keyHandler.contains("openLibrarySystemMenu()")
        )
        assertTrue(
            "system menu should show the active host and Polaris readiness in the header",
            systemSheet.contains("serverDisplayName") &&
                systemSheet.contains("R.string.nova_system_menu_host_format") &&
                systemSheet.contains("R.string.nova_system_menu_host_named_format") &&
                systemSheet.contains("R.string.nova_system_menu_status_polaris_ready") &&
                systemSheet.contains("R.string.nova_system_menu_status_offline")
        )
        assertTrue(
            "system menu should expose the short top-level Nova actions only",
            systemSheet.contains("R.string.nova_system_menu_settings") &&
                systemSheet.contains("R.string.nova_system_menu_polaris_sync") &&
                systemSheet.contains("R.string.nova_system_menu_manage_server") &&
                systemSheet.contains("R.string.nova_system_menu_help_diagnostics") &&
                systemSheet.contains("R.string.nova_system_menu_about")
        )
        assertTrue(
            "system rows should route to existing workflows and dismiss before launching secondary surfaces",
            systemSheet.contains("onOpenSettings") &&
                systemSheet.contains("onOpenPolarisSync") &&
                systemSheet.contains("onManageServer") &&
                systemSheet.contains("onOpenHelpDiagnostics") &&
                systemSheet.contains("onOpenAbout") &&
                systemSheet.contains("onDismiss()") &&
                systemSheet.contains("role = Role.Button") &&
                systemSheet.contains("semantics(mergeDescendants = true)")
        )
        assertTrue(
            "system menu rows should stay compact enough for all safe actions to fit on Retroid landscape first paint",
            systemSheet.contains("verticalArrangement = Arrangement.spacedBy(8.dp)") &&
                systemSheet.contains("fontSize = 20.sp") &&
                systemSheet.contains(".height(54.dp)") &&
                systemSheet.contains("fontSize = 14.sp") &&
                systemSheet.contains("fontSize = 10.sp")
        )
        assertFalse(
            "system menu should not waste first-paint height on non-action footer copy that clips on Retroid landscape",
            systemSheet.contains("R.string.nova_system_menu_safe_hint")
        )
        assertFalse(
            "destructive stream/session actions should stay out of the top-level system menu",
            systemSheet.contains("onEndSession") ||
                systemSheet.contains("displayQuitConfirmationDialog") ||
                systemSheet.contains("ServerHelper.doQuit")
        )
        assertTrue(
            "system menu strings should keep the GameNative-inspired top level short and self-hosted",
            strings.contains("name=\"nova_system_menu_title\">System") &&
                strings.contains("name=\"nova_system_menu_host_named_format\"") &&
                strings.contains("name=\"nova_system_menu_settings\">Settings") &&
                strings.contains("name=\"nova_system_menu_polaris_sync\">Polaris sync") &&
                strings.contains("name=\"nova_system_menu_manage_server\">Manage server") &&
                strings.contains("name=\"nova_system_menu_help_diagnostics\">Help / diagnostics") &&
                strings.contains("name=\"nova_system_menu_about\">About Nova") &&
                strings.contains("name=\"nova_system_menu_about_toast\">%1\$s")
        )
    }

    @Test
    fun libraryCoverLoadingIsKeyedOutsideAndroidViewUpdate() {
        val source = readNovaLibraryActivity()

        assertTrue(
            "cover view should be keyed by the game cover identity",
            source.contains("key(game.id, game.coverUrl)")
        )
        assertTrue(
            "cover load should happen when the keyed ImageView is created",
            source.contains("apiClient.loadCoverInto(this, game)")
        )
        assertFalse(
            "cover load should not be restarted from AndroidView.update on focus recomposition",
            source.contains("update = { imageView ->\n                    apiClient.loadCoverInto(imageView, game)")
        )
    }

    @Test
    fun libraryFocusedBackdropUsesFallbackArtworkCandidates() {
        val source = readNovaLibraryActivity()
        val screen = source.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryFocusedBackdrop("
        )
        val backdrop = source.section(
            "private fun NovaLibraryFocusedBackdrop(",
            "private fun NovaLibraryRail("
        )

        assertTrue(
            "focused backdrop remember keys should include the hero game so active-session backdrop changes are not stale",
            screen.contains("remember(\n            model.filteredGames,\n            model.recentGames,\n            model.allGames,\n            model.hero.game,")
        )
        assertTrue(
            "focused backdrop should fall back to visible library artwork before remaining blank",
            screen.contains("model.hero.game") &&
                screen.contains("model.filteredGames.firstOrNull()") &&
                screen.contains("model.recentGames.firstOrNull()")
        )
        assertTrue(
            "focused backdrop should let the cover loader use its game-id fallback when coverUrl is blank",
            backdrop.contains("val artworkGame = game") &&
                backdrop.contains("apiClient.loadCoverInto(this, targetGame)")
        )
        assertFalse(
            "focused backdrop should not hide artwork only because Polaris omitted coverUrl",
            backdrop.contains("coverUrl.trim().isNotEmpty()")
        )
    }

    @Test
    fun libraryUiModelMappingIsRememberedAcrossUnrelatedRecompositions() {
        val source = readNovaLibraryActivity()
        val setContent = source.section(
            "setContent {",
            "NovaLibraryScreen("
        )
        val helperStart = source.indexOf("private fun rememberNovaLibraryUiModel(")

        assertTrue(
            "library screen should use a remembered model helper",
            helperStart >= 0
        )

        val rememberedModel = source.section(
            "private fun rememberNovaLibraryUiModel(",
            "@Composable\n    private fun NovaLibraryScreen("
        )

        assertTrue(
            "library model mapping should be keyed to the data that affects filtering, options, and session hero state",
            rememberedModel.contains("remember(games, searchQuery, filterState, activeSession, optionsState)")
        )
        assertTrue(
            "remembered model helper should own the mapper call",
            rememberedModel.contains("NovaLibraryUiStateMapper.build(")
        )
        assertFalse(
            "setContent should not rebuild library filtering/sorting for unrelated state changes",
            setContent.contains("NovaLibraryUiStateMapper.build(")
        )
    }

    @Test
    fun libraryHomeHeroIsRenderedBeforeRowsAndKeepsControllerActionsFocused() {
        val source = readNovaLibraryActivity()
        val screen = source.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryFocusedBackdrop("
        )
        val hero = source.section(
            "private fun NovaLibraryHomeHero(",
            "private fun NovaLibraryRail("
        )

        val landscape = screen.section("if (isLandscape) {", "} else {")

        assertTrue(
            "landscape library should promote the hero before grid content so the picker remains visible on Retroid",
            landscape.indexOf("NovaLibraryHomeHero(") in 0 until landscape.indexOf("NovaLibraryContent(")
        )
        assertTrue(
            "landscape library should restore the recent rail after picker/grid content, not between hero and picker",
            landscape.indexOf("NovaLibraryContent(") in 0 until landscape.indexOf("NovaLibraryRecentRail(")
        )
        assertTrue(
            "landscape recent rail should ask the mapper whether the hero already owns resume/continue instead of duplicating it",
            screen.contains("NovaLibraryUiStateMapper.showLandscapeRecentRail(") &&
                screen.contains("heroReason = model.hero.reason") &&
                screen.contains("recentCount = model.recentGames.size")
        )
        assertTrue(
            "hero should use the mapped model hero state instead of recomputing presentation copy",
            screen.contains("hero = model.hero")
        )
        assertTrue(
            "hero should expose the game-launcher primary action through NovaActionButton",
            hero.contains("NovaActionButton(") && hero.contains("text = hero.actionLabel")
        )
        assertTrue(
            "hero should render the mapped caption so filtered, recent, active, and empty states explain the CTA",
            hero.contains("text = hero.caption")
        )
        assertTrue(
            "hero height should be mapper-driven so Retroid landscape can shrink the resume surface without source spelunking",
            hero.contains("val height = NovaLibraryUiStateMapper.heroHeightDp(compact = compact).dp")
        )
        assertTrue(
            "hero caption stack should use tighter vertical spacing to avoid clipped badge rows",
            hero.contains("Arrangement.spacedBy(if (compact) 1.dp else 5.dp)")
        )
        assertTrue(
            "compact hero should use tighter padding so the continue strip gives vertical room back to the grid",
            hero.contains(".padding(if (compact) 8.dp else 16.dp)")
        )
        assertTrue(
            "hero text stack should declare compact line heights so captions do not inherit oversized body metrics",
            hero.contains("lineHeight = if (compact) 11.sp else 14.sp") &&
                hero.contains("lineHeight = if (compact) 22.sp else 34.sp") &&
                hero.contains("lineHeight = if (compact) 13.sp else 16.sp") &&
                hero.contains("lineHeight = if (compact) 13.sp else 15.sp")
        )
        assertTrue(
            "compact hero should hide secondary subtitle/caption/badges so the silhouette actually changes on Retroid",
            hero.contains("if (!compact) {") &&
                hero.contains("text = hero.subtitle") &&
                hero.contains("if (showCaption) {") &&
                hero.contains("if (!compact && hero.badges.isNotEmpty())")
        )
        assertTrue(
            "hero card itself should activate the same primary action when D-pad focus lands on the container",
            hero.contains(".combinedClickable(onClick = onPrimaryAction)") &&
                hero.indexOf(".combinedClickable(onClick = onPrimaryAction)") in 0 until hero.indexOf(".focusable()")
        )
        assertTrue(
            "hero focus should update the focused backdrop/focus restore model for D-pad users",
            hero.contains("onGameFocused(heroGame)")
        )
    }

    @Test
    fun libraryEmptyAndOfflineRecoveryStatesUseDeliberateCtas() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val emptyState = activity.section(
            "private fun NovaLibraryEmptyState(",
            "@Composable\n    private fun NovaLibraryErrorState("
        )
        val errorState = activity.section(
            "private fun NovaLibraryErrorState(",
            "@Composable\n    private fun NovaLibraryRecoveryState("
        )

        assertTrue(
            "default no-games empty state should make Manage library the primary recovery action",
            emptyState.contains("NovaLibraryEmptyState.DEFAULT -> stringResource(R.string.nova_library_empty_action_manage)")
        )
        assertTrue(
            "empty library primary CTA copy should point to library management, not generic server settings",
            strings.contains("name=\"nova_library_empty_action_manage\">Manage library")
        )
        assertTrue(
            "recent-empty state should invite users back to the full library instead of sounding like an error",
            emptyState.contains("NovaLibraryEmptyState.RECENT -> stringResource(R.string.nova_library_empty_action_recent)") &&
                strings.contains("name=\"nova_library_empty_action_recent\">View all games")
        )
        assertTrue(
            "source no-results should have a source-specific direct escape hatch",
            emptyState.contains("NovaLibraryEmptyState.SOURCE -> stringResource(R.string.nova_library_empty_action_source)") &&
                strings.contains("name=\"nova_library_empty_action_source\">Clear source")
        )
        assertTrue(
            "source no-results should keep Manage library as the secondary recovery action",
            emptyState.contains("emptyState == NovaLibraryEmptyState.SOURCE") &&
                emptyState.contains("secondaryActionLabel = sourceSecondaryActionLabel") &&
                emptyState.contains("onSecondaryAction = sourceSecondaryAction")
        )
        assertTrue(
            "filtered empty state should keep Clear filters as the direct escape hatch",
            emptyState.contains("NovaLibraryEmptyState.FILTERED -> stringResource(R.string.nova_library_empty_action_clear)")
        )
        assertTrue(
            "offline/load failure recovery should offer Retry first and Manage server second",
            errorState.indexOf("text = stringResource(R.string.nova_retry)") in 0 until
                errorState.indexOf("text = stringResource(R.string.nova_library_error_action_manage)")
        )
    }

    @Test
    fun librarySearchDoesNotEnterTextInputOnDpadFocus() {
        val searchField = readNovaLibraryActivity().section(
            "private fun NovaSearchField(",
            "private fun NovaFilterChip("
        )

        assertTrue(
            "search should keep a browse mode before explicitly editing text",
            searchField.contains("var searchEditing by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "search should not show the IME just because D-pad focus lands on it",
            searchField.contains("readOnly = !searchEditing")
        )
        assertTrue(
            "search should handle D-pad keys before the IME traps navigation",
            searchField.contains(".onPreviewKeyEvent")
        )
        assertTrue(
            "search should hide the keyboard when D-pad navigation leaves edit mode",
            searchField.contains("keyboardController?.hide()")
        )
        assertTrue(
            "search should move focus down out of the field instead of trapping D-pad input",
            searchField.contains("Key.DirectionDown -> leaveSearchEditing(FocusDirection.Down)")
        )
        assertFalse(
            "search should not wait for edit mode before releasing D-pad navigation",
            searchField.contains("Key.DirectionDown -> if (searchEditing)")
        )
        assertTrue(
            "controller select should explicitly enter search edit mode on TV remotes",
            searchField.contains("Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->") &&
                searchField.contains("beginSearchEditing()")
        )
        assertFalse(
            "controller select should not be swallowed without activating search",
            searchField.contains("Key.DirectionCenter -> true")
        )
    }

    @Test
    fun libraryRailKeepsDpadTraversalInsideRail() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )

        assertTrue(
            "side rail should be a focus group so vertical D-pad traversal stays in the rail",
            rail.contains(".focusGroup()")
        )
    }

    @Test
    fun libraryRailKeepsBottomFiltersScrollableAboveSafeArea() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )

        assertTrue(
            "side rail scroll content should include bottom safe-area padding so the final filter can be scrolled fully above the gesture/nav area",
            rail.contains(".verticalScroll(rememberScrollState())") &&
                rail.contains("bottom = NovaLibraryUiStateMapper.railScrollBottomPaddingDp().dp")
        )
    }

    @Test
    fun libraryRailUsesCompactSpacingSoBottomFiltersFitRetroidLandscape() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )

        assertTrue(
            "side rail should use compact mapped spacing so bottom filters are initially visible on Retroid landscape instead of only scroll-recoverable",
            rail.contains("verticalArrangement = Arrangement.spacedBy(NovaLibraryUiStateMapper.railVerticalSpacingDp().dp)")
        )
    }

    @Test
    fun libraryRailUsesCompactPrimaryFilterGridOnRetroidLandscape() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )

        assertTrue(
            "side rail should render primary filters in a compact mapper-driven grid so All/Recent/Sources/HDR/More are initially visible on Retroid landscape",
            rail.contains("NovaLibraryPrimaryFilterGrid(") &&
                rail.contains("NovaLibraryUiStateMapper.railFilterColumns(maxWidth.value.toInt())") &&
                rail.contains("Arrangement.spacedBy(NovaLibraryUiStateMapper.railFilterGridSpacingDp().dp)") &&
                rail.contains("NovaLibrarySummary(model = model, compact = true)")
        )
    }

    @Test
    fun libraryRailUsesSingleRowActionsBeforePrimaryFiltersOnRetroidLandscape() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )

        assertTrue(
            "Refresh/Manage/Switch should share one compact mapper-driven row on Retroid landscape so the primary filter grid is not pushed below the fold",
            rail.contains("NovaLibraryRailActions(") &&
                rail.contains("NovaLibraryUiStateMapper.railActionColumns(maxWidth.value.toInt())") &&
                rail.contains("NovaLibraryUiStateMapper.railActionButtonMinHeightDp().dp")
        )
    }

    @Test
    fun libraryRestoresLastFocusedGameAndFilter() {
        val source = readNovaLibraryActivity()
        val gameCard = source.section(
            "private fun NovaLibraryGameCard(",
            "private fun NovaMiniBadge("
        )
        val filterChip = source.section(
            "private fun NovaSelectableChip(",
            "private fun NovaLibraryPanel("
        )

        assertTrue(
            "library should keep last focused game id in activity state for detail-sheet returns",
            source.contains("private var lastFocusedGameId by mutableStateOf<String?>(null)") &&
                source.contains("onGameFocused = { lastFocusedGameId = it.id }")
        )
        assertTrue(
            "library should keep last focused primary filter for rail/top-header traversal",
            source.contains("private var lastFocusedPrimaryFilter by mutableStateOf(NovaLibraryPrimaryFilter.ALL)") &&
                source.contains("onPrimaryFilterFocused = { lastFocusedPrimaryFilter = it }")
        )
        assertTrue(
            "game cards should request focus when they match the remembered game",
            gameCard.contains("val focusRequester = remember { FocusRequester() }") &&
                gameCard.contains(".focusRequester(focusRequester)") &&
                gameCard.contains("if (restoreFocus && !restoreAttempted)")
        )
        assertTrue(
            "filter chips should request focus when they match the remembered filter",
            filterChip.contains("val focusRequester = remember { FocusRequester() }") &&
                filterChip.contains(".focusRequester(focusRequester)") &&
                filterChip.contains("if (restoreFocus && !restoreAttempted)")
        )
    }

    @Test
    fun libraryGameCardsUseHighVisibilityFocusedFrame() {
        val gameCard = readNovaLibraryActivity().section(
            "private fun NovaLibraryGameCard(",
            "private fun NovaMiniBadge("
        )

        assertTrue(
            "focused library cards should use a thicker outer focus border",
            gameCard.contains("width = if (focused) 3.dp else 1.dp")
        )
        assertTrue(
            "focused library cards should draw a foreground focus frame above cover art",
            gameCard.contains(".border(4.dp, surfaces.focusRing, RoundedCornerShape(14.dp))")
        )
        assertTrue(
            "focused library cards should add an accent wash so the selected cover is visible on busy art",
            gameCard.contains(".background(surfaces.focusHalo.copy(alpha = 0.28f))")
        )
        assertTrue(
            "focused library cards should include a bright inner ring for contrast",
            gameCard.contains(".border(2.dp, colors.onAccent.copy(alpha = 0.82f), RoundedCornerShape(10.dp))")
        )
        assertTrue(
            "focused library cards should expose a compact action affordance",
            gameCard.contains("R.string.nova_library_card_action_details") &&
                gameCard.contains(".align(Alignment.TopEnd)")
        )
        assertTrue(
            "focused library cards should observe the same focus target that receives D-pad focus",
            gameCard.indexOf(".onFocusChanged {") in 0 until
                gameCard.indexOf(".combinedClickable(")
        )
    }

    @Test
    fun libraryGameCardHeightsComeFromSharedSizingRules() {
        val gameCard = readNovaLibraryActivity().section(
            "private fun NovaLibraryGameCard(",
            "private fun NovaMiniBadge("
        )
        val loadingCard = readNovaLibraryActivity().section(
            "private fun NovaLoadingCard(",
            "private fun NovaLibraryEmptyState("
        )

        assertTrue(
            "library game cards should use shared smaller sizing rules",
            gameCard.contains("NovaLibraryUiStateMapper.gameCardHeightDp(compact = compact, isLandscape = isLandscape).dp")
        )
        assertTrue(
            "library loading cards should match the same default card sizing",
            loadingCard.contains("NovaLibraryUiStateMapper.gameCardHeightDp(compact = false, isLandscape = isLandscape).dp")
        )
        assertFalse(
            "library game cards should not keep the old large default heights inline",
            gameCard.contains("isLandscape -> 164.dp") || gameCard.contains("else -> 184.dp")
        )
    }

    @Test
    fun libraryMiniBadgesStaySmallOnDenseCards() {
        val miniBadge = readNovaLibraryActivity().section(
            "private fun NovaMiniBadge(",
            "@Composable\n    private fun NovaLibraryLoadingGrid("
        )

        assertTrue(
            "library card badges should use compact text",
            miniBadge.contains("fontSize = 9.sp")
        )
        assertTrue(
            "library card badges should pin a compact line height",
            miniBadge.contains("lineHeight = 10.sp")
        )
        assertTrue(
            "library card badges should use tighter pill padding",
            miniBadge.contains(".padding(horizontal = 6.dp, vertical = 2.dp)")
        )
    }

    @Test
    fun activeSessionCardStaysCompactAndShowsStreamContext() {
        val activeSession = readNovaLibraryActivity().section(
            "private fun NovaLibraryActiveSessionCard(",
            "private fun NovaLibrarySummary("
        )

        assertTrue(
            "active session card should include the stream profile when Polaris exposes it",
            activeSession.contains("val streamDetail = formatStreamProfile(session)")
        )
        assertTrue(
            "active session card should keep its primary action compact in the rail",
            activeSession.contains("minHeight = 34.dp") &&
                activeSession.contains("fontSize = 11.sp")
        )
    }

    @Test
    fun libraryShowsPolarisAwareStatusStripBeforeCounts() {
        val source = readNovaLibraryActivity()
        val rail = source.section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )
        val topHeader = source.section(
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryTitle("
        )
        val hasStatusStrip = source.contains("private fun NovaLibraryStatusStrip(")
        val statusStrip = if (hasStatusStrip) {
            source.section(
                "private fun NovaLibraryStatusStrip(",
                "private fun NovaLibraryActiveSessionCard("
            )
        } else {
            ""
        }

        assertTrue(
            "library should render a named Polaris-aware status strip instead of the old generic status row",
            hasStatusStrip && !source.contains("private fun NovaLibraryStatus(")
        )
        assertTrue(
            "status strip should sit before numeric summary counts in both Retroid rail and phone header",
            rail.indexOf("NovaLibraryStatusStrip(") in 0 until rail.indexOf("NovaLibrarySummary(") &&
                topHeader.indexOf("NovaLibraryStatusStrip(") in 0 until topHeader.indexOf("NovaLibrarySummary(")
        )
        assertTrue(
            "status strip should make Polaris readiness, launch mode, and resumable session state visible",
            statusStrip.contains("R.string.nova_library_polaris_ready") &&
                statusStrip.contains("R.string.nova_library_polaris_checking") &&
                statusStrip.contains("R.string.nova_library_resume_ready") &&
                statusStrip.contains("activeSession != null")
        )
        assertTrue(
            "status strip should use compact launch-mode labels so the Retroid rail does not clip the third pill",
            statusStrip.contains("compactStatusModeLabel(settings)") &&
                source.contains("private fun compactStatusModeLabel(")
        )
    }

    @Test
    fun libraryFiltersExposeClearActionWhenNarrowed() {
        val source = readNovaLibraryActivity()
        val rail = source.section(
            "private fun NovaLibraryRail(",
            "private fun NovaLibraryTopHeader("
        )
        val topHeader = source.section(
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryTitle("
        )

        assertTrue(
            "library should compute a clearable state from search plus filter constraints",
            source.contains("private fun hasClearableFilters(") &&
                source.contains("searchQuery.isNotBlank() || filterState.hasActiveConstraint")
        )
        assertTrue(
            "landscape rail should show a clear filters action when filters/search are active",
            rail.contains("if (hasClearableFilters(searchQuery, filterState))") &&
                rail.contains("R.string.nova_library_filter_clear_all")
        )
        assertTrue(
            "portrait header should show the same clear filters action",
            topHeader.contains("if (hasClearableFilters(searchQuery, filterState))") &&
                topHeader.contains("R.string.nova_library_filter_clear_all")
        )
    }

    @Test
    fun gameDetailLaunchControlsPrioritizePrimaryPlayFocus() {
        val detail = readNovaGameDetailSheet()
        val launchControls = detail.section(
            "private fun LaunchControls(",
            "@Composable\nprivate fun LaunchProfileSummaryInline("
        )

        assertTrue(
            "game detail should focus the primary play action when the sheet opens",
            launchControls.contains("val playFocusRequester = remember { FocusRequester() }") &&
                launchControls.contains("playFocusRequester.requestFocus()") &&
                launchControls.contains(".focusRequester(playFocusRequester)")
        )
        assertTrue(
            "game detail should make Play the full-width primary action before secondary tuning actions",
            launchControls.section("text = playLabel", "enabled = uiState.playEnabled")
                .contains(".fillMaxWidth()\n                .focusRequester(playFocusRequester)") &&
                launchControls.indexOf("text = playLabel") < launchControls.indexOf("text = profilePreferenceLabel")
        )
        assertTrue(
            "game detail should keep Play above mode choices and the longer launch profile summary",
            launchControls.indexOf("text = playLabel") < launchControls.indexOf("LaunchModeChoicePill(") &&
                launchControls.indexOf("text = playLabel") < launchControls.indexOf("LaunchProfileSummaryInline(")
        )
    }

    @Test
    fun gameDetailLaunchModeUsesSingleInlineSelectorInsteadOfDuplicateOptionsDrawer() {
        val detail = readNovaGameDetailSheet()
        val launchControls = detail.section(
            "private fun LaunchControls(",
            "@Composable\nprivate fun LaunchProfileSummaryInline("
        )

        assertFalse(
            "launch mode should not duplicate Headless/Virtual choices in a separate Launch Options drawer",
            detail.contains("private fun showLaunchOptions(") ||
                detail.contains("onLaunchOptions = {") ||
                launchControls.contains("text = launchOptionsLabel")
        )
        assertTrue(
            "Headless/Virtual should be directly selectable from the detail sheet before launching",
            launchControls.contains("LaunchModeChoicePill(") &&
                launchControls.contains("onClick = { onLaunchModeSelected(\"headless\") }") &&
                launchControls.contains("onClick = { onLaunchModeSelected(\"virtual_display\") }")
        )
        assertTrue(
            "non-duplicative tuning should remain available separately from launch mode selection",
            launchControls.contains("text = profilePreferenceLabel") &&
                launchControls.indexOf("text = profilePreferenceLabel") > launchControls.indexOf("LaunchModeChoicePill(")
        )
    }

    @Test
    fun gameDetailKeepsMangoHudOutOfPrimaryLaunchDrawer() {
        val detail = readNovaGameDetailSheet()
        val sheetContent = detail.section(
            "fun NovaGameDetailSheetContent(",
            "@Composable\nprivate fun NovaDetailPanel("
        )

        assertFalse(
            "MangoHUD should not render as a prominent switch card in the main launch drawer",
            sheetContent.contains("MangoHudCard(")
        )
        assertTrue(
            "when MangoHUD is already enabled, the drawer should show only a passive status after launch controls",
            sheetContent.contains("if (mangoHudEnabled) {") &&
                sheetContent.contains("MangoHudPassiveStatus(") &&
                sheetContent.indexOf("MangoHudPassiveStatus(") > sheetContent.indexOf("LaunchControlsPanel(")
        )
    }

    @Test
    fun gameDetailCoverLoadingIsKeyedByGameIdentity() {
        val detailsPanel = readNovaGameDetailSheet().section(
            "private fun GameDetailsPanel(",
            "@Composable\nprivate fun LaunchControlsPanel("
        )

        assertTrue(
            "detail sheet cover view should be keyed by game identity so reused panels do not show stale artwork",
            detailsPanel.contains("key(game.id, game.coverUrl)") &&
                detailsPanel.contains("coverLoader(this)")
        )
    }

    @Test
    fun libraryLoadErrorsUsePersistentRetryState() {
        val source = readNovaLibraryActivity()
        val content = source.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )

        assertTrue(
            "library load errors should be stored in state instead of only a transient toast",
            source.contains("private var loadErrorMessage by mutableStateOf<String?>(null)")
        )
        assertTrue(
            "library content should render a persistent retryable error state when no games loaded",
            content.contains("loadErrorMessage != null && model.allGames.isEmpty()") &&
                content.contains("NovaLibraryErrorState(")
        )
        assertTrue(
            "library error state should use the shared retry action",
            source.contains("private fun NovaLibraryErrorState(") &&
                source.contains("text = stringResource(R.string.nova_retry)")
        )
    }

    @Test
    fun libraryLazyContainersDeclareStableContentTypes() {
        val source = readNovaLibraryActivity()

        assertTrue(
            "main library grid games should declare a stable content type",
            source.containsRegex(
                """items\s*\(\s*model\.filteredGames\s*,[\s\S]*?contentType\s*=\s*\{\s*"library-game"\s*\}\s*\)\s*\{"""
            )
        )
        assertTrue(
            "recent rail games should declare a stable content type",
            source.containsRegex(
                """items\s*\(\s*games\s*,[\s\S]*?contentType\s*=\s*\{\s*"recent-game"\s*\}\s*\)\s*\{"""
            )
        )
        assertTrue(
            "loading grid placeholders should declare a stable content type",
            source.containsRegex(
                """items\s*\(\s*12\s*,[\s\S]*?contentType\s*=\s*\{\s*"loading-card"\s*\}\s*\)\s*\{"""
            )
        )
    }

    @Test
    fun libraryRecentRailTargetsFourVisibleCards() {
        val rail = readNovaLibraryActivity().section(
            "private fun NovaLibraryRecentRail(",
            "private fun NovaLibraryGameCard("
        )

        assertTrue(
            "continue rail should calculate card width from the available row width",
            rail.contains("BoxWithConstraints(") &&
                rail.contains("NovaLibraryUiStateMapper.recentRailCardWidthDp")
        )
        assertTrue(
            "continue rail should target four visible game columns",
            rail.contains("NovaLibraryUiStateMapper.RECENT_RAIL_VISIBLE_COLUMNS")
        )
        assertFalse(
            "continue rail should not keep the old oversized fixed game card width",
            rail.contains("Modifier.width(176.dp)")
        )
    }

    @Test
    fun sharedComposeFocusControlsUseHighContrastTreatment() {
        val focusComponents = readNovaFocusComponents()
        val actionButton = focusComponents.substring(focusComponents.indexOf("fun NovaActionButton("))
        val selectableChip = readNovaLibraryActivity().section(
            "private fun NovaSelectableChip(",
            "private fun NovaLibraryPanel("
        )

        assertTrue(
            "action buttons should reserve a stronger focused outline",
            actionButton.contains("focused -> 3.dp")
        )
        assertTrue(
            "action buttons should use the focused control surface on D-pad focus",
            actionButton.contains("focused -> surfaces.selectedControl")
        )
        assertTrue(
            "selectable chips should use the same 3dp focused outline",
            selectableChip.contains(".border(if (focused) 3.dp else 1.dp")
        )
        assertTrue(
            "selectable chips should visibly fill on focus even when not selected",
            selectableChip.contains("focused -> surfaces.selectedControl")
        )
        assertTrue(
            "selectable chips should observe the focus target that receives D-pad focus",
            selectableChip.indexOf(".onFocusChanged {") in 0 until
                selectableChip.indexOf(".combinedClickable(")
        )
        assertTrue(
            "selectable chips should expose one merged button semantics node so clipped child text never becomes the accessibility target",
            selectableChip.contains(".semantics(mergeDescendants = true)") &&
                selectableChip.contains("val chipDescription = \"\$label. \$detail\"") &&
                selectableChip.contains("contentDescription = chipDescription") &&
                selectableChip.contains("role = Role.Button") &&
                selectableChip.contains(".combinedClickable(")
        )
        assertTrue(
            "shared Compose focus controls should use the Nova focus motion modifier",
            focusComponents.contains("internal fun Modifier.novaFocusMotion(") &&
                focusComponents.contains("animateFloatAsState(") &&
                focusComponents.contains("NovaFocusMotionSpec.ButtonPressedScale") &&
                actionButton.contains(".novaFocusMotion(")
        )
    }

    @Test
    fun settingsRowsUseSharedFocusMotionAndHighContrastOutline() {
        val settings = readNovaSettingsScreen()
        val searchField = settings.section(
            "private fun NovaSettingsSearchField(",
            "@Composable\nprivate fun SearchResultSummary("
        )
        val quickPill = settings.section(
            "private fun NovaSettingPill(",
            "@Composable\nprivate fun NovaSettingsCategoryRail("
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaSettingsRows("
        )
        val settingRow = settings.section(
            "private fun NovaSettingRow(",
            "@Composable\nprivate fun NovaSettingApplyBadge("
        )

        listOf(searchField, quickPill, categoryRow, settingRow).forEach { section ->
            assertTrue(
                "settings focus surfaces should use Nova focus motion",
                section.contains(".novaFocusMotion(focused = focused, pressed = false)")
            )
            assertTrue(
                "settings focus surfaces should match the stronger 3dp controller outline",
                section.contains(".border(if (focused) 3.dp else 1.dp")
            )
        }
    }

    @Test
    fun settingsWideLayoutUsesRetroidCompactHierarchyMetrics() {
        val settings = readNovaSettingsScreen()
        val content = settings.section(
            "private fun NovaSettingsContent(",
            "@Composable\nprivate fun novaSettingsControllerHints()"
        )
        val quickStrip = settings.section(
            "private fun NovaSettingsQuickStrip(",
            "@Composable\nprivate fun NovaSettingPill("
        )
        val quickPill = settings.section(
            "private fun NovaSettingPill(",
            "@Composable\nprivate fun NovaSettingsCategoryRail("
        )
        val categoryRail = settings.section(
            "private fun NovaSettingsCategoryRail(",
            "@Composable\nprivate fun NovaSettingsCategoryChips("
        )
        val categoryRow = settings.section(
            "private fun NovaCategoryRow(",
            "@Composable\nprivate fun NovaSettingsRows("
        )
        val rows = settings.section(
            "private fun NovaSettingsRows(",
            "@Composable\nprivate fun NovaSettingRow("
        )
        val settingRow = settings.section(
            "private fun NovaSettingRow(",
            "@Composable\nprivate fun NovaSettingApplyBadge("
        )
        val applyBadge = settings.section(
            "private fun NovaSettingApplyBadge(",
            "@Composable\nprivate fun NovaSettingValueChip("
        )
        val valueChip = settings.section(
            "private fun NovaSettingValueChip(",
            "@Composable\nprivate fun NovaSettingDialog("
        )

        assertTrue(
            "settings should centralize Retroid landscape sizing knobs instead of scattering magic dp constants",
            settings.contains("private object NovaSettingsMetrics") &&
                settings.contains("fun categoryRailWidthDp(): Int = 196") &&
                settings.contains("fun wideColumnSpacingDp(): Int = 14") &&
                settings.contains("fun quickStripHeightDp(): Int = 52") &&
                settings.contains("fun quickPillWidthDp(): Int = 144") &&
                settings.contains("fun headerToQuickStripSpacingDp(): Int = 6") &&
                settings.contains("fun quickStripToContentSpacingDp(): Int = 6") &&
                settings.contains("fun contentToHintSpacingDp(): Int = 4") &&
                settings.contains("fun settingsRowSpacingDp(): Int = 6") &&
                settings.contains("fun categoryRowVerticalPaddingDp(): Int = 6") &&
                settings.contains("fun settingsRowVerticalPaddingDp(): Int = 6") &&
                settings.contains("fun valueChipMinHeightDp(): Int = 28")
        )
        assertTrue(
            "wide Settings should give browsing rows more room by narrowing the category rail and spacing",
            content.contains(".width(NovaSettingsMetrics.categoryRailWidthDp().dp)") &&
                content.contains("Arrangement.spacedBy(NovaSettingsMetrics.wideColumnSpacingDp().dp)") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.headerToQuickStripSpacingDp().dp))") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.quickStripToContentSpacingDp().dp))") &&
                content.contains("Spacer(Modifier.height(NovaSettingsMetrics.contentToHintSpacingDp().dp))")
        )
        assertTrue(
            "quick settings should stay useful but stop dominating Retroid first paint height",
            quickStrip.contains(".height(NovaSettingsMetrics.quickStripHeightDp().dp)") &&
                quickPill.contains(".width(NovaSettingsMetrics.quickPillWidthDp().dp)") &&
                quickPill.contains(".heightIn(min = NovaSettingsMetrics.quickStripHeightDp().dp)")
        )
        assertTrue(
            "category rail and rows should use compact spacing/padding so more settings are visible above the hint bar",
            categoryRail.contains("Arrangement.spacedBy(NovaSettingsMetrics.categoryRailSpacingDp().dp)") &&
                categoryRow.contains("vertical = NovaSettingsMetrics.categoryRowVerticalPaddingDp().dp") &&
                rows.contains("Arrangement.spacedBy(NovaSettingsMetrics.settingsRowSpacingDp().dp)") &&
                rows.contains("PaddingValues(bottom = NovaSettingsMetrics.rowsBottomPaddingDp().dp)") &&
                settingRow.contains("vertical = NovaSettingsMetrics.settingsRowVerticalPaddingDp().dp")
        )
        assertTrue(
            "Settings text should declare compact line heights instead of inheriting oversized Material body metrics on RP6",
            quickPill.contains("lineHeight = 11.sp") &&
                quickPill.contains("lineHeight = 14.sp") &&
                categoryRow.contains("lineHeight = 16.sp") &&
                categoryRow.contains("lineHeight = 12.sp") &&
                settingRow.contains("lineHeight = 16.sp") &&
                settingRow.contains("lineHeight = 13.sp") &&
                applyBadge.contains("lineHeight = 11.sp") &&
                valueChip.contains("lineHeight = 14.sp")
        )
        assertTrue(
            "value chips should be compact enough to preserve title/summary room in the right column",
            valueChip.contains(".widthIn(min = 92.dp, max = 220.dp)") &&
                valueChip.contains(".heightIn(min = NovaSettingsMetrics.valueChipMinHeightDp().dp)")
        )
    }

    @Test
    fun reusableControllerHintBarIsWiredAcrossLibraryDetailAndSettings() {
        val focusComponents = readNovaFocusComponents()
        val library = readNovaLibraryActivity()
        val libraryScreen = library.section(
            "private fun NovaLibraryScreen(",
            "@Composable\n    private fun NovaLibraryFocusedBackdrop("
        )
        val detail = readNovaGameDetailSheet()
        val detailContent = detail.section(
            "fun NovaGameDetailSheetContent(",
            "@Composable\nprivate fun NovaDetailPanel("
        )
        val settings = readNovaSettingsScreen()
        val settingsContent = settings.section(
            "private fun NovaSettingsContent(",
            "@Composable\nprivate fun NovaSettingsCompactHeader("
        )

        assertTrue(
            "shared focus components should expose a small, reusable controller hint model and bar",
            focusComponents.contains("data class NovaControllerHint(") &&
                focusComponents.contains("fun NovaControllerHintBar(") &&
                focusComponents.contains(".horizontalScroll(rememberScrollState())") &&
                focusComponents.contains(".heightIn(min = 30.dp)") &&
                focusComponents.contains("contentDescription = hintContentDescription")
        )
        assertTrue(
            "library should default to drawer-first landscape controls while still preserving bottom space for the controller hint bar",
            libraryScreen.contains("val controllerHintBarBottomPadding = if (isLandscape)") &&
                libraryScreen.contains("val showLandscapeControlRail = NovaLibraryUiStateMapper.showLandscapeControlRail()") &&
                libraryScreen.contains("val controllerHintBarLandscapeStartPadding = if (isLandscape && showLandscapeControlRail) railWidth + 10.dp else 0.dp") &&
                libraryScreen.contains("if (isLandscape) {") &&
                libraryScreen.contains("NovaLibraryLandscapeToolbar(") &&
                libraryScreen.contains(".padding(bottom = controllerHintBarBottomPadding)") &&
                libraryScreen.contains("NovaControllerHintBar(") &&
                libraryScreen.contains("hints = novaLibraryControllerHints(isLandscape)") &&
                libraryScreen.contains("modifier = Modifier") &&
                libraryScreen.contains(".align(Alignment.BottomCenter)") &&
                libraryScreen.contains(".padding(start = controllerHintBarLandscapeStartPadding)") &&
                libraryScreen.contains(".fillMaxWidth()")
        )
        assertFalse(
            "landscape should no longer require the permanent left rail as the default customization surface",
            libraryScreen.contains("val controllerHintBarLandscapeStartPadding = if (isLandscape) railWidth + 10.dp else 0.dp")
        )
        assertTrue(
            "game detail sheet should use the shared hint bar with explicit horizontal and bottom padding inside the scrollable sheet",
            detailContent.contains("NovaControllerHintBar(") &&
                detailContent.contains("hints = novaGameDetailControllerHints()") &&
                detailContent.contains(".padding(start = 14.dp, end = 14.dp, top = 12.dp)") &&
                detailContent.contains(".padding(bottom = 16.dp)")
        )
        assertTrue(
            "settings should keep the main rows weighted above the shared hint bar instead of letting rows consume and clip the bottom controls",
            settingsContent.contains("val controllerHints = novaSettingsControllerHints()") &&
                settingsContent.contains("Row(\n                modifier = Modifier\n                    .weight(1f)") &&
                settingsContent.contains("modifier = Modifier\n                        .fillMaxWidth()\n                        .weight(1f)") &&
                settingsContent.contains("NovaControllerHintBar(")
        )
    }

    @Test
    fun libraryEmptyAndErrorTextIsBoundedAndCentered() {
        val source = readNovaLibraryActivity()
        val start = source.indexOf("private fun NovaLibraryEmptyState(")
        val end = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", start)
        val emptyState = source.substring(start, end)

        assertTrue(
            "empty/error copy should be centered for TV and narrow portrait layouts",
            emptyState.contains("textAlign = TextAlign.Center")
        )
        assertTrue(
            "empty/error copy should be width bounded so long messages do not run edge to edge",
            emptyState.contains(".widthIn(max = 360.dp)")
        )
    }

    @Test
    fun streamHudCompactTextDoesNotInheritBodyLineHeight() {
        val source = readNovaStreamHudContent()
        val metric = source.section(
            "private fun HudMetric(",
            "@Composable\nprivate fun HudTinyLabel("
        )
        val tinyLabel = source.section(
            "private fun HudTinyLabel(",
            "@Composable\nprivate fun HudValueText("
        )
        val valueText = source.section(
            "private fun HudValueText(",
            "@Composable\nprivate fun HudCompactText("
        )
        val compactText = source.section(
            "private fun HudCompactText(",
            "@Composable\nprivate fun HudStatusDot("
        )

        assertTrue(
            "metric tiles should have a minimum height instead of clipping text to a fixed row",
            metric.contains(".heightIn(min = 40.dp)")
        )
        assertTrue(
            "metric values should not inherit Material body line height",
            metric.contains("lineHeight = 12.sp")
        )
        assertTrue(
            "tiny labels should not inherit Material body line height",
            tinyLabel.contains("lineHeight = 8.sp")
        )
        assertTrue(
            "large HUD values should use a line height sized to their font",
            valueText.contains("lineHeight = (size + 2).sp")
        )
        assertTrue(
            "compact HUD values should not inherit Material body line height",
            compactText.contains("lineHeight = 12.sp")
        )
    }

    @Test
    fun commandCenterUsesAnchoredLeftDrawerInsteadOfBottomSheet() {
        val quickMenu = readNovaQuickMenu()
        val content = readNovaQuickMenuContent()

        assertFalse(
            "in-stream Command Center should not use a bottom sheet that floats high/off-center in landscape",
            quickMenu.contains("BottomSheetDialog") || quickMenu.contains("BottomSheetBehavior")
        )
        assertTrue(
            "in-stream Command Center should be hosted by a full-screen dialog overlay so the drawer can anchor to the left edge",
            quickMenu.contains("Dialog(game") &&
                quickMenu.contains("WindowManager.LayoutParams.MATCH_PARENT") &&
                quickMenu.contains("Gravity.START")
        )
        assertTrue(
            "full-screen Command Center dialog must remove platform/decor insets so the drawer is visually flush with the left display edge",
            quickMenu.contains("decorView.setPadding(0, 0, 0, 0)") &&
                quickMenu.contains("layoutInDisplayCutoutMode =")
        )
        assertTrue(
            "Command Center content should render inside a named left-side drawer wrapper",
            quickMenu.contains("NovaQuickMenuDrawer(") &&
                content.contains("fun NovaQuickMenuDrawer(")
        )
        assertTrue(
            "Compose overlay dialogs must inherit the stream activity lifecycle before attach",
            quickMenu.contains("composeView.setViewTreeLifecycleOwner(game)")
        )
        assertTrue(
            "left drawer should be width-capped for landscape phones/TV while staying near-full-width on compact portrait screens",
            content.contains(".widthIn(max = 460.dp)") &&
                content.contains("compactDrawerWidth = (configuration.screenWidthDp * 0.92f).dp")
        )
        assertTrue(
            "left drawer should use trailing rounded corners, not a bottom-sheet top-only shape",
            content.contains("RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)") &&
                !content.contains("RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)")
        )
        assertTrue(
            "drawer surface should be opaque enough at x=0 that it reads as attached instead of floating over the stream",
            content.contains("surfaces.panel.copy(alpha = 0.96f)")
        )
        assertTrue(
            "scrim should dismiss the Command Center while keeping the stream visible behind the drawer",
            content.contains("surfaces.backgroundScrim.copy(alpha = 0.38f)") &&
                content.contains("callbacks.onDismiss")
        )
    }

    @Test
    fun commandCenterGroupsQuickKeysBeforeSecondaryPanels() {
        val content = readNovaQuickMenuContent()
        val body = content.section(
            "fun NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaQuickMenuHeader("
        )

        val stabilityCard = body.indexOf("NovaQuickMenuStabilityCard(state.stability, callbacks)")
        val quickKeysPanel = body.indexOf("NovaQuickMenuPanel(title = quickKeysTitle)")
        val syncCard = body.indexOf("NovaQuickMenuInfoCard(\n                    action = state.sync")
        val advancedToggleCard = body.indexOf("NovaQuickMenuInfoCard(\n                    action = state.advancedToggle")
        val overlaysPanel = body.indexOf("title = overlaysTitle")
        val controlsPanel = body.indexOf("title = controlsTitle")
        val sessionPanel = body.indexOf("NovaQuickMenuPanel(title = sessionTitle)")

        assertTrue(
            "Command Center quick shortcuts should have an explicit section label instead of floating between tuning and overlays",
            body.contains("val quickKeysTitle = stringResource(R.string.nova_quick_menu_quick_keys)") &&
                quickKeysPanel >= 0
        )
        assertTrue(
            "Command Center first paint should prioritize Quick Keys before tuning/status cards so Retroid users do not have to scroll to reach ESC/Alt+Enter/Alt+F4",
            quickKeysPanel in 0 until stabilityCard &&
                quickKeysPanel in 0 until syncCard &&
                quickKeysPanel in 0 until advancedToggleCard
        )
        assertTrue(
            "quick shortcuts should stay above secondary overlay/control/session panels for controller-first access",
            quickKeysPanel in 0 until overlaysPanel &&
                overlaysPanel in 0 until controlsPanel &&
                controlsPanel in 0 until sessionPanel
        )
    }

    @Test
    fun legacyAppLibraryHeroExposesEndSessionForOwnedStreams() {
        val layout = readSource("src/main/res/layout/activity_app_view.xml")
        val source = readSource("src/main/java/com/papi/nova/AppView.kt")

        assertTrue(
            "legacy app library hero should include a dedicated End Session affordance",
            layout.contains("@+id/recently_played_end_session") &&
                layout.contains("@string/applist_menu_quit")
        )
        assertTrue(
            "end-session affordance should only show for this client's active stream",
            source.contains("endSessionView?.visibility = if (appIsRunning && !appOwnedByAnotherClient)")
        )
        assertTrue(
            "end-session affordance should use the same quit confirmation and refresh path as the app sheet",
            source.contains("endRunningSessionFromLibrary(finalTargetApp.app)") &&
                source.contains("UiHelper.displayQuitConfirmationDialog") &&
                source.contains("ServerHelper.doQuit")
        )
        assertTrue(
            "library End Session should resume grid polling after either quit success or failure",
            source.contains("private fun quitRunningSessionAndRefresh(") &&
                source.contains("val resumeGridUpdates = Runnable") &&
                source.contains("ServerHelper.doQuit(this, activeComputer, app, binder, resumeGridUpdates, resumeGridUpdates)") &&
                readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
                    .contains("onFail: Runnable?,")
        )
        assertTrue(
            "ServerHelper quit failure callbacks should run when the host reports quit failure",
            readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
                .contains("val quitSucceeded = httpConn.quitApp(sessionToken)") &&
                readSource("src/main/java/com/papi/nova/utils/ServerHelper.kt")
                    .contains("failed = !quitSucceeded")
        )
    }

    @Test
    fun composeLibraryActiveSessionCardExposesEndSessionForOwnedStreams() {
        val source = readNovaLibraryActivity()
        val card = source.section(
            "private fun NovaLibraryActiveSessionCard(",
            "private fun formatStreamProfile("
        )

        assertTrue(
            "Compose library should pass an end-session callback into the screen",
            source.contains("onEndSession = ::endActiveSession")
        )
        assertTrue(
            "active session card should offer End Session alongside Resume only for streams owned by this client",
            card.contains("onEndSession: (NovaLibraryActiveSessionUiState) -> Unit") &&
                card.contains("if (!session.watchOnly)") &&
                card.contains("R.string.applist_menu_quit") &&
                card.contains("onClick = { onEndSession(session) }")
        )
        assertTrue(
            "ending from the library should route through the same confirmed quit path and clear the card",
            source.contains("private fun endActiveSession(session: NovaLibraryActiveSessionUiState)") &&
                source.contains("ComputerDetails.AddressTuple(streamHost, streamHttpPort)") &&
                source.contains("ServerHelper.doQuit(") &&
                source.contains("activeSession = null") &&
                source.contains("scheduleActiveSessionFollowUpRefreshes(clearOnly = true)")
        )
    }

    @Test
    fun streamHudUsesCompactBoundedLabels() {
        val source = readNovaStreamHudContent()
        val debugHud = source.section(
            "private fun NovaStreamHudDebug(",
            "@Composable\nprivate fun NovaStreamHudPerformance("
        )
        val performanceHud = source.section(
            "private fun NovaStreamHudPerformance(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )
        val minimalHud = source.section(
            "private fun NovaStreamHudMinimal(",
            "@Composable\nprivate fun HudPanel("
        )

        assertTrue(
            "debug HUD should use the shorter HUD-specific status label",
            debugHud.contains("text = state.autopilotHudLabel")
        )
        assertTrue(
            "debug HUD status label should have a max width so it cannot crowd the FPS label",
            debugHud.contains(".widthIn(max = 96.dp)")
        )
        assertTrue(
            "performance HUD should cap its overlay width while allowing narrow parents to constrain it",
            performanceHud.contains("modifier = modifier.widthIn(max = 320.dp)")
        )
        assertTrue(
            "performance compact status should be horizontally bounded",
            performanceHud.contains(".widthIn(min = 28.dp, max = 42.dp)")
        )
        assertTrue(
            "performance HUD should use explicit compact line height for the status chip",
            performanceHud.contains("lineHeight = 11.sp")
        )
        assertFalse(
            "minimal HUD should stay casual: no bitrate readout",
            minimalHud.contains("state.bitrateLabel")
        )
        assertFalse(
            "minimal HUD should avoid sparkline density during casual play",
            minimalHud.contains("NovaHudSparkline")
        )
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaStreamHudContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt")

    private fun readNovaQuickMenu(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")

    private fun readNovaQuickMenuContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt")

    private fun readNovaGameDetailSheet(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")

    private fun readNovaSettingsScreen(): String =
        readSource("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt")

    private fun readNovaFocusComponents(): String =
        readSource("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt")

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.containsRegex(pattern: String): Boolean =
        Regex(pattern).containsMatchIn(this)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
