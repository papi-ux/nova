package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComposeSourceGuardTest {
    @Test
    fun commandCenterRequestsInitialFocusForDpadNavigationOnOpen() {
        val quickMenuContent = readNovaQuickMenuContent()
        val content = quickMenuContent.section(
            "fun NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaQuickMenuHeader("
        )
        val header = quickMenuContent.section(
            "private fun NovaQuickMenuHeader(",
            "@Composable\nprivate fun NovaQuickMenuTitleBlock("
        )
        val closeButton = quickMenuContent.section(
            "private fun NovaQuickMenuCloseButton(",
            "@Composable\nprivate fun NovaQuickMenuSessionStrip("
        )

        assertTrue(
            "Command Center should create a FocusRequester when opened so Android TV DPAD navigation has an initial target without requiring keyboard Tab",
            quickMenuContent.contains("import androidx.compose.ui.focus.FocusRequester") &&
                quickMenuContent.contains("import androidx.compose.ui.focus.focusRequester") &&
                content.contains("val initialFocusRequester = remember { FocusRequester() }") &&
                content.contains("LaunchedEffect(Unit)") &&
                content.contains("initialFocusRequester.requestFocus()")
        )
        assertTrue(
            "Command Center should attach that initial focus requester to the visible Close button in both compact and wide header layouts",
            content.contains("NovaQuickMenuHeader(state, callbacks, initialFocusRequester)") &&
                header.contains("initialFocusRequester: FocusRequester") &&
                header.contains("NovaQuickMenuCloseButton(callbacks, initialFocusRequester)") &&
                closeButton.contains("initialFocusRequester: FocusRequester") &&
                closeButton.contains(".focusRequester(initialFocusRequester)")
        )
    }

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
            "quick options drawer should use a real modal overlay with scrollable focusable content",
            optionsSheet.contains("Dialog(") &&
                optionsSheet.contains("usePlatformDefaultWidth = false") &&
                optionsSheet.contains(".verticalScroll(rememberScrollState())") &&
                optionsSheet.contains(".focusGroup()") &&
                optionsSheet.contains("NovaLibrarySortMode.entries") &&
                optionsSheet.contains("NovaLibraryLayoutMode.entries")
        )
        assertTrue(
            "quick options sheet should expose Sort, Layout, and Poster title sections rather than hiding browsing decisions in the rail",
            optionsSheet.contains("R.string.nova_library_options_sort_title") &&
                optionsSheet.contains("R.string.nova_library_options_layout_title") &&
                optionsSheet.contains("R.string.nova_library_options_poster_titles_title") &&
                optionsSheet.contains("onSortMode(sortMode)") &&
                optionsSheet.contains("onLayoutMode(layoutMode)") &&
                optionsSheet.contains("onPosterTitlesVisible(true)") &&
                optionsSheet.contains("onPosterTitlesVisible(false)")
        )
        assertTrue(
            "library drawer options should be persisted so sort, layout, poster titles, and filters survive relaunches",
            activity.contains("NovaLibraryPreferences.loadOptions(libraryPreferences)") &&
                activity.contains("NovaLibraryPreferences.loadFilterState(libraryPreferences)") &&
                activity.contains("NovaLibraryPreferences.persistOptions(libraryPreferences(), nextState)") &&
                activity.contains("NovaLibraryPreferences.persistFilterState(libraryPreferences(), normalized)") &&
                activity.contains("updateLibraryOptions { it.copy(sortMode = sortMode) }") &&
                activity.contains("updateLibraryOptions { it.copy(layoutMode = layoutMode) }") &&
                activity.contains("updateLibraryOptions { it.copy(showPosterTitles = showPosterTitles) }") &&
                activity.contains("updateLibraryFilterState(NovaLibraryFilterState())")
        )
        assertTrue(
            "layout modes and poster-title visibility should be wired into actual library card rendering",
            activity.contains("val layoutMode = model.optionsState.layoutMode") &&
                activity.contains("val compactCards = layoutMode == NovaLibraryLayoutMode.COMPACT_GRID") &&
                activity.contains("val listCards = layoutMode == NovaLibraryLayoutMode.LIST") &&
                activity.contains("compact = compactCards") &&
                activity.contains("listStyle = listCards") &&
                activity.contains("showPosterTitle = model.optionsState.showPosterTitles")
        )
        assertTrue(
            "quick options strings should cover the Sort/Layout/Poster title surface",
            strings.contains("name=\"nova_library_options_title\">Library Options") &&
                strings.contains("name=\"nova_library_options_sort_recent\">Recent") &&
                strings.contains("name=\"nova_library_options_sort_name_asc\">Name A-Z") &&
                strings.contains("name=\"nova_library_options_sort_name_desc\">Name Z-A") &&
                strings.contains("name=\"nova_library_options_sort_source\">Source") &&
                strings.contains("name=\"nova_library_options_sort_hdr_first\">HDR first") &&
                strings.contains("name=\"nova_library_options_layout_compact_grid\">Compact grid") &&
                strings.contains("name=\"nova_library_options_poster_titles_title\">Poster titles") &&
                strings.contains("name=\"nova_library_options_poster_titles_hide\">Plain artwork")
        )
    }

    @Test
    fun libraryPersistentChromeStaysOutOfTheGamesWay() {
        val activity = readNovaLibraryActivity()
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryFocusedBackdrop("
        )
        val landscapeToolbar = activity.section(
            "private fun NovaLibraryLandscapeToolbar(",
            "private fun NovaLibraryTopHeader("
        )
        val portraitHeader = activity.section(
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryTitle("
        )

        assertTrue(
            "landscape toolbar should be a slim nav overlay, not another padded card slab above the grid",
            landscapeToolbar.contains("surfaces.panel.copy(alpha = 0.72f)") &&
                landscapeToolbar.contains(".padding(horizontal = 10.dp, vertical = 6.dp)") &&
                landscapeToolbar.contains("fontSize = 16.sp")
        )
        assertFalse(
            "landscape toolbar should not wrap the top nav in NovaLibraryPanel's full card treatment",
            landscapeToolbar.contains("NovaLibraryPanel(modifier = Modifier.fillMaxWidth())")
        )
        assertTrue(
            "portrait should move browse chrome into Library Options so games begin higher on the screen",
            portraitHeader.contains("NovaLibraryCompactMetaRow(") &&
                portraitHeader.contains("hasClearableFilters(searchQuery, filterState)")
        )
        assertFalse(
            "portrait header should not permanently spend vertical space on search and horizontal filter chips",
            portraitHeader.contains("NovaSearchField(") ||
                portraitHeader.contains("NovaLibraryPrimaryFilter.entries.forEach")
        )
        assertTrue(
            "landscape should keep the compact hero + full-width grid stack after the slim toolbar",
            screen.contains("NovaLibraryLandscapeToolbar(") &&
                screen.contains("NovaLibraryHomeHero(") &&
                screen.contains("NovaLibraryContent(")
        )
    }

    @Test
    fun libraryOptionsOverlayIsTightConsoleDrawerNotFullWidthMaterialSheet() {
        val optionsSheet = readNovaLibraryActivity().section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )

        assertTrue(
            "library options should render as an anchored console drawer with stronger scrim instead of a full-width bottom sheet",
            optionsSheet.contains("Dialog(") &&
                optionsSheet.contains("usePlatformDefaultWidth = false") &&
                optionsSheet.contains("align(Alignment.CenterStart)") &&
                optionsSheet.contains("widthIn(max = 420.dp)") &&
                optionsSheet.contains("surfaces.backgroundScrim.copy(alpha = 0.58f)")
        )
        assertFalse(
            "library options should not use the giant Material bottom sheet now that it is the primary browse drawer",
            optionsSheet.contains("ModalBottomSheet(")
        )
        assertTrue(
            "drawer internals should be denser than the previous material sheet rhythm",
            optionsSheet.contains(".padding(horizontal = 14.dp, vertical = 12.dp)") &&
                optionsSheet.contains("verticalArrangement = Arrangement.spacedBy(6.dp)") &&
                optionsSheet.contains("fontSize = 18.sp")
        )
    }

    @Test
    fun libraryDrawersPrioritizeRetroidFirstPaintDensity() {
        val activity = readNovaLibraryActivity()
        val optionsSheet = activity.section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )
        val systemSheet = activity.section(
            "private fun NovaSystemMenuSheet(",
            "private fun NovaLibraryOptionsSheet("
        )
        val searchIndex = optionsSheet.indexOf("NovaSearchField(")
        val refreshIndex = optionsSheet.indexOf("R.string.nova_refresh")

        assertTrue(
            "left drawer should put Search before Refresh so the primary browse task is first on Retroid",
            searchIndex >= 0 && refreshIndex > searchIndex
        )
        assertFalse(
            "left drawer should not spend first-paint height on prose hint copy after the split is visible in the shell",
            optionsSheet.contains("R.string.nova_library_options_hint")
        )
        assertTrue(
            "left drawer should use a tighter first-paint rhythm: 40dp search, 32dp secondary refresh, and 6dp section spacing",
            optionsSheet.contains("heightDp = 40") &&
                optionsSheet.contains("minHeight = 32.dp") &&
                optionsSheet.contains("verticalArrangement = Arrangement.spacedBy(6.dp)")
        )
        assertTrue(
            "right drawer should keep host status compact and make every safe action visible without bottom clipping on Retroid",
            systemSheet.contains("maxLines = 1") &&
                systemSheet.contains(".height(48.dp)") &&
                systemSheet.contains(".padding(horizontal = 12.dp, vertical = 5.dp)") &&
                systemSheet.contains("verticalArrangement = Arrangement.spacedBy(6.dp)")
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
        val menuKeyHandler = keyHandler.section(
            "KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> {",
            "else -> super.onKeyDown"
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
            "system menu should dismiss via dialog onDismissRequest so Back/B and scrim close it before leaving the library",
            systemSheet.contains("Dialog(") &&
                systemSheet.contains("usePlatformDefaultWidth = false") &&
                systemSheet.contains("onDismissRequest = onDismiss") &&
                systemSheet.contains("align(Alignment.CenterEnd)") &&
                systemSheet.contains(".verticalScroll(rememberScrollState())") &&
                activity.contains("dismissActiveLibraryOverlay()") &&
                keyHandler.contains("keyCode == KeyEvent.KEYCODE_BUTTON_B && dismissActiveLibraryOverlay()")
        )
        assertTrue(
            "system menu should clear options/filter overlays on open and let controller shortcuts hop between left/right drawers",
            activity.contains("private val hasActiveLibraryOverlay") &&
                activity.contains("activeOptionsSheet = false") &&
                activity.contains("activeFilterSheet = null") &&
                keyHandler.contains("if (!activeOptionsSheet) openLibraryOptionsSheet()") &&
                keyHandler.contains("if (!activeSystemMenu) openLibrarySystemMenu()") &&
                systemSheet.contains("onOpenOptions: () -> Unit") &&
                systemSheet.contains("event.nativeKeyEvent.keyCode") &&
                systemSheet.contains("KeyEvent.KEYCODE_DPAD_LEFT") &&
                systemSheet.contains("KeyEvent.KEYCODE_BUTTON_L1") &&
                systemSheet.contains("KeyEvent.KEYCODE_BUTTON_X") &&
                systemSheet.contains("event.key == Key.DirectionLeft") &&
                systemSheet.contains("onOpenOptions()")
        )
        assertTrue(
            "Menu/Start should be a destination-to-System shortcut, not a close toggle; Back/B owns dismiss",
            menuKeyHandler.contains("if (!activeSystemMenu) openLibrarySystemMenu()") &&
                !menuKeyHandler.contains("dismissLibrarySystemMenu()")
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
            "system menu should expose the short top-level Nova/system actions only",
            systemSheet.contains("R.string.nova_system_menu_switch_host") &&
                systemSheet.contains("R.string.nova_system_menu_settings") &&
                systemSheet.contains("R.string.nova_system_menu_polaris_sync") &&
                systemSheet.contains("R.string.nova_system_menu_manage_server") &&
                systemSheet.contains("R.string.nova_system_menu_help_diagnostics") &&
                systemSheet.contains("R.string.nova_system_menu_about")
        )
        assertTrue(
            "system rows should route to existing workflows and dismiss before launching secondary surfaces",
            systemSheet.contains("onSwitchHost") &&
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
            systemSheet.contains("verticalArrangement = Arrangement.spacedBy(6.dp)") &&
                systemSheet.contains("fontSize = 18.sp") &&
                systemSheet.contains(".height(48.dp)") &&
                systemSheet.contains("fontSize = 13.sp") &&
                systemSheet.contains("fontSize = 9.sp")
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
                strings.contains("name=\"nova_system_menu_switch_host\">Switch host") &&
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
            "private fun NovaLibraryHomeHero("
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
            "private fun NovaLibraryLandscapeToolbar("
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
            "hero should expose exactly one visually dominant game-launcher primary action through NovaActionButton",
            hero.contains("NovaActionButton(") && hero.contains("text = hero.actionLabel")
        )
        assertEquals(
            "home hero should not grow a duplicate primary launch/resume button beside the mapped CTA",
            1,
            hero.split("text = hero.actionLabel").size - 1
        )
        assertTrue(
            "hero should render the mapped caption so filtered, recent, active, and empty states explain the CTA",
            hero.contains("text = hero.caption")
        )
        assertTrue(
            "compact hero should render the mapper-owned supporting line instead of recomputing session/recent context in Compose",
            hero.contains("hero.supportingLine") &&
                hero.contains("text = hero.supportingLine") &&
                hero.contains("if (compact && hero.supportingLine.isNotBlank())")
        )
        assertTrue(
            "hero should render the selected game's real cover before falling back to a deterministic Nova artwork tile",
            hero.contains("NovaLibraryHeroArtwork(") &&
                hero.contains("game = heroGame") &&
                hero.contains("apiClient = apiClient") &&
                hero.contains("fallbackTitle = hero.artworkFallbackTitle") &&
                hero.contains("fallbackSubtitle = hero.artworkFallbackSubtitle")
        )
        assertTrue(
            "hero height should be mapper-driven so Retroid landscape can shrink the resume surface without source spelunking",
            hero.contains("val height = NovaLibraryUiStateMapper.heroHeightDp(compact = compact).dp")
        )
        assertTrue(
            "landscape compact shell should route persistent chrome metrics through the mapper before games lose another row",
            screen.contains("NovaLibraryUiStateMapper.screenPaddingDp(isLandscape).dp") &&
                screen.contains("NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp") &&
                screen.contains("Arrangement.spacedBy(NovaLibraryUiStateMapper.landscapeContentSpacingDp().dp)")
        )
        assertTrue(
            "hero caption stack should use tighter vertical spacing to avoid clipped badge rows",
            hero.contains("Arrangement.spacedBy(if (compact) 1.dp else 5.dp)")
        )
        assertTrue(
            "compact hero should use tight padding so richer console context still gives vertical room back to the grid",
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
    fun libraryHomeHeroKeepsTitleVisibleBesideBoundedCoverAndCta() {
        val source = readNovaLibraryActivity()
        val hero = source.section(
            "private fun NovaLibraryHomeHero(",
            "private fun NovaLibraryHeroFallbackArtwork("
        )

        assertTrue(
            "home hero needs the Polaris API client so selected games use the same cover loader as grid/detail cards",
            hero.contains("apiClient: PolarisApiClient") &&
                source.contains("private fun NovaLibraryHeroArtwork(") &&
                source.contains("apiClient.loadCoverInto(this, targetGame)")
        )
        assertTrue(
            "compact landscape hero should place artwork, title context, and a bounded launch CTA in that order",
            hero.indexOf("NovaLibraryHeroArtwork(") in 0 until hero.indexOf("Column(\n                modifier = Modifier.weight(1f)") &&
                hero.contains("Modifier.width(if (compact) 132.dp else 168.dp)")
        )
        assertFalse(
            "hero CTA column must not use unconstrained widthIn + fillMaxWidth because it gobbles the row and hides the title",
            hero.contains("Modifier.widthIn(min = if (compact) 104.dp else 148.dp)")
        )
    }

    @Test
    fun libraryGridKeepsPosterRowsAboveFooterChrome() {
        val activity = readNovaLibraryActivity()
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "@Composable\n    private fun NovaLibraryFocusedBackdrop("
        )
        val content = activity.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )

        assertTrue(
            "landscape shell should reserve a mapper-owned footer gutter for the overlaid controller hints instead of letting poster rows render under the bar",
            screen.contains("NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp") &&
                mapper.contains("private const val LANDSCAPE_CONTROLLER_HINT_BOTTOM_PADDING_DP = 48")
        )
        assertTrue(
            "game grid should use mapper-owned inner padding with extra bottom scroll room so the final poster row can settle above the footer",
            content.contains("contentPadding = PaddingValues(") &&
                content.contains("NovaLibraryUiStateMapper.gridContentPaddingDp().dp") &&
                content.contains("bottom = NovaLibraryUiStateMapper.gridBottomContentPaddingDp(isLandscape).dp") &&
                mapper.contains("fun gridBottomContentPaddingDp(isLandscape: Boolean): Int")
        )
    }

    @Test
    fun libraryEmptyAndOfflineRecoveryStatesUseDeliberateCtas() {
        val activity = readNovaLibraryActivity()
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val content = activity.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )

        assertTrue(
            "library content should route empty grids through mapper-owned recovery state",
            content.contains("NovaLibraryUiStateMapper.emptyRecoveryState(") &&
                content.contains("emptyState = model.emptyState") &&
                content.contains("totalCount = model.summary.totalCount") &&
                content.contains("sourceName = filterState.source")
        )
        assertTrue(
            "default no-games empty state should make Manage library the one primary recovery action",
            mapper.contains("NovaLibraryEmptyState.DEFAULT -> NovaLibraryRecoveryUiState(") &&
                mapper.contains("primaryActionLabel = \"Manage library\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.MANAGE_LIBRARY")
        )
        assertTrue(
            "recent-empty state should invite users back to the full library instead of sounding like an error",
            mapper.contains("NovaLibraryEmptyState.RECENT -> NovaLibraryRecoveryUiState(") &&
                mapper.contains("primaryActionLabel = \"View all games\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS")
        )
        assertTrue(
            "source no-results should name the selected source and use one direct clear-source CTA",
            mapper.contains("title = \"No ${'$'}sourceLabel games\"") &&
                mapper.contains("primaryActionLabel = \"Clear source\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS") &&
                mapper.contains("private fun sourceDisplayName(sourceName: String?)")
        )
        assertTrue(
            "filtered empty state should keep Clear filters as the direct escape hatch",
            mapper.contains("NovaLibraryEmptyState.FILTERED -> NovaLibraryRecoveryUiState(") &&
                mapper.contains("primaryActionLabel = \"Clear filters\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS")
        )
        assertTrue(
            "offline/load failure recovery should distinguish retryable connection failures from Polaris API/server failures",
            mapper.contains("fun loadFailureRecoveryState(message: String)") &&
                mapper.contains("title = \"Host offline\"") &&
                mapper.contains("primaryActionLabel = \"Retry\"") &&
                mapper.contains("title = \"Polaris unavailable\"") &&
                mapper.contains("primaryActionLabel = \"Manage server\"")
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
    fun libraryOptionsDrawerKeepsDpadTraversalInsideLeftDrawer() {
        val optionsSheet = readNovaLibraryActivity().section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )

        assertTrue(
            "left library options drawer should be a focus group so vertical D-pad traversal stays inside browse controls",
            optionsSheet.contains(".focusGroup()")
        )
        assertTrue(
            "left library options drawer should let Right hop to the system drawer for the two-zone controller map",
            optionsSheet.contains("onOpenSystemMenu: () -> Unit") &&
                optionsSheet.contains(".onPreviewKeyEvent { event ->") &&
                optionsSheet.contains("event.nativeKeyEvent.keyCode") &&
                optionsSheet.contains("KeyEvent.KEYCODE_DPAD_RIGHT") &&
                optionsSheet.contains("KeyEvent.KEYCODE_BUTTON_R1") &&
                optionsSheet.contains("KeyEvent.KEYCODE_BUTTON_START") &&
                optionsSheet.contains("event.key == Key.DirectionRight") &&
                optionsSheet.contains("onOpenSystemMenu()")
        )
    }

    @Test
    fun libraryOptionsDrawerKeepsBottomControlsScrollableAboveSafeArea() {
        val optionsSheet = readNovaLibraryActivity().section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )

        assertTrue(
            "left library options drawer scroll content should include safe-area padding so the final layout options can scroll above gesture/nav chrome",
            optionsSheet.contains(".windowInsetsPadding(WindowInsets.safeDrawing)") &&
                optionsSheet.contains(".verticalScroll(rememberScrollState())") &&
                optionsSheet.contains("Spacer(modifier = Modifier.height(14.dp))")
        )
    }

    @Test
    fun libraryOptionsDrawerUsesCompactBrowseControlsOnRetroidLandscape() {
        val optionsSheet = readNovaLibraryActivity().section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )

        assertTrue(
            "left drawer should own library refresh, search, filters, sort, and layout instead of a permanent rail",
            optionsSheet.contains("R.string.nova_refresh") &&
                optionsSheet.contains("NovaSearchField(") &&
                optionsSheet.contains("NovaLibraryPrimaryFilter.entries.forEach") &&
                optionsSheet.contains("NovaLibrarySortMode.entries") &&
                optionsSheet.contains("NovaLibraryLayoutMode.entries")
        )
        assertTrue(
            "left drawer should keep browse controls compact and horizontally scrollable for handheld landscape",
            optionsSheet.contains(".horizontalScroll(rememberScrollState())") &&
                optionsSheet.contains("Modifier.width(NovaLibraryUiStateMapper.filterChipWidthDp(filter).dp)") &&
                optionsSheet.contains("verticalArrangement = Arrangement.spacedBy(6.dp)")
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
            "private fun NovaLibraryRecoveryState("
        )

        assertTrue(
            "library game cards should use shared sizing rules for grid, compact grid, and list modes",
            gameCard.contains("NovaLibraryUiStateMapper.gameCardHeightDp(layoutMode = layoutMode, isLandscape = isLandscape).dp") &&
                gameCard.contains("listStyle -> NovaLibraryLayoutMode.LIST") &&
                gameCard.contains("compact -> NovaLibraryLayoutMode.COMPACT_GRID")
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
    fun libraryGameCardsReserveReadableTitleBandOnBusyArtwork() {
        val activity = readNovaLibraryActivity()
        val gameCard = activity.section(
            "private fun NovaLibraryGameCard(",
            "private fun NovaMiniBadge("
        )
        val titleScrim = if (activity.contains("private fun NovaLibraryCardTitleScrim(")) {
            activity.section(
                "private fun NovaLibraryCardTitleScrim(",
                "private fun NovaLibraryCardBadgeRow("
            )
        } else {
            ""
        }

        assertTrue(
            "grid game cards should gate the poster title/caption overlay so users can choose plain artwork posters",
            gameCard.contains("showPosterTitle: Boolean = true") &&
                gameCard.contains("if (showPosterTitle) {") &&
                gameCard.contains("NovaLibraryCardTitleScrim(") &&
                gameCard.indexOf("if (showPosterTitle) {") in 0 until gameCard.lastIndexOf("text = title")
        )
        assertTrue(
            "grid game cards should draw a dedicated title-safe scrim above cover art before title text when titles are enabled",
            gameCard.contains("NovaLibraryCardTitleScrim(") &&
                gameCard.indexOf("NovaLibraryCardTitleScrim(") in 0 until gameCard.lastIndexOf("text = title")
        )
        assertTrue(
            "title scrim should be a bounded bottom band, not a weak full-card wash that leaves white logo art behind white text",
            titleScrim.contains(".height(if (compact) 64.dp else 88.dp)") &&
                titleScrim.contains("0.36f to surfaces.mediaScrimBottom.copy(alpha = 0.64f)") &&
                titleScrim.contains("1.0f to surfaces.mediaScrimBottom.copy(alpha = 0.96f)")
        )
        assertTrue(
            "title and metadata should sit inside a small padded caption panel for extra contrast over noisy cover art",
            gameCard.contains(".background(surfaces.mediaScrimBottom.copy(alpha = 0.34f))") &&
                gameCard.contains(".padding(horizontal = 7.dp, vertical = 5.dp)")
        )
    }

    @Test
    fun libraryMiniBadgesStaySmallOnDenseCards() {
        val activity = readNovaLibraryActivity()
        val miniBadge = activity.section(
            "private fun NovaMiniBadge(",
            "@Composable\n    private fun NovaLibraryLoadingGrid("
        )
        val badgeRow = if (activity.contains("private fun NovaLibraryCardBadgeRow(")) {
            activity.section(
                "private fun NovaLibraryCardBadgeRow(",
                "private fun NovaMiniBadge("
            )
        } else {
            ""
        }

        assertTrue(
            "library card badges should use compact text",
            miniBadge.contains("fontSize = 8.sp")
        )
        assertTrue(
            "library card badges should pin a compact line height",
            miniBadge.contains("lineHeight = 9.sp")
        )
        assertTrue(
            "library card badges should use tighter pill padding",
            miniBadge.contains(".padding(horizontal = 5.dp, vertical = 1.dp)")
        )
        assertTrue(
            "grid cards should render badges through a bounded row so repeated HDR/Recent chips do not dominate cover art",
            badgeRow.contains("private fun NovaLibraryCardBadgeRow(") &&
                badgeRow.contains(".widthIn(max = if (compact) 92.dp else 128.dp)") &&
                badgeRow.contains("horizontalArrangement = Arrangement.spacedBy(4.dp)")
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
    fun libraryShowsCompactStatusMetadataWithoutPermanentRailCounts() {
        val source = readNovaLibraryActivity()
        val landscapeToolbar = source.section(
            "private fun NovaLibraryLandscapeToolbar(",
            "private fun NovaLibraryTopHeader("
        )
        val topHeader = source.section(
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryCompactMetaRow("
        )
        val compactMeta = source.section(
            "private fun NovaLibraryCompactMetaRow(",
            "private fun NovaLibraryTitle("
        )
        val hasStatusStrip = source.contains("private fun NovaLibraryStatusStrip(")
        val statusStrip = if (hasStatusStrip) {
            source.section(
                "private fun NovaLibraryStatusStrip(",
                "private fun compactStatusModeLabel("
            )
        } else {
            ""
        }

        assertFalse(
            "drawer-first library should not keep the old permanent rail mounted beside the grid",
            source.contains("private fun NovaLibraryRail(")
        )
        assertTrue(
            "compact chrome should keep Polaris readiness and result metadata visible without count-card rail chrome",
            landscapeToolbar.contains("R.string.nova_system_menu_status_polaris_ready") &&
                landscapeToolbar.contains("R.string.nova_library_results_format") &&
                topHeader.contains("NovaLibraryCompactMetaRow(") &&
                compactMeta.contains("R.string.nova_system_menu_status_polaris_ready") &&
                compactMeta.contains("R.string.nova_library_status_checking") &&
                compactMeta.contains("R.string.nova_library_resume_ready") &&
                !topHeader.contains("NovaLibrarySummary(")
        )
        assertTrue(
            "legacy status strip helper should still format Polaris readiness and resumable session state for any compact surfaces that reuse it",
            hasStatusStrip &&
                statusStrip.contains("R.string.nova_library_polaris_ready") &&
                statusStrip.contains("R.string.nova_library_polaris_checking") &&
                statusStrip.contains("R.string.nova_library_resume_ready") &&
                statusStrip.contains("activeSession != null")
        )
        assertTrue(
            "status copy should use compact launch-mode labels so handheld chrome does not clip",
            statusStrip.contains("compactStatusModeLabel(settings)") &&
                source.contains("private fun compactStatusModeLabel(")
        )
    }

    @Test
    fun libraryFiltersExposeClearActionWhenNarrowed() {
        val source = readNovaLibraryActivity()
        val optionsSheet = source.section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )
        val topHeader = source.section(
            "private fun NovaLibraryTopHeader(",
            "private fun NovaLibraryCompactMetaRow("
        )
        val compactMeta = source.section(
            "private fun NovaLibraryCompactMetaRow(",
            "private fun NovaLibraryTitle("
        )

        assertTrue(
            "library should compute a clearable state from search plus filter constraints",
            source.contains("private fun hasClearableFilters(") &&
                source.contains("searchQuery.isNotBlank() || filterState.hasActiveConstraint")
        )
        assertTrue(
            "left library options drawer should show a clear filters action when filters/search are active",
            optionsSheet.contains("if (hasClearableFilters(searchQuery, filterState))") &&
                optionsSheet.contains("R.string.nova_library_filter_clear_all")
        )
        assertTrue(
            "portrait header should summarize active filters without remounting browse controls permanently above the grid",
            topHeader.contains("val hasFilters = hasClearableFilters(searchQuery, filterState)") &&
                compactMeta.contains("if (hasFilters) add(\"Filters active\")") &&
                !topHeader.contains("R.string.nova_library_filter_clear_all") &&
                !topHeader.contains("NovaSearchField(")
        )
    }

    @Test
    fun gameDetailRetroidFirstPaintUsesCompactGameIdentityHeader() {
        val detail = readNovaGameDetailSheet()
        val detailsPanel = detail.section(
            "private fun GameDetailsPanel(",
            "@Composable\nprivate fun LaunchControlsPanel("
        )
        val launchControls = detail.section(
            "private fun LaunchControls(",
            "@Composable\nprivate fun LaunchProfileSummaryInline("
        )
        val launchModePill = detail.section(
            "private fun LaunchModeChoicePill(",
            "@Composable\nprivate fun ProfileSummaryText("
        )

        assertTrue(
            "Retroid landscape first paint should treat game identity as a compact launch header, not a second hero slab",
            detailsPanel.contains(".heightIn(min = 136.dp)") &&
                detailsPanel.contains("contentPadding = PaddingValues(12.dp)") &&
                detailsPanel.contains(".width(108.dp)") &&
                detailsPanel.contains("fontSize = 20.sp") &&
                detailsPanel.contains("lineHeight = 22.sp") &&
                detailsPanel.contains("maxLines = 2")
        )
        assertFalse(
            "game detail should not keep the old oversized first-paint panel that pushed launch mode choices below the fold",
            detailsPanel.contains(".heightIn(min = 172.dp)") ||
                detailsPanel.contains(".width(126.dp)") ||
                detailsPanel.contains("fontSize = 22.sp")
        )
        assertTrue(
            "primary launch and mode choice controls should stay compact enough to be visible together on Retroid first paint",
            launchControls.contains("minHeight = 50.dp") &&
                launchModePill.contains("modifier = modifier.heightIn(min = 52.dp)")
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
        assertTrue(
            "game detail should surface host/render limits before the primary launch action",
            launchControls.contains("LaunchProfilePrimaryNotice(") &&
                launchControls.indexOf("LaunchProfilePrimaryNotice(") < launchControls.indexOf("text = playLabel")
        )
    }

    @Test
    fun gameDetailLaunchModeUsesSingleInlineSelectorInsteadOfDuplicateOptionsDrawer() {
        val detail = readNovaGameDetailSheet()
        val launchControls = detail.section(
            "private fun LaunchControls(",
            "@Composable\nprivate fun LaunchProfileSummaryInline("
        )

        assertTrue(
            "Headless/Virtual should be directly selectable from the detail sheet before launching",
            launchControls.contains("LaunchModeChoicePill(") &&
                launchControls.contains("onClick = { onLaunchModeSelected(\"headless\") }") &&
                launchControls.contains("onClick = { onLaunchModeSelected(\"virtual_display\") }")
        )
        assertTrue(
            "Launch Options should remain a secondary path after inline Headless/Virtual choices",
            detail.contains("private fun showLaunchOptions(") &&
                detail.contains("onLaunchOptions = {") &&
                launchControls.contains("text = launchOptionsLabel") &&
                launchControls.indexOf("text = launchOptionsLabel") > launchControls.indexOf("LaunchModeChoicePill(")
        )
        assertTrue(
            "non-duplicative tuning should remain available separately from launch mode selection",
            launchControls.contains("text = profilePreferenceLabel") &&
                launchControls.indexOf("text = profilePreferenceLabel") > launchControls.indexOf("LaunchModeChoicePill(") &&
                launchControls.split("LaunchProfileSummaryInline(").size == 2
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
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        assertTrue(source.contains("private var appliedTheme"))
        assertTrue(source.contains("recreateForThemeChangeIfNeeded()"))
        val content = source.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )

        assertTrue(
            "library load errors should be stored in state instead of only a transient toast",
            source.contains("private var loadErrorMessage by mutableStateOf<String?>(null)")
        )
        assertTrue(
            "library content should render a persistent mapper-owned recovery state when no games loaded",
            content.contains("loadErrorMessage != null && model.allGames.isEmpty()") &&
                content.contains("NovaLibraryUiStateMapper.loadFailureRecoveryState(loadErrorMessage)") &&
                content.contains("NovaLibraryRecoveryState(")
        )
        assertTrue(
            "library load recovery should keep retry as the generic/offline primary action",
            mapper.contains("fun loadFailureRecoveryState(message: String)") &&
                mapper.contains("title = \"Host offline\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.RETRY") &&
                mapper.contains("title = \"Couldn't load library\"")
        )
    }

    @Test
    fun libraryLaunchFailuresUseDurableRecoveryState() {
        val source = readNovaLibraryActivity()
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val content = source.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )

        assertTrue(
            "launch/preflight errors should be durable Compose state instead of toast-only UX",
            source.contains("private var launchErrorMessage by mutableStateOf<String?>(null)") &&
                content.contains("launchRecoveryState = launchErrorMessage") &&
                content.contains("NovaLibraryUiStateMapper::launchFailureRecoveryState")
        )
        assertTrue(
            "launch recovery copy should offer one Manage server CTA with the raw failure preserved as detail",
            mapper.contains("fun launchFailureRecoveryState(message: String)") &&
                mapper.contains("title = \"Launch blocked\"") &&
                mapper.contains("primaryActionLabel = \"Manage server\"") &&
                mapper.contains("detail = message.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "missing launch prerequisites and thrown preflight exceptions should update launchErrorMessage",
            source.contains("launchErrorMessage = message") &&
                source.contains("Missing Polaris session details for launch") &&
                source.contains("Failed to launch ${'$'}{game.name}")
        )
        assertTrue(
            "stale launch recovery should clear on refresh, detail navigation, and valid launch/resume paths",
            source.contains("loadErrorMessage = null\n        launchErrorMessage = null") &&
                source.contains("private fun showGameDetail(game: PolarisGame) {\n        launchErrorMessage = null") &&
                source.contains("private fun launchGame(") &&
                source.contains("private fun resumeActiveSession(") &&
                source.split("launchErrorMessage = null").size >= 5
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
                settings.contains("fun quickPillWidthDp(): Int = 168") &&
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
    fun libraryControllerHintsNameShouldersAsLibrarySystemZones() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val hints = activity.section(
            "private fun novaLibraryControllerHints(",
            "@Composable\n    private fun NovaLibraryFocusedBackdrop("
        )
        val keyHandler = activity.section(
            "override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {",
            "override fun onStop()"
        )

        assertTrue(
            "global controller hints should name the spatial left/right zones instead of vague Panels/Options copy",
            hints.contains("key = stringResource(R.string.nova_controller_hint_x)") &&
                hints.contains("label = stringResource(R.string.nova_controller_hint_library)") &&
                hints.contains("key = stringResource(R.string.nova_controller_hint_lb_rb)") &&
                hints.contains("label = stringResource(R.string.nova_controller_hint_library_system)") &&
                strings.contains("name=\"nova_controller_hint_library\">Library") &&
                strings.contains("name=\"nova_controller_hint_library_system\">Library / System")
        )
        assertFalse(
            "the global controller hint should not call shoulders Panels, Filters, or generic Options after the two-zone split",
            hints.contains("label = stringResource(R.string.nova_controller_hint_panels)") ||
                hints.contains("label = stringResource(R.string.nova_controller_hint_filters)") ||
                hints.contains("label = stringResource(R.string.nova_controller_hint_options)")
        )
        assertTrue(
            "closed-screen shoulders should open the spatial drawers directly instead of cycling source/filter chips",
            keyHandler.contains("KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> {") &&
                keyHandler.contains("if (!activeOptionsSheet) openLibraryOptionsSheet()") &&
                keyHandler.contains("KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> {") &&
                keyHandler.contains("if (!activeSystemMenu) openLibrarySystemMenu()")
        )
        assertFalse(
            "shoulder keys should no longer walk primary filters globally; filter cycling belongs inside the Library drawer",
            keyHandler.contains("movePrimaryFilter(")
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
            libraryScreen.contains("val controllerHintBarBottomPadding = NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp") &&
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
        val start = source.indexOf("private fun NovaLibraryRecoveryState(")
        val end = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", start)
        val recoveryState = source.substring(start, end)

        assertTrue(
            "empty/error copy should be centered for TV and narrow portrait layouts",
            recoveryState.contains("textAlign = TextAlign.Center")
        )
        assertTrue(
            "empty/error copy should be width bounded so long messages do not run edge to edge",
            recoveryState.contains(".widthIn(max = 360.dp)")
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
    fun streamHudDragUsesRawTouchCoordinatesAndPreservesPositionAcrossModeCycles() {
        val source = readSource("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")
        val touchHandler = source.section(
            "private fun setupTouchHandler(view: View)",
            "fun cycleMode()"
        )
        val cycleMode = source.section(
            "fun cycleMode()",
            "fun updateFromPerfText("
        )

        assertTrue(
            "Nova HUD drag should be handled by the HUD view itself using raw display coordinates so Retroid touch swipes move the floating overlay instead of the stream surface",
            touchHandler.contains("view.setOnTouchListener") &&
                touchHandler.contains("event.rawX") &&
                touchHandler.contains("event.rawY") &&
                touchHandler.contains("viewStartX = touchedView.x") &&
                touchHandler.contains("viewStartY = touchedView.y") &&
                touchHandler.contains("touchedView.x = viewStartX + dx") &&
                touchHandler.contains("touchedView.y = viewStartY + dy") &&
                touchHandler.contains("DRAG_THRESHOLD")
        )
        assertTrue(
            "tap-to-cycle must not reset a user-dragged HUD back to top-left when the compact/expanded width changes",
            cycleMode.contains("val savedX = view.x") &&
                cycleMode.contains("val savedY = view.y") &&
                cycleMode.contains("width = layoutWidthForMode(currentMode)") &&
                cycleMode.contains("view.post") &&
                cycleMode.contains("view.x = savedX") &&
                cycleMode.contains("view.y = savedY")
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
            content.contains("NovaInGameOverlayAlpha.GlassPanel")
        )
        assertTrue(
            "scrim should dismiss the Command Center while keeping the stream visible behind the drawer",
            content.contains("NovaInGameOverlayAlpha.CommandCenterScrim") &&
                content.contains("callbacks.onDismiss")
        )
    }

    @Test
    fun commandCenterDrawerUsesFingerTrackedHorizontalMotion() {
        val content = readNovaQuickMenuContent()
        val drawer = content.section(
            "fun NovaQuickMenuDrawer(",
            "@Composable\nfun NovaQuickMenuContent("
        )

        assertFalse(
            "Retroid Command Center should not be a canned AnimatedVisibility drawer once tactile polish is enabled",
            drawer.contains("AnimatedVisibility(") || drawer.contains("slideInHorizontally(")
        )
        assertTrue(
            "drawer motion should be progress-aware: spring in, offset by progress, and move with horizontal drag distance",
            drawer.contains("Animatable(0f)") &&
                drawer.contains("animateDrawerTo(") &&
                drawer.contains("spring(") &&
                drawer.contains("IntOffset(") &&
                drawer.contains("drawerProgress.value") &&
                drawer.contains("dragAmount / drawerWidthPx")
        )
        assertTrue(
            "horizontal drag should be orientation-locked so vertical Command Center scrolling does not accidentally dismiss",
            drawer.contains("detectHorizontalDragGestures(") &&
                drawer.contains("onHorizontalDrag =") &&
                drawer.contains("change.consume()")
        )
        assertTrue(
            "swipe-left dismissal should close only after a meaningful progress threshold, preserving tap/back dismissal semantics",
            drawer.contains("NovaQuickMenuDrawerDismissProgress") &&
                drawer.contains("dismissDrawerWithMotion()") &&
                drawer.contains("callbacks.onDismiss()")
        )
    }

    @Test
    fun inGameOverlayOpacityTokensAreSharedByCommandCenterAndHud() {
        val tokenPath = Path.of("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        assertTrue(
            "Command Center and NovaHUD should share named in-game glass opacity tokens instead of local magic alpha literals",
            Files.exists(tokenPath)
        )
        val tokens = readSource("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        val commandCenter = readNovaQuickMenuContent()
        val hud = readNovaStreamHudContent()

        assertTrue(
            "token file should name the overlay alpha contract for panels, nested controls, scrim, and borders",
            tokens.contains("object NovaInGameOverlayAlpha") &&
                tokens.contains("const val GlassPanel") &&
                tokens.contains("const val NestedTile") &&
                tokens.contains("const val NestedControl") &&
                tokens.contains("const val CommandCenterScrim") &&
                tokens.contains("const val Border")
        )
        assertTrue(
            "Command Center should consume the shared alpha contract across scrim, panel, nested tiles, controls, borders, and handle",
            commandCenter.contains("NovaInGameOverlayAlpha.CommandCenterScrim") &&
                commandCenter.contains("NovaInGameOverlayAlpha.GlassPanel") &&
                commandCenter.contains("NovaInGameOverlayAlpha.NestedTile") &&
                commandCenter.contains("NovaInGameOverlayAlpha.NestedControl") &&
                commandCenter.contains("NovaInGameOverlayAlpha.Border") &&
                commandCenter.contains("NovaInGameOverlayAlpha.AccentHandle")
        )
        assertTrue(
            "NovaHUD should use the same glass/control/border token family so it reads as one overlay system with Command Center",
            hud.contains("NovaInGameOverlayAlpha.GlassPanel") &&
                hud.contains("NovaInGameOverlayAlpha.NestedControl") &&
                hud.contains("NovaInGameOverlayAlpha.Border") &&
                hud.contains("NovaInGameOverlayAlpha.AccentDivider")
        )
        assertFalse(
            "old local overlay alpha literals should be replaced by shared named tokens in the in-game overlay files",
            commandCenter.contains("surfaces.panel.copy(alpha = 0.96f)") ||
                commandCenter.contains("surfaces.tile.copy(alpha = 0.72f)") ||
                hud.contains("surfaces.panel.copy(alpha = 0.96f)") ||
                hud.contains("surfaces.control.copy(alpha = 0.82f)")
        )
    }


    @Test
    fun retroidLoneAppSwitchMapsToCommandCenterWithoutHijackingGenericMenu() {
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val controllerHandler = readSource("src/main/java/com/papi/nova/binding/input/ControllerHandler.kt")
        val shortcuts = readSource("src/main/java/com/papi/nova/binding/input/NovaControllerShortcutState.kt")

        val downShortcut = game.indexOf("handleFallbackNovaShortcut(event, down = true)")
        val downIgnore = if (downShortcut >= 0) {
            game.indexOf("prefConfig!!.ignoreSynthEvents && deviceId <= 0", downShortcut)
        } else {
            -1
        }
        val upShortcut = game.indexOf("handleFallbackNovaShortcut(event, down = false)")
        val upIgnore = if (upShortcut >= 0) {
            game.indexOf("prefConfig!!.ignoreSynthEvents && deviceId <= 0", upShortcut)
        } else {
            -1
        }

        assertTrue(
            "fallback shortcut handling must stay before ignoreSynthEvents so ADB app-owned shortcuts still work",
            downShortcut >= 0 && downIgnore > downShortcut &&
                upShortcut >= 0 && upIgnore > upShortcut
        )
        assertTrue(
            "synthetic fallback state should opt into lone KEYCODE_APP_SWITCH, not generic KEYCODE_MENU",
            game.contains("fallbackNovaShortcutState:NovaControllerShortcutState = NovaControllerShortcutState().apply") &&
                game.contains("loneAppSwitchOpensQuickMenu = true")
        )
        assertTrue(
            "Retroid built-in controller should be recognized by vendor/product before enabling lone app-switch",
            controllerHandler.contains("isRetroidPocketBuiltInController(context)") &&
                controllerHandler.contains("context.vendorId == 0x2022") &&
                controllerHandler.contains("context.productId == 0x3002") &&
                controllerHandler.contains("context.novaShortcutState.loneAppSwitchOpensQuickMenu = true")
        )
        assertTrue(
            "the lone app-switch path should open the same Command Center action as other shortcuts",
            shortcuts.contains("keyCode == KeyEvent.KEYCODE_APP_SWITCH && loneAppSwitchOpensQuickMenu") &&
                shortcuts.contains("NovaControllerShortcutAction.OPEN_QUICK_MENU")
        )
        assertFalse(
            "generic lone KEYCODE_MENU should not be promoted to single-button Command Center until hardware proves it is safe",
            shortcuts.contains("keyCode == KeyEvent.KEYCODE_MENU && loneAppSwitchOpensQuickMenu") ||
                shortcuts.contains("KeyEvent.KEYCODE_MENU && loneAppSwitchOpensQuickMenu")
        )
    }

    @Test
    fun commandCenterFirstPaintPrioritizesSessionQualityAndHudBeforeQuickKeys() {
        val content = readNovaQuickMenuContent()
        val body = content.section(
            "fun NovaQuickMenuContent(",
            "@Composable\nprivate fun NovaQuickMenuHeader("
        )
        val header = content.section(
            "private fun NovaQuickMenuHeader(",
            "@Composable\nprivate fun NovaQuickMenuTitleBlock("
        )

        val sessionStrip = body.indexOf("NovaQuickMenuSessionStrip(state)")
        val stabilityCard = body.indexOf("NovaQuickMenuStabilityCard(state.stability, callbacks)")
        val syncCard = body.indexOf("action = state.sync")
        val overlaysPanel = body.indexOf("title = overlaysTitle")
        val quickKeysPanel = body.indexOf("NovaQuickMenuPanel(title = quickKeysTitle)")
        val advancedToggleCard = body.indexOf("action = state.advancedToggle")
        val controlsPanel = body.indexOf("title = controlsTitle")
        val sessionPanel = body.indexOf("NovaQuickMenuPanel(title = sessionTitle)")

        assertTrue(
            "Command Center first paint should keep the session strip immediately after the header",
            sessionStrip >= 0 && stabilityCard > sessionStrip
        )
        assertTrue(
            "stream quality/recovery state should be above overlay/HUD controls so gameplay health is understood before shortcuts",
            stabilityCard in 0 until syncCard &&
                syncCard in 0 until overlaysPanel
        )
        assertTrue(
            "NovaHUD/overlay controls should be promoted above Quick Keys and lower utilities in the polished Command Center hierarchy",
            overlaysPanel in 0 until quickKeysPanel &&
                quickKeysPanel in 0 until advancedToggleCard &&
                advancedToggleCard in 0 until controlsPanel &&
                controlsPanel in 0 until sessionPanel
        )
        assertTrue(
            "Command Center header should expose an explicit close affordance that invokes the same dismiss callback as scrim/back",
            header.contains("NovaQuickMenuCloseButton(callbacks") &&
                content.contains("contentDescription = \"Close Command Center\"") &&
                content.contains("onClick = callbacks.onDismiss")
        )
    }

    @Test
    fun commandCenterPanelsUseSectionHeadersForHierarchy() {
        val content = readNovaQuickMenuContent()
        val panel = content.section(
            "private fun NovaQuickMenuPanel(",
            "@Composable\nprivate fun NovaQuickMenuRow("
        )

        assertTrue(panel.contains("NovaQuickMenuSectionHeader"))
        assertTrue(panel.contains("title.uppercase()"))
        assertTrue(panel.contains("colors.accent.copy(alpha = 0.14f)"))
        assertTrue(panel.contains("surfaces.focusRing.copy(alpha = 0.52f)"))
        assertTrue(content.contains("NovaQuickMenuPanel(title = overlaysTitle)"))
        assertTrue(content.contains("NovaQuickMenuPanel(title = quickKeysTitle)"))
        assertTrue(content.contains("NovaQuickMenuPanel(title = controlsTitle)"))
        assertTrue(content.contains("NovaQuickMenuPanel(title = sessionTitle)"))
    }

    @Test
    fun commandCenterExposesInsertThroughExistingSpecialKeyTranslator() {
        val state = readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt")
        val content = readNovaQuickMenuContent()
        val menu = readNovaQuickMenu()
        val legacyGameMenu = readSource("src/main/java/com/papi/nova/GameMenu.kt")
        val strings = readSource("src/main/res/values/strings.xml")

        assertTrue(
            "Command Center Quick Keys should expose Insert for OptiScaler without hiding it behind a keyboard pairing workaround",
            state.contains("QUICK_INSERT") &&
                state.contains("R.string.game_menu_send_keys_insert")
        )
        assertTrue(
            "Insert quick key should route through the same Command Center quick-key callback bucket as the other special keys",
            content.contains("NovaQuickMenuActionId.QUICK_INSERT,") &&
                content.contains("NovaQuickMenuActionId.QUICK_CTRL_V -> onQuickKey(action.id)")
        )
        assertTrue(
            "Insert quick key should route through the existing Windows VK_INSERT translator path",
            menu.contains("NovaQuickMenuActionId.QUICK_INSERT -> keys(KeyboardTranslator.VK_INSERT)")
        )
        assertTrue(
            "legacy Send special keys menu should also expose Insert for users entering through More Keys",
            legacyGameMenu.contains("R.string.game_menu_send_keys_insert") &&
                legacyGameMenu.contains("KeyboardTranslator.VK_INSERT.toShort()")
        )
        assertTrue(
            "Insert label should be public-resource backed like the other special keys",
            strings.contains("<string name=\"game_menu_send_keys_insert\">Insert</string>")
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
    fun libraryHeroExposesEndSessionForOwnedActiveStreams() {
        val source = readNovaLibraryActivity()
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val hero = source.section(
            "private fun NovaLibraryHomeHero(",
            "private fun NovaLibraryHeroFallbackArtwork("
        )

        assertTrue(
            "owned active-session hero should expose a direct End session recovery action for stale host/game sessions",
            mapper.contains("secondaryActionLabel = if (session.ownedByClient) \"End session\" else null") &&
                mapper.contains("Resume this stream, or end it if the host game is stale.")
        )
        assertTrue(
            "Library screen should wire the hero secondary action to the confirmed end-session path",
            source.contains("onSecondaryAction = {") &&
                source.contains("activeSession?.let(onEndSession)") &&
                hero.contains("hero.secondaryActionLabel") &&
                hero.contains("onSecondaryAction")
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

    @Test
    fun gameDetailLaunchOptionsUseActionableModeState() {
        val launchControls = readNovaGameDetailSheet().section(
            "private fun LaunchControls(",
            "@Composable\nprivate fun LaunchModeChoicePill("
        )

        assertTrue(launchControls.contains("uiState.showLaunchOptionsButton"))
        assertTrue(launchControls.contains("uiState.showLaunchModeSummary"))
        assertFalse(launchControls.contains("uiState.launchOptionsEnabled"))
    }

    @Test
    fun gameDetailLaunchOptionsAvoidRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val launchOptions = detail.section(
            "private fun showLaunchOptions(",
            "private fun optionLabel("
        )

        assertTrue(
            "Launch Options runs from the Compose game detail sheet and must not use raw AlertDialog.Builder; Retroid Portable Chrome routes this through Nova glass option panels",
            !launchOptions.contains("AlertDialog.Builder(") &&
                detail.contains("data class NovaLaunchOptionsState") &&
                detail.contains("private fun NovaLaunchOptionsSheet(")
        )
    }

    @Test
    fun gameDetailProfilePreferenceAvoidsRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")
        val profileOptions = detail.section(
            "private fun showProfilePreferenceOptions(",
            "private fun showSteamLaunchModeOptions("
        )

        assertTrue(
            "AI Preference/Profile selector runs from the Compose game detail sheet and must not use raw AlertDialog.Builder on Retroid Portable Chrome",
            !profileOptions.contains("AlertDialog.Builder(") &&
                detail.contains("data class NovaProfilePreferenceOptionsState") &&
                detail.contains("private fun NovaProfilePreferenceSheet(")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.containsRegex(pattern: String): Boolean =
        Regex(pattern).containsMatchIn(this)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
