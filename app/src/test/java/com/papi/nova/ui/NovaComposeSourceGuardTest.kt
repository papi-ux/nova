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
                quickMenuContent.contains("import androidx.compose.runtime.withFrameNanos") &&
                content.contains("val initialFocusRequester = remember { FocusRequester() }") &&
                content.contains("LaunchedEffect(Unit)") &&
                content.contains("withFrameNanos { }") &&
                content.contains("runCatching { initialFocusRequester.requestFocus() }")
        )
        assertTrue(
            "Command Center should attach that initial focus requester to the visible Close button in both compact and wide header layouts",
            content.contains("NovaQuickMenuHeader(state, callbacks, initialFocusRequester)") &&
                header.contains("initialFocusRequester: FocusRequester") &&
                header.contains("NovaQuickMenuCloseButton(callbacks, initialFocusRequester)") &&
                closeButton.contains("initialFocusRequester: FocusRequester") &&
                closeButton.contains(".focusRequester(initialFocusRequester)")
        )
        val focusRequesterIndex = closeButton.indexOf(".focusRequester(initialFocusRequester)")
        val semanticsIndex = closeButton.indexOf(".semantics")
        assertTrue(
            "Close button should attach focusRequester before later modifiers so it targets the NovaActionButton focusable node",
            focusRequesterIndex >= 0 && semanticsIndex > focusRequesterIndex
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
            "private fun NovaLibraryHomeHero("
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
            "Stage, Grid, Compact, and poster-title visibility should be wired into production rendering",
            activity.contains("val layoutMode = model.optionsState.layoutMode") &&
                activity.contains("layoutMode == NovaLibraryLayoutMode.STAGE") &&
                activity.contains("NovaLibraryStage(") &&
                activity.contains("layoutMode = layoutMode") &&
                activity.contains("layoutMode = NovaLibraryLayoutMode.COMPACT") &&
                activity.contains("showPosterTitle = model.optionsState.showPosterTitles")
        )
        assertTrue(
            "quick options strings should cover the Sort/Layout/Poster title surface",
            strings.contains("name=\"nova_library_options_title\">Library Options") &&
                strings.contains("name=\"nova_library_options_sort_recent\">Recent") &&
                strings.contains("name=\"nova_library_options_sort_name_asc\">Name A-Z") &&
                strings.contains("name=\"nova_library_options_sort_name_desc\">Name Z-A") &&
                strings.contains("name=\"nova_library_options_sort_source\">Source") &&
                strings.contains("name=\"nova_library_options_sort_hdr_first\">HDR First") &&
                strings.contains("name=\"nova_library_options_layout_stage\">Stage") &&
                strings.contains("name=\"nova_library_options_layout_grid\">Grid") &&
                strings.contains("name=\"nova_library_options_layout_compact\">Compact") &&
                strings.contains("name=\"nova_library_options_poster_titles_title\">Poster Titles") &&
                strings.contains("name=\"nova_library_options_poster_titles_hide\">Plain Artwork")
        )
    }

    @Test
    fun libraryPersistentChromeStaysOutOfTheGamesWay() {
        val activity = readNovaLibraryActivity()
        val stage = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val sharedToolbar = stage.section(
            "internal fun NovaLibraryLandscapeToolbarContent(",
            "internal fun NovaLibraryStage(",
        )
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryHomeHero("
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
            sharedToolbar.contains("surfaces.panel.copy(alpha = 0.72f * LocalNovaMenuOpacityScale.current)") &&
                sharedToolbar.contains(".padding(horizontal = 10.dp, vertical = 5.5.dp)") &&
                sharedToolbar.contains("landscapeToolbarHeightDp(largeText)") &&
                sharedToolbar.contains("fontSize = 16.sp") &&
                landscapeToolbar.contains("NovaLibraryLandscapeToolbarContent(")
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
                optionsSheet.contains("NovaMenuPreferences.readabilityScrimAlpha(")
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
            "private fun NovaLibraryHomeHero("
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
                strings.contains("name=\"nova_system_menu_switch_host\">Switch Host") &&
                strings.contains("name=\"nova_system_menu_settings\">Settings") &&
                strings.contains("name=\"nova_system_menu_polaris_sync\">Polaris Sync") &&
                strings.contains("name=\"nova_system_menu_manage_server\">Manage Server") &&
                strings.contains("name=\"nova_system_menu_help_diagnostics\">Help / diagnostics") &&
                strings.contains("name=\"nova_system_menu_about\">About Nova") &&
                strings.contains("name=\"nova_system_menu_about_toast\">%1\$s")
        )
    }

    @Test
    fun libraryCoverLoadingIsKeyedOutsideAndroidViewUpdate() {
        val source = readNovaLibraryActivity()
        val chrome = readSource("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val posterCard = readSource("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val focusedRevisionKey = "key(PolarisApiClient.artworkPresentationKey(targetGame, PolarisGame.ARTWORK_KIND_POSTER))"
        assertTrue(
            "shared Activity and Stage poster views should be revision-aware",
            posterCard.contains("PolarisApiClient.artworkPresentationKey(") &&
                posterCard.contains("PolarisGame.ARTWORK_KIND_POSTER") &&
                chrome.contains("PolarisApiClient.artworkPresentationKey(") &&
                chrome.contains("PolarisGame.ARTWORK_KIND_POSTER")
        )
        assertTrue(
            "focused backdrop and home Hero cover should also recreate when the Poster revision changes",
            source.split(focusedRevisionKey).size - 1 >= 1 &&
                chrome.contains("R.id.nova_artwork_presentation_key")
        )
        assertTrue(
            "shared poster load should be fenced by the keyed ImageView presentation identity",
            posterCard.contains("view.getTag(R.id.nova_artwork_presentation_key) != posterPresentationKey") &&
                posterCard.contains("posterLoader?.invoke(view, game) ?: apiClient.loadCoverInto(view, game)")
        )
        assertTrue(
            "shared poster update should persist the presentation key before loading",
            posterCard.contains("view.setTag(R.id.nova_artwork_presentation_key, posterPresentationKey)")
        )
    }

    @Test
    fun libraryCinematicBackdropUsesCachedHeroThenPosterFallback() {
        val backdrop = readSource("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")

        assertTrue(backdrop.contains("artworkKind = if (hasCachedHero)"))
        assertTrue(backdrop.contains("PolarisGame.ARTWORK_KIND_HERO"))
        assertTrue(backdrop.contains("PolarisGame.ARTWORK_KIND_POSTER"))
        assertTrue(backdrop.contains("apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)"))
        assertTrue(backdrop.contains("apiClient.loadCoverInto(view, target.game)"))
        assertFalse(backdrop.contains("coverUrl.trim().isNotEmpty()"))
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
            "private fun NovaLibraryHomeHero("
        )
        val hero = source.section(
            "private fun NovaLibraryHomeHero(",
            "private fun NovaLibraryLandscapeToolbar("
        )

        val stage = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val landscape = screen.blockStartingAt("if (isLandscape) {")

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
                screen.contains("NovaLibraryLandscapeStageShell(") &&
                stage.contains("controllerHintBarBottomPaddingDp(isLandscape = true)") &&
                stage.contains("NovaLibraryUiStateMapper.landscapeContentSpacingDp().dp")
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
            "hero card opens the game and falls back to the primary action, so a running game is still reachable while the grid omits it",
            hero.contains(".combinedClickable(onClick = onOpenDetail ?: onPrimaryAction)") &&
                hero.indexOf(".combinedClickable(onClick = onOpenDetail ?: onPrimaryAction)") in 0 until hero.indexOf(".focusable()")
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
            hero.indexOf("NovaLibraryHeroArtwork(") in 0 until hero.indexOf("Column(\n                // fill = false") &&
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
            "@Composable\n    private fun NovaLibraryHomeHero("
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
                mapper.contains("primaryActionLabel = \"Manage Library\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.MANAGE_LIBRARY")
        )
        assertTrue(
            "recent-empty state should invite users back to the full library instead of sounding like an error",
            mapper.contains("NovaLibraryEmptyState.RECENT -> NovaLibraryRecoveryUiState(") &&
                mapper.contains("primaryActionLabel = \"View All Games\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS")
        )
        assertTrue(
            "source no-results should name the selected source and use one direct clear-source CTA",
            mapper.contains("title = \"No ${'$'}sourceLabel games\"") &&
                mapper.contains("primaryActionLabel = \"Clear Source\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS") &&
                mapper.contains("private fun sourceDisplayName(sourceName: String?)")
        )
        assertTrue(
            "filtered empty state should keep Clear filters as the direct escape hatch",
            mapper.contains("NovaLibraryEmptyState.FILTERED -> NovaLibraryRecoveryUiState(") &&
                mapper.contains("primaryActionLabel = \"Clear Filters\"") &&
                mapper.contains("primaryAction = NovaLibraryRecoveryAction.CLEAR_FILTERS")
        )
        assertTrue(
            "offline/load failure recovery should distinguish retryable connection failures from Polaris API/server failures",
            mapper.contains("fun loadFailureRecoveryState(message: String)") &&
                mapper.contains("title = \"Host offline\"") &&
                mapper.contains("primaryActionLabel = \"Retry\"") &&
                mapper.contains("title = \"Polaris unavailable\"") &&
                mapper.contains("primaryActionLabel = \"Manage Server\"")
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
        val posterFocus = source.section(
            "private fun rememberLibraryPosterFocusRequester(",
            "@Composable\n    private fun NovaLibraryLoadingGrid("
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
            "shared poster call sites should request focus when they match the remembered game",
            source.windowed("rememberLibraryPosterFocusRequester(".length)
                .count { it == "rememberLibraryPosterFocusRequester(" } == 3 &&
                posterFocus.contains("val focusRequester = remember { FocusRequester() }") &&
                posterFocus.contains("if (restoreFocus && !restoreAttempted)") &&
                posterFocus.contains("focusRequester.requestFocus()")
        )
        assertTrue(
            "filter chips should request focus when they match the remembered filter",
            filterChip.contains("val focusRequester = remember { FocusRequester() }") &&
                filterChip.contains(".focusRequester(focusRequester)") &&
                filterChip.contains("if (restoreFocus && !restoreAttempted)")
        )
    }

    @Test
    fun gridCompactAndRecentUseSharedCinematicPosterCallSites() {
        val activity = readNovaLibraryActivity()
        val content = activity.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )
        val recentRail = activity.section(
            "private fun NovaLibraryRecentRail(",
            "@Composable\n    private fun NovaLibraryLoadingGrid("
        )

        assertEquals(
            "Grid/Compact and Recent should be the Activity's only two shared-poster call sites",
            2,
            activity.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" },
        )
        assertEquals(
            "the main grid should render one shared poster per keyed game",
            1,
            content.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" },
        )
        assertTrue(
            "the main grid should pass the mapper-selected Grid or Compact mode into the shared poster",
            content.contains("layoutMode = layoutMode") &&
                content.contains("showPosterTitle = model.optionsState.showPosterTitles") &&
                content.contains("onOpenDetail = { onOpenDetail(game) }")
        )
        assertEquals(
            "the Recent/Continue rail should render one shared poster per keyed game",
            1,
            recentRail.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" },
        )
        assertTrue(
            "the Recent/Continue rail should use Compact presentation and preserve title/detail callbacks",
            recentRail.contains("layoutMode = NovaLibraryLayoutMode.COMPACT") &&
                recentRail.contains("showPosterTitle = showPosterTitles") &&
                recentRail.contains("onOpenDetail = { onOpenDetail(game) }")
        )
        assertTrue(
            "Stage poster loading should receive a stable remembered loader rather than a new recomposition identity",
            content.contains("val stablePosterLoader = remember(apiClient)") &&
                content.contains("posterLoader = stablePosterLoader")
        )
        assertFalse(
            "the retired fixed-height bordered library card must be deleted after both call sites migrate",
            activity.contains("private fun NovaLibraryGameCard(") ||
                activity.contains("private fun NovaLibraryCardTitleScrim(") ||
                activity.contains("private fun NovaLibraryCardBadgeRow(")
        )
        listOf(
            "NovaLibraryCardTitleScrim(",
            "NovaLibraryCardBadgeRow(",
            ".background(surfaces.focusHalo.copy(alpha = 0.28f))",
            ".border(4.dp, surfaces.focusRing",
            "R.string.nova_library_card_action_details",
        ).forEach { forbidden ->
            assertFalse("migrated Activity poster call sites must not render legacy visual chrome: $forbidden", content.contains(forbidden) || recentRail.contains(forbidden))
        }
    }

    @Test
    fun gridAndCompactLoadingPlaceholdersUsePortraitPosterGeometry() {
        val activity = readNovaLibraryActivity()
        val content = activity.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail("
        )
        val loading = activity.section(
            "private fun NovaLibraryLoadingGrid(",
            "private fun NovaLibraryRecoveryState("
        )

        assertFalse(
            "shared-poster grids should no longer thread the retired fixed game-card height",
            content.contains("gameCardHeightDp") || loading.contains("gameCardHeightDp") || loading.contains("cardHeightDp")
        )
        assertTrue(
            "loading posters should use the same mapper-owned 2:3 artwork ratio and mode-specific focus gutter",
            loading.contains("NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)") &&
                loading.contains(".padding(horizontal = presentationSpec.focusGutterDp.dp)") &&
                loading.contains(".aspectRatio(NovaLibraryUiStateMapper.posterAspectRatio())")
        )
        assertFalse(
            "loading posters must not retain the retired short landscape crop",
            loading.contains(".height(cardHeightDp.dp)") || loading.contains("112.dp") || loading.contains("88.dp")
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
        val sharedToolbar = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt").section(
            "internal fun NovaLibraryLandscapeToolbarContent(",
            "internal fun NovaLibraryStage(",
        )
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
            sharedToolbar.contains("R.string.nova_system_menu_status_polaris_ready") &&
                sharedToolbar.contains("R.string.nova_library_results_format") &&
                landscapeToolbar.contains("NovaLibraryLandscapeToolbarContent(") &&
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
        val detail = readNovaGameDetail()
        val detailsPanel = detail.section(
            "private fun GameDetailsPanel(",
            "@Composable\ninternal fun LaunchProfilePrimaryNotice("
        )
        assertTrue(
            "Retroid landscape first paint should keep either Hero or Poster identity inside the compact launch header ceiling",
            detailsPanel.contains(".heightIn(min = 136.dp)") &&
                detailsPanel.contains("contentPadding = PaddingValues(12.dp)") &&
                detailsPanel.contains(".height(136.dp)") &&
                detailsPanel.contains(".width(108.dp)") &&
                detailsPanel.contains("fontSize = if (compact) 17.sp else 20.sp") &&
                detailsPanel.contains("lineHeight = if (compact) 19.sp else 22.sp") &&
                detailsPanel.contains("maxLines = if (compact) 1 else 2")
        )
        assertFalse(
            "game detail should not keep the old oversized first-paint panel that pushed launch mode choices below the fold",
            detailsPanel.contains(".heightIn(min = 172.dp)") ||
                detailsPanel.contains(".width(126.dp)") ||
                detailsPanel.contains("fontSize = 22.sp")
        )
        assertTrue(
            "the primary launch and the mode choices stay compact enough for Retroid landscape. " +
                "This used to measure the pinned footer, which the window replaced with the " +
                "action rail; the floor now lives on the rail's own action height",
            detail.contains("internal val NovaGameDetailActionHeight = 48.dp") &&
                detail.contains("heightIn(min = NovaGameDetailActionHeight)") &&
                detail.contains("NOVA_DETAIL_ROW_MIN_HEIGHT = 48.dp")
        )
    }

    @Test
    fun gameDetailLaunchControlsPrioritizePrimaryPlayFocus() {
        val detail = readNovaGameDetail()
        val overview = detail.section(
            "internal fun NovaGameDetailOverview(",
            "private fun NovaGameDetailTitle("
        )
        val actions = detail.section(
            "private fun NovaGameDetailActions(",
            "private fun NovaGameDetailAction("
        )

        assertTrue(
            "the primary action holds first focus, and nothing scrolls above it",
            detail.contains("val playFocusRequester = remember { FocusRequester() }") &&
                actions.contains(".focusRequester(playFocusRequester)") &&
                actions.contains("primary = activeSession?.watchOnly != true") &&
                !overview.contains("verticalScroll")
        )
        assertTrue(
            "something has to actually ask. This guard used to pin only the wiring -- the " +
                "requester existed and was attached -- while the requestFocus() call sat in a " +
                "composable that had lost its last caller when this window replaced the bottom " +
                "sheet, so nothing requested focus at all and the d-pad started wherever the " +
                "first focusable happened to be. The ask lives beside the button it names.",
            actions.contains("LaunchedEffect(playFocusable)") &&
                actions.contains("runCatching { playFocusRequester.requestFocus() }")
        )
        assertFalse(
            "and the composable that stranded it is gone rather than left to strand another",
            detail.contains("internal fun NovaGameDetailLaunchFooter(")
        )
        assertTrue(
            "the action lane is one row, so a D-pad walk never leaves it",
            actions.contains("Row(") &&
                actions.contains("horizontalArrangement = Arrangement.spacedBy(10.dp)")
        )
        assertTrue(
            "every focusable action clears the accessible target floor",
            detail.contains("internal val NovaGameDetailActionHeight = 48.dp") &&
                detail.contains("heightIn(min = NovaGameDetailActionHeight)")
        )
        assertTrue(
            "the rail is a fixed three, so a D-pad walk cannot find a different number of " +
                "nodes on a different game; a review still replaces it with its own answers",
            actions.contains("NovaGameDetailDestination.PLAY_SETUP") &&
                actions.contains("NovaGameDetailDestination.ARTWORK") &&
                actions.contains("if (reviewExpanded)") &&
                actions.contains("onRetryHighFps") &&
                actions.contains("onResetProfile")
        )
        assertFalse(
            "nothing gates a rail node any more: what showLaunchModeAction used to remove " +
                "is a row inside Play Setup rather than an action beside it",
            actions.contains("if (showLaunchModeAction)")
        )
        assertFalse(
            "the primary launch button must not sit inside a scrolling body",
            actions.contains("verticalScroll")
        )
    }

    @Test
    fun playSetupKeepsModeChoiceInlineAndHoldsTheProfileControlsToo() {
        val detail = readNovaGameDetail()

        assertTrue(
            "Headless/Virtual stay one press away and say what they would do while you pick. " +
                "They moved from rows into the comparison strip, which is still inline: a picker " +
                "raised over the destination would cost a press and re-introduce the options " +
                "drawer that inline selection was added to remove",
            detail.contains("NovaPlaySetupComparison(") &&
                detail.contains("onSelect = { onLaunchModeSelected(\"headless\") }") &&
                detail.contains("onSelect = { onLaunchModeSelected(\"virtual_display\") }") &&
                detail.contains("consequence = stringResource(")
        )
        assertTrue(
            "one destination holds the whole decision: where it runs, the launch settings, the " +
                "tuning profile and Steam launch. Splitting these across two drawers is what " +
                "made each of them carry half of the other's subject",
            detail.contains("onClick = onLaunchOptions,") &&
                detail.contains("onClick = onProfilePreference,") &&
                detail.contains("SteamLaunchModeCard(") &&
                detail.contains("LaunchProfileSummaryActions(")
        )
        assertFalse(
            "LaunchControls served the destination that no longer exists; leaving it behind " +
                "would leave a second way to draw the same choice",
            detail.contains("private fun LaunchControls(")
        )
    }

    @Test
    fun gameDetailKeepsMangoHudOutOfPrimaryLaunchDrawer() {
        val detail = readNovaGameDetail()
        val sheetContent = detail.section(
            "fun NovaGameDetailContent(",
            "@Composable\nprivate fun NovaDetailPanel("
        )

        assertFalse(
            "MangoHUD should not render as a prominent switch card in the main launch drawer",
            sheetContent.contains("MangoHudCard(")
        )
        assertTrue(
            "when MangoHUD is already enabled, Play Setup shows only a passive status, and it " +
                "comes after the choices rather than competing with them",
            sheetContent.contains("if (mangoHudEnabled) {") &&
                sheetContent.contains("MangoHudPassiveStatus(") &&
                sheetContent.indexOf("NovaPlaySetupBody(") in
                0 until sheetContent.indexOf("MangoHudPassiveStatus(")
        )
    }

    @Test
    fun gameDetailCoverLoadingIsKeyedByGameIdentity() {
        val source = readNovaGameDetail()
        val detailsPanel = source.section(
            "private fun GameDetailsPanel(",
            "@Composable\ninternal fun LaunchProfilePrimaryNotice("
        )

        assertTrue(
            "detail sheet cover view should follow artwork revision changes",
            detailsPanel.contains("key(PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_POSTER))") &&
                detailsPanel.contains("coverLoader(this)")
        )
        assertTrue("logo view should follow artwork revision changes", source.contains("key(logoPresentationKey)"))
        assertTrue(
            "successful artwork mutations should refresh detail state before propagation",
            source.contains("currentGame = currentGame.copy(artwork = manifest)\n            refreshUiState()")
        )
    }

    @Test
    fun gameDetailUsesHeroBackdropLogoTransformIconIdentityAndPosterFallback() {
        val source = readNovaGameDetail()
        val overview = source.section(
            "internal fun NovaGameDetailOverview(",
            "private fun NovaGameDetailTitle("
        )
        val title = source.section(
            "private fun NovaGameDetailTitle(",
            "private fun NovaGameDetailStatusLine("
        )

        assertTrue(
            "the hero should be the full-bleed backdrop rather than a 136dp panel thumbnail, reusing the library's own backdrop so hero-to-poster fallback and the theme scrims come with it",
            overview.contains("NovaLibraryCinematicBackdrop(") &&
                overview.contains("strength = 1f") &&
                !overview.contains(".height(136.dp)")
        )
        assertTrue(
            "curated logo artwork should become the title treatment at real size, still keyed by presentation revision",
            title.contains("if (logoAvailable)") &&
                title.contains("key(logoPresentationKey)") &&
                title.contains("logoLoader(this)") &&
                title.contains("maxWidth = 200.dp, maxHeight = 64.dp")
        )
        assertTrue(
            "a game with no curated logo falls back to its name, and the fallback is a title rather than a poster card",
            title.contains("text = game.name") &&
                title.contains("nova-game-detail-title")
        )
    }

    @Test
    fun artworkPreferencesAreCollapsedAtBottomAndUseManifestArtwork() {
        val source = readNovaGameDetail()
        val content = source.section(
            "fun NovaGameDetailContent(",
            "@Composable\nprivate fun NovaGameDetailScrollableContent("
        )
        assertTrue(
            "artwork curation should be its own destination, and a full-screen one: the studio lays itself out as a Row of weighted Columns and cannot fold into a side panel",
            content.contains("NovaGameDetailDestination.ARTWORK -> NovaGameDetailFullScreen(") &&
                content.contains("NovaArtworkStudio(")
        )

        val panel = readSource("src/main/java/com/papi/nova/ui/NovaArtworkStudio.kt")
        assertTrue(
            "artwork preferences should start collapsed wherever the studio is one row among many",
            panel.contains("initiallyExpanded: Boolean = false") &&
                panel.contains("var expanded by remember(initialQuery) { mutableStateOf(initiallyExpanded) }")
        )
        assertTrue(
            "the destination that is nothing but the studio should open it, not cost a tap and leave the window empty",
            content.contains("NovaArtworkStudio(\n                    initiallyExpanded = true,")
        )
        assertTrue("artwork header should toggle expansion", panel.contains("clickable { expanded = !expanded }") && panel.contains("if (expanded)"))
        assertTrue("Studio should show persisted identity and composition beside the live draft", panel.contains("R.string.nova_artwork_current_match") && panel.contains("R.string.nova_artwork_current_composition") && panel.contains("R.string.nova_artwork_live_preview"))
        assertTrue("Studio should render Poster, Hero, Logo, and Icon composition layers", NovaArtworkKinds.ALL.all { kind -> panel.contains("kind = NovaArtworkKinds.${kind.uppercase()}") })
    }

    @Test
    fun artworkProviderFailuresAreNotReportedAsNoMatches() {
        val sheet = readNovaGameDetail()
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val strings = readSource("src/main/res/values/strings.xml")
        val searchHandler = sheet.section(
            "onSearchArtwork = { query ->",
            "onIdentitySelected = { candidate ->",
        )

        assertTrue(
            "candidate search should surface HTTP, envelope, and malformed-body failures without retaining provider response content",
            api.contains("throw IOException(\"artwork candidate search HTTP \${response.code}\")") &&
                api.contains("throw IOException(\"invalid artwork candidate search response\")") &&
                api.contains("catch (_: JSONException)") &&
                api.contains("throw IOException(\"invalid artwork JSON\")") &&
                api.contains("Nova: artwork candidate search failed")
        )
        assertTrue(
            "detail UI should reserve No matches for successful empty searches, rethrow cancellation, and map ordinary failures separately",
            searchHandler.contains("try {") &&
                searchHandler.contains("catch (e: CancellationException)") &&
                searchHandler.contains("throw e") &&
                searchHandler.contains("catch (_: Exception)") &&
                !searchHandler.contains("runCatching") &&
                searchHandler.contains("R.string.nova_artwork_search_failed") &&
                searchHandler.contains("R.string.nova_artwork_no_matches")
        )
        assertTrue(
            "provider failure copy should direct the user to Polaris without pretending a search succeeded",
            strings.contains("name=\"nova_artwork_search_failed\">Artwork search unavailable. Check SteamGridDB in Polaris and try again.</string>")
        )
    }

    @Test
    fun artworkRequestsUseFreshTlsStateWithoutCachingTheDerivedClient() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        assertTrue(
            "production artwork calls should rebuild policy from the fresh per-call TLS client",
            api.contains("private fun executeArtwork(request: Request) = buildArtworkHttpClientForCall(::clientForCall).newCall(") &&
                api.contains("SSLContext.getInstance(\"TLS\").apply") &&
                api.contains(".sslSocketFactory(sslContext.socketFactory, trustManager)")
        )
        assertFalse(
            "artwork client must not be cached across mTLS calls",
            api.contains("private val artworkHttpClient")
        )
    }

    @Test
    fun artworkFetchLogsUseFixedClassificationWithoutUrlsOrExceptionMessages() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val fetch = api.section(
            "private fun fetchArtwork(url: String)",
            "/**\n     * Toggle MangoHud",
        )
        assertTrue(fetch.contains("val requestClass = artworkRequestLogLabel(url)"))
        assertTrue(fetch.contains("e.javaClass.simpleName"))
        assertFalse(fetch.contains("\$url"))
        assertFalse(fetch.contains("errorMessage(e)"))
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
            content.contains("NovaLibraryUiStateMapper.shouldShowLoadFailure(") &&
                content.contains("loadErrorMessage = loadErrorMessage") &&
                content.contains("heroReason = model.hero.reason") &&
                content.contains("NovaLibraryUiStateMapper.loadFailureRecoveryState(") &&
                content.contains("loadErrorMessage.orEmpty()") &&
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
                mapper.contains("primaryActionLabel = \"Manage Server\"") &&
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
            "@Composable\n    private fun NovaLibraryLoadingGrid("
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
            "@Composable\n    private fun NovaLibraryHomeHero("
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
    fun libraryUsesCinematicHintsWhileDetailAndSettingsKeepTheReusableBar() {
        val focusComponents = readNovaFocusComponents()
        val cinematicChrome = readSource("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val library = readNovaLibraryActivity()
        val libraryScreen = library.section(
            "private fun NovaLibraryScreen(",
            "@Composable\n    private fun NovaLibraryHomeHero("
        )
        val detail = readNovaGameDetail()
        val detailContent = detail.section(
            "fun NovaGameDetailContent(",
            "@Composable\nprivate fun NovaDetailPanel("
        )
        val settings = readNovaSettingsScreen()
        val settingsContent = settings.section(
            "private fun NovaSettingsContent(",
            "@Composable\nprivate fun NovaSettingsCompactHeader("
        )

        assertTrue(
            "shared focus components should retain the reusable model and bar for non-library surfaces",
            focusComponents.contains("data class NovaControllerHint(") &&
                focusComponents.contains("fun NovaControllerHintBar(") &&
                focusComponents.contains(".horizontalScroll(rememberScrollState())") &&
                focusComponents.contains(".heightIn(min = 30.dp)") &&
                focusComponents.contains("contentDescription = hintContentDescription")
        )
        assertTrue(
            "library should use its borderless full-width cinematic hint renderer while preserving reserved footer space",
            cinematicChrome.contains("internal fun NovaLibraryCinematicControllerHints(") &&
                libraryScreen.contains("val controllerHintBarBottomPadding = NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp") &&
                libraryScreen.contains("val showLandscapeControlRail = NovaLibraryUiStateMapper.showLandscapeControlRail()") &&
                libraryScreen.contains("if (isLandscape) {") &&
                libraryScreen.contains("NovaLibraryLandscapeToolbar(") &&
                libraryScreen.contains(".padding(bottom = controllerHintBarBottomPadding)") &&
                libraryScreen.contains("NovaLibraryCinematicControllerHints(") &&
                libraryScreen.contains("hints = visibleControllerHints") &&
                libraryScreen.contains("semanticsDescription = controllerHintDescription") &&
                libraryScreen.contains(".align(Alignment.BottomCenter)") &&
                libraryScreen.contains(".fillMaxWidth()")
        )
        assertFalse(
            "library alone should stop using the shared bordered bar and obsolete left-start offset",
            libraryScreen.contains("NovaControllerHintBar(") ||
                library.contains("import com.papi.nova.ui.compose.NovaControllerHintBar") ||
                libraryScreen.contains("controllerHintBarLandscapeStartPadding")
        )
        assertTrue(
            "the game detail window keeps the shared hint model; the Overview paints it borderless on the artwork while destinations keep the reusable bar",
            detail.contains("List<NovaControllerHint>") &&
                detail.contains("novaGameDetailOverviewHints()") &&
                detail.contains("NovaControllerHintBar(") &&
                detail.contains("nova_controller_hint_back")
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
            "drawer surface should consume the literal shared outer panel at x=0 instead of a theme-glass multiplier",
            content.contains(".background(surfaces.panel)")
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
    fun inGameOverlayNestedOpacityTokensAreSharedByCommandCenterAndHud() {
        val tokenPath = Path.of("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        assertTrue(
            "Command Center and NovaHUD should share named nested opacity tokens instead of local magic alpha literals",
            Files.exists(tokenPath)
        )
        val tokens = readSource("src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt")
        val commandCenter = readNovaQuickMenuContent()
        val hud = readNovaStreamHudContent()

        assertTrue(
            "token file should name the nested overlay alpha contract for controls, scrim, and borders",
            tokens.contains("object NovaInGameOverlayAlpha") &&
                tokens.contains("const val NestedTile") &&
                tokens.contains("const val NestedControl") &&
                tokens.contains("const val CommandCenterScrim") &&
                tokens.contains("const val Border")
        )
        assertTrue(
            "Command Center should use literal outer opacity plus shared nested scrim, tile, control, border, and handle tokens",
            commandCenter.contains("NovaInGameOverlayAlpha.CommandCenterScrim") &&
                commandCenter.contains(".background(surfaces.panel)") &&
                commandCenter.contains("NovaInGameOverlayAlpha.NestedTile") &&
                commandCenter.contains("NovaInGameOverlayAlpha.NestedControl") &&
                commandCenter.contains("NovaInGameOverlayAlpha.Border") &&
                commandCenter.contains("NovaInGameOverlayAlpha.AccentHandle")
        )
        assertTrue(
            "NovaHUD should use its own literal outer opacity plus the same nested control/border token family",
            hud.contains(".background(surfaces.panel.copy(alpha = hudOpacityScale))") &&
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
        val endActiveSession = source.section(
            "private fun endActiveSession(",
            "private fun openServerManagement("
        )
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
            endActiveSession.contains("ComputerDetails.AddressTuple(streamHost, streamHttpPort)") &&
                endActiveSession.contains("ServerHelper.doQuit(") &&
                endActiveSession.contains("val generation = beginActiveSessionRefresh()") &&
                endActiveSession.contains("activeSession = null") &&
                endActiveSession.contains("scheduleActiveSessionFollowUpRefreshes(") &&
                endActiveSession.contains("clearOnly = true") &&
                endActiveSession.contains("generation = generation")
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
            mapper.contains("secondaryActionLabel = if (session.ownedByClient) \"End Session\" else null") &&
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

    @Test
    fun streamHudPerformanceSeparatesFpsFromDetailMetrics() {
        val source = readNovaStreamHudContent()
        val performanceHud = source.section(
            "private fun NovaStreamHudPerformance(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )

        assertTrue(
            "FPS display should place the FPS/target/sparkline and added detail metrics in separate bounded rows",
            performanceHud.contains("HudPerformancePrimaryRow(state)") &&
                performanceHud.contains("HudPerformanceDetailRow(state)")
        )

        val primaryRow = source.section(
            "private fun HudPerformancePrimaryRow(",
            "@Composable\nprivate fun HudPerformanceDetailRow("
        )
        val detailRow = source.section(
            "private fun HudPerformanceDetailRow(",
            "@Composable\nprivate fun NovaStreamHudMinimal("
        )

        assertTrue(primaryRow.contains("state.fpsLabel"))
        assertTrue(primaryRow.contains("state.targetFpsLabel"))
        assertTrue(primaryRow.contains("NovaHudSparkline("))
        assertFalse(primaryRow.contains("state.latencyLabel"))
        assertFalse(primaryRow.contains("state.bitrateLabel"))
        assertFalse(primaryRow.contains("state.resolutionLabel"))
        assertFalse(primaryRow.contains("state.codecLabel"))

        assertTrue(detailRow.contains("state.latencyLabel"))
        assertTrue(detailRow.contains("state.bitrateLabel"))
        assertTrue(detailRow.contains("state.resolutionLabel"))
        assertTrue(detailRow.contains("state.codecLabel"))
        assertFalse(detailRow.contains("state.fpsLabel"))
    }

    @Test
    fun streamHudZeroOpacityRemovesPanelShadowChrome() {
        val source = readNovaStreamHudContent()
        val panel = source.section(
            "private fun HudPanel(",
            "@Composable\nprivate fun HudDiagnosticStrip("
        )

        assertTrue(
            "0% NovaHUD opacity should remove the panel shadow instead of leaving faint ghost boxes",
            panel.contains(".shadow(16.dp * hudOpacityScale, panelShape, clip = false)")
        )
        assertFalse(
            "NovaHUD panel shadow must not remain fully opaque when glass opacity is 0%",
            panel.contains(".shadow(16.dp, panelShape, clip = false)")
        )
    }

    @Test
    fun artworkProgressAccountingIsPublishedAtomically() {
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")
        val workerAccounting = updater.section(
            "val status = try {",
            "results[gameIndex] = ItemResult",
        )

        assertTrue(
            workerAccounting.contains(
                "withContext(NonCancellable) {\n" +
                    "                        callbackLock.withLock {\n" +
                    "                            when (status)"
            ) &&
                workerAccounting.indexOf("when (status)") <
                    workerAccounting.indexOf("completed.incrementAndGet()") &&
                workerAccounting.indexOf("completed.incrementAndGet()") <
                    workerAccounting.indexOf("onProgress(snapshot())")
        )
    }


    @Test
    fun libraryOptionsExposeBoundedArtworkLibraryUpdateLifecycle() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val apiClient = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val updater = readSource("src/main/java/com/papi/nova/ui/NovaArtworkLibraryUpdater.kt")
        val optionsSheet = activity.section(
            "private fun NovaLibraryOptionsSheet(",
            "private fun NovaLibraryFilterSheet("
        )
        val startOwnership = updater.section(
            "fun start(games: List<PolarisGame>): Boolean",
            "fun cancel(): Boolean",
        )
        val cancelOwnership = updater.section(
            "fun cancel(): Boolean",
            "fun beginRefresh(): NovaArtworkLibraryRefreshToken",
        )
        val refreshPublication = updater.section(
            "fun publishRefresh(",
            "fun discardRefresh(",
        )
        assertTrue(
            activity.contains("private lateinit var artworkLibraryUpdateViewModel") &&
                activity.contains("ViewModelProvider(") &&
                activity.contains("repeatOnLifecycle(Lifecycle.State.STARTED)") &&
                activity.contains("apiClient.getAllGames()") &&
                activity.contains("val published = artworkLibraryUpdateViewModel.publishRefresh(") &&
                activity.contains(") { publishedGames ->") &&
                activity.contains("if (!published) return@launch") &&
                activity.contains("ownsVisibleRefreshState") &&
                activity.contains("allGames = publishedGames") &&
                activity.contains("artworkLibraryUpdateViewModel.start(selectedGames)") &&
                activity.contains("artworkLibraryUpdateViewModel.cancel()") &&
                !activity.contains("private var artworkLibraryUpdateJob: Job?")
        )
        assertTrue(
            updater.contains("class NovaArtworkLibraryUpdateViewModel(") &&
                updater.contains("scope = viewModelScope") &&
                updater.contains("parallelism = 2") &&
                updater.contains("if (activeJob != null) return false") &&
                !updater.contains("if (activeJob?.isActive == true) return false") &&
                !updater.contains("activeJob?.takeIf { it.isActive }") &&
                updater.contains("withContext(NonCancellable)") &&
                updater.contains("val workerCount = minOf(parallelism, eligibleGames.size)") &&
                updater.contains("currentCoroutineContext().ensureActive()") &&
                updater.contains("List(workerCount)") &&
                !updater.contains("eligibleGames.map { game ->") &&
                updater.contains("fun publishRefresh(") &&
                updater.contains("publish: (List<PolarisGame>) -> Unit") &&
                updater.contains("publish(merged)") &&
                updater.contains("if (token.id != refreshSequence) {") &&
                updater.contains("return@synchronized false") &&
                updater.contains("acknowledgedPublicationSequence") &&
                updater.contains("committed.sequence <= acknowledgedPublicationSequence") &&
                updater.contains("fun discardRefresh(token: NovaArtworkLibraryRefreshToken): Boolean") &&
                updater.contains("activeRefreshes")
        )
        assertTrue(
            refreshPublication.contains("try {") &&
                refreshPublication.contains("publish(merged)") &&
                refreshPublication.contains("} finally {") &&
                refreshPublication.contains("activeRefreshes.remove(token.id)") &&
                refreshPublication.indexOf("publish(merged)") <
                    refreshPublication.indexOf("acknowledgedPublicationSequence")
        )
        assertTrue(
            startOwnership.contains("launched.invokeOnCompletion { cause ->") &&
                startOwnership.contains("if (activeJob === launched)") &&
                startOwnership.contains("cause is CancellationException") &&
                startOwnership.indexOf("activeJob = launched") <
                    startOwnership.indexOf("launched.invokeOnCompletion") &&
                startOwnership.indexOf("launched.invokeOnCompletion") <
                    startOwnership.indexOf("launched.start()")
        )
        assertTrue(
            cancelOwnership.contains("return synchronized(runLock)") &&
                cancelOwnership.indexOf("onCancelAdmissionAttempt()") <
                    cancelOwnership.indexOf("return synchronized(runLock)") &&
                cancelOwnership.indexOf("val job = activeJob") <
                    cancelOwnership.indexOf("_snapshot.update") &&
                cancelOwnership.indexOf("_snapshot.update") <
                    cancelOwnership.indexOf("job.cancel()")
        )
        assertTrue(
            updater.contains("Channel<IndexedValue<PolarisGame>>(") &&
                updater.contains("capacity = maxOf(1, workerCount)") &&
                updater.contains("eligibleGames.withIndex().forEach { queue.send(it) }") &&
                updater.contains("} finally {\n                queue.close()") &&
                updater.contains("for ((gameIndex, game) in queue)") &&
                updater.contains(
                    "for ((gameIndex, game) in queue) {\n" +
                        "                    currentCoroutineContext().ensureActive()\n" +
                        "                    val status = try {\n" +
                        "                        update(game).status"
                ) &&
                updater.contains("val workers = List(workerCount)") &&
                updater.contains("workers.awaitAll()") &&
                updater.contains("producer.join()") &&
                !updater.contains("nextGameIndex") &&
                !updater.contains("eligibleGames.map { game ->")
        )
        assertTrue(
            apiClient.contains("internal fun paginateAllGames(") &&
                apiClient.contains("private fun getGamesPageOrThrow(") &&
                apiClient.contains("fun getAllGames(pageSize: Int = 100") &&
                apiClient.contains("paginateAllGames(pageSize)") &&
                apiClient.contains("getGamesPageOrThrow(limit = pageSize, offset = offset)") &&
                apiClient.contains("throw IOException(\"game library HTTP") &&
                apiClient.contains("offset += pageSize") &&
                apiClient.contains("if (games.size == before)") &&
                apiClient.contains("throw IOException(\"game library pagination made no progress\")") &&
                !apiClient.contains("putIfAbsent") &&
                apiClient.contains("fun updateArtworkForLibrary(gameId: String)") &&
                apiClient.contains("buildArtworkLibraryUpdateBody()") &&
                apiClient.contains("parseArtworkLibraryUpdateResponse(json)")
        )
        assertTrue(
            optionsSheet.contains("R.string.nova_artwork_library_update_title") &&
                optionsSheet.contains("NovaArtworkLibraryUpdateUiState.Running") &&
                optionsSheet.contains("LinearProgressIndicator(") &&
                optionsSheet.contains("onClick = ::cancelArtworkLibraryUpdate") &&
                optionsSheet.contains("onClick = { startArtworkLibraryUpdate(")
        )
        assertTrue(
            optionsSheet.contains("R.string.nova_artwork_library_update_policy") &&
                optionsSheet.contains("R.string.nova_artwork_library_update_preserve_custom") &&
                optionsSheet.contains("R.string.nova_artwork_library_update_retry")
        )
        assertTrue(
            strings.contains("name=\"nova_artwork_library_update_title\">Update Artwork Library") &&
                strings.contains("name=\"nova_artwork_library_update_policy\"") &&
                strings.contains("name=\"nova_artwork_library_update_preserve_custom\"") &&
                strings.contains("name=\"nova_artwork_library_update_cancel\"") &&
                strings.contains("name=\"nova_artwork_library_update_retry\"")
        )
    }


    @Test
    fun artworkLibraryCapabilityFailureExplainsServerMismatch() {
        val activity = readNovaLibraryActivity()
        val strings = readSource("src/main/res/values/strings.xml")
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        assertTrue(activity.contains("NovaArtworkLibraryUpdateFailure.SERVER_CAPABILITY_UNAVAILABLE"))
        assertTrue(activity.contains("R.string.nova_artwork_library_update_unavailable"))
        assertTrue(strings.contains("name=\"nova_artwork_library_update_unavailable\"") && strings.contains("Update Polaris"))
        assertTrue(api.contains("?: throw PolarisArtworkLibraryUpdateUnavailableException()") && api.contains("response.code == 404"))
    }

    // Task 9 plain-art/default/semantic source guards: BEGIN
    @Test
    fun task9SharedPosterCardKeepsMetadataInAccessibilityOnly() {
        val poster = readSource("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val card = poster.section(
            "internal fun NovaLibraryPosterCard(",
            "@Composable\nprivate fun NovaLibraryPosterArtwork(",
        )
        val artwork = poster.section(
            "private fun NovaLibraryPosterArtwork(",
            "@Composable\nprivate fun NovaLibraryPosterCaption(",
        )
        val metadata = poster.substringAfter("private fun novaLibraryPosterMetadata(game: PolarisGame): String =")
        val visualCard = card.substringAfter("    Column(")

        assertTrue(
            "the accessible poster label must retain title, nonblank source/category metadata, HDR, recent, and Details",
            card.contains("val metadata = novaLibraryPosterMetadata(game)") &&
                card.contains("add(title)") &&
                card.contains("if (metadata.isNotBlank()) add(metadata)") &&
                card.contains("if (game.hdrSupported) add(hdrLabel)") &&
                card.contains("if (game.lastLaunched > 0L) add(recentLabel)") &&
                card.contains("add(detailsLabel)") &&
                card.contains("contentDescription = accessibleLabel") &&
                metadata.contains("listOf(game.sourceLabel, game.categoryLabel)") &&
                metadata.contains(".filter(String::isNotBlank)")
        )
        assertTrue(
            "poster semantic activation must remain the detail-only path",
            card.contains("onOpenDetail: () -> Unit") &&
                card.contains(".combinedClickable(") &&
                card.contains("onClick = onOpenDetail")
        )
        listOf("onLaunch", "onStream", "launchGame", "startStream").forEach { forbidden ->
            assertFalse("poster semantics must not gain launch/stream callback $forbidden", card.contains(forbidden))
        }
        listOf(
            "NovaStagePill(",
            "NovaBadge(",
            "NovaMiniBadge(",
            "NovaLibraryCardBadgeRow(",
            "NovaLibraryCardTitleScrim(",
            "SELECTED",
            "Selected",
            "R.string.nova_library_badge_hdr",
            "R.string.nova_library_filter_recent",
            "R.string.nova_library_card_action_details",
            "sourceLabel",
            "categoryLabel",
        ).forEach { forbidden ->
            assertFalse("plain poster visual tree must not render $forbidden", visualCard.contains(forbidden))
        }
        assertFalse("poster artwork must not render text or pill overlays", artwork.contains("Text(") || artwork.contains("Pill("))
    }

    @Test
    fun task9SharedPosterCardUsesScaleOnlyWithoutVisualBadgesOrBorders() {
        val poster = readSource("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val card = poster.section(
            "internal fun NovaLibraryPosterCard(",
            "@Composable\nprivate fun NovaLibraryPosterArtwork(",
        )
        val artwork = poster.section(
            "private fun NovaLibraryPosterArtwork(",
            "@Composable\nprivate fun NovaLibraryPosterCaption(",
        )
        val implementation = poster.section(
            "internal fun NovaLibraryPosterCard(",
            "private fun novaLibraryPosterMetadata(game: PolarisGame): String =",
        )

        assertTrue(
            "shared PosterCard focus treatment must stay scale-led with the approved alpha/lift support",
            card.contains("val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)") &&
                card.contains("val scale by animateFloatAsState(") &&
                card.contains("targetValue = if (focused) presentationSpec.focusedScale else 1f") &&
                card.contains("val alpha by animateFloatAsState(") &&
                card.contains("targetValue = if (focused) 1f else presentationSpec.unfocusedAlpha") &&
                card.contains("val lift by animateDpAsState(") &&
                card.contains("targetValue = if (focused) NovaPosterFocusedLift else 0.dp") &&
                artwork.contains("scaleX = scale") &&
                artwork.contains("scaleY = scale") &&
                artwork.contains("translationY = -lift.toPx()") &&
                artwork.contains("this.alpha = alpha")
        )
        assertFalse("PosterCard implementation must remain borderless", implementation.contains(".border("))
        listOf(
            "NovaStagePill(",
            "NovaBadge(",
            "NovaMiniBadge(",
            "NovaLibraryCardBadgeRow(",
            "NovaLibraryCardTitleScrim(",
            "SELECTED",
            "Selected",
        ).forEach { forbidden ->
            assertFalse("PosterCard implementation must not restore visual overlay $forbidden", implementation.contains(forbidden))
        }
    }

    @Test
    fun task9StageGridCompactAndRecentUseOnlySharedPosterCard() {
        val stage = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stageGrid = stage.section(
            "private fun NovaLibraryStagePosterGrid(",
            "internal fun NovaLibraryStageRow(",
        )
        val stageRow = stage.section(
            "internal fun NovaLibraryStageRow(",
            "private fun stagePosterCaptionBudgetDp(",
        )
        val libraryGrid = activity.section(
            "private fun NovaLibraryContent(",
            "private fun NovaLibraryRecentRail(",
        )
        val recentContinue = activity.section(
            "private fun NovaLibraryRecentRail(",
            "private fun rememberLibraryPosterFocusRequester(",
        )

        assertTrue(
            "Stage and compact Stage rails must each call the shared PosterCard",
            stageGrid.countOccurrences("NovaLibraryPosterCard(") == 1 &&
                stageGrid.contains("layoutMode = NovaLibraryLayoutMode.STAGE") &&
                stageRow.countOccurrences("NovaLibraryPosterCard(") == 1 &&
                stageRow.contains("layoutMode = NovaLibraryLayoutMode.STAGE")
        )
        assertTrue(
            "Grid/Compact library content and Recent/Continue must call the shared PosterCard",
            libraryGrid.countOccurrences("NovaLibraryPosterCard(") == 1 &&
                libraryGrid.contains("layoutMode = layoutMode") &&
                recentContinue.countOccurrences("NovaLibraryPosterCard(") == 1 &&
                recentContinue.contains("layoutMode = NovaLibraryLayoutMode.COMPACT")
        )
        listOf("NovaLibraryStageCard(", "NovaLibraryGameCard(").forEach { legacy ->
            assertFalse("legacy poster definition/call must stay deleted: $legacy", stage.contains(legacy) || activity.contains(legacy))
        }
    }

    @Test
    fun task9StageIdentityUsesOneManifestIconAndOneRenderedTitle() {
        val stage = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val identity = stage.blockStartingAt("private fun NovaLibraryStageHero(")

        assertTrue(
            "Stage identity must use exactly one manifest-icon rendering path",
            identity.countOccurrences("AndroidView(") == 1 &&
                identity.countOccurrences("game.iconArtwork") == 1 &&
                identity.countOccurrences("PolarisGame.ARTWORK_KIND_ICON") == 2 &&
                identity.contains("artworkLoader(view, game, PolarisGame.ARTWORK_KIND_ICON)")
        )
        assertTrue(
            "Stage identity must render the game name exactly once in Nova text; the second"
                + " Text is the supporting source/category/capability line, not another title",
            identity.countOccurrences("Text(") == 2 &&
                identity.countOccurrences("text = game.name") == 1 &&
                identity.contains("stageHeroMetadata(game)")
        )
        assertFalse("Stage identity must never request logo artwork", identity.contains("ARTWORK_KIND_LOGO"))
        assertFalse(
            "Stage identity must not add a separate logo or wordmark path",
            identity.lowercase().contains("wordmark") || identity.lowercase().contains("logo")
        )
    }
    // Task 9 plain-art/default/semantic source guards: END

    @Test
    fun sharedPosterCardIsCleanOwnerAcrossStageGridAndRecentMigrations() {
        val poster = readSource("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val stage = readSource("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = readNovaLibraryActivity()
        val focusComponents = readNovaFocusComponents()
        val signatureStart = poster.indexOf("internal fun NovaLibraryPosterCard(")
        val signature = poster.substring(signatureStart, poster.indexOf("\n) {", signatureStart) + 4)
        val modifierStart = poster.indexOf("modifier = modifier", signatureStart)
        val requesterIndex = poster.indexOf(".then(focusRequesterModifier)", modifierStart)
        val focusObserverIndex = poster.indexOf(".onFocusChanged", modifierStart)
        val clickOwnerIndex = poster.indexOf(".combinedClickable(", modifierStart)

        assertTrue(poster.contains("internal fun NovaLibraryPosterCard("))
        assertTrue(poster.contains(".semantics(mergeDescendants = true)"))
        assertEquals(1, poster.windowed(".combinedClickable(".length).count { it == ".combinedClickable(" })
        assertFalse("combinedClickable already owns focus and activation", poster.contains(".focusable()"))
        assertFalse(poster.contains("import androidx.compose.foundation.focusable"))
        assertTrue(
            "FocusRequester and onFocusChanged must precede combinedClickable so they observe its focus target",
            requesterIndex >= 0 && requesterIndex < focusObserverIndex && focusObserverIndex < clickOwnerIndex,
        )
        assertTrue(signature.contains("onOpenDetail: () -> Unit"))
        assertFalse(signature.contains("onLaunch") || signature.contains("onStream") || signature.contains("onPrimaryAction"))
        assertTrue(poster.contains("posterLoader: ((ImageView, PolarisGame) -> Unit)? = null"))
        assertTrue(poster.contains("val posterLoaderIdentity: Any = posterLoader ?: apiClient"))
        assertTrue(poster.contains("remember(artworkRevisionKey, posterLoaderIdentity)"))
        assertTrue(poster.contains("posterLoader?.invoke(view, game) ?: apiClient.loadCoverInto(view, game)"))
        assertTrue(poster.contains(".testTag(\"nova-poster-${'$'}{game.id}\")"))
        assertTrue(poster.contains(".testTag(\"nova-poster-art-${'$'}{game.id}\")"))
        assertFalse(poster.contains(".border("))
        assertFalse(poster.contains("SELECTED"))
        assertFalse(poster.contains("NovaFocusMotionSpec.CardFocusedScale"))
        assertTrue(focusComponents.contains("const val DurationMillis = 150"))
        assertTrue(focusComponents.contains("const val CardFocusedScale = 1.025f"))
        assertEquals(2, stage.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" })
        assertEquals(2, activity.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" })
    }

    @Test
    fun libraryBackdropSelectionPrefersCurrentFocusBeforeHeroEvenForActiveSessions() {
        val activity = readNovaLibraryActivity()
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryHomeHero("
        )
        val selection = screen
            .substringAfter("val focusedBackdropGame = remember(")
            .substringBefore("val controllerHints")
        val focusLookup = selection.indexOf("restoreFocusGameId")
        val focusedItem = selection.indexOf("model.filteredGames.firstOrNull { it.id == focusedId }")
        val heroFallback = selection.indexOf("?: model.hero.game")
        val filteredFallback = selection.indexOf("?: model.filteredGames.firstOrNull()")
        val recentFallback = selection.indexOf("?: model.recentGames.firstOrNull()")
        val backdropCall = screen.indexOf("NovaLibraryCinematicBackdrop(")
        val particles = screen.indexOf("if (surfaces.particlesEnabled)")
        val windowContent = screen.indexOf(".background(surfaces.backgroundScrim)")

        assertEquals(1, activity.windowed("NovaLibraryCinematicBackdrop(".length).count { it == "NovaLibraryCinematicBackdrop(" })
        assertFalse(activity.contains("NovaLibraryFocusedBackdrop"))
        assertFalse(selection.contains("if (model.hero.reason == NovaLibraryHeroReason.ACTIVE_SESSION)"))
        assertTrue(focusLookup >= 0 && focusedItem > focusLookup)
        assertTrue(focusedItem < heroFallback && heroFallback < filteredFallback && filteredFallback < recentFallback)
        assertTrue(backdropCall >= 0 && backdropCall < particles && backdropCall < windowContent)
        assertTrue(activity.windowed("onGameFocused = onGameFocused".length).count { it == "onGameFocused = onGameFocused" } >= 7)
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaStreamHudContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt")

    private fun readNovaQuickMenu(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenu.kt")

    private fun readNovaQuickMenuContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt")

    private fun readNovaGameDetail(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")

    private fun readNovaSettingsScreen(): String =
        readSource("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt")

    private fun readNovaFocusComponents(): String =
        readSource("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt")

    @Test
    fun playSetupLaunchSettingsRowReadsActionableModeState() {
        val detail = readNovaGameDetail()

        // The state this asked of LaunchControls is now asked of the row that replaced
        // it: what the launch settings offer is derived, never assumed enabled.
        assertTrue(detail.contains("uiState.showLaunchOptionsButton"))
        assertFalse(detail.contains("uiState.launchOptionsEnabled"))
    }

    @Test
    fun gameDetailLaunchOptionsAvoidRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val launchOptions = detail.section(
            "private fun showLaunchOptions(",
            "private fun optionLabel("
        )

        assertTrue(
            "the launch settings are chosen without a dialog and without a sheet, for the " +
                "same reason as the tuning preference: a glass panel over the destination is " +
                "still a modal on the launch path",
            !launchOptions.contains("AlertDialog.Builder(") &&
                detail.contains("data class NovaLaunchOptionsState") &&
                !detail.contains("private fun NovaLaunchOptionsSheet(") &&
                detail.contains("launchOptionsState != null -> NovaPlaySetupComparison(")
        )
    }

    @Test
    fun gameDetailProfilePreferenceAvoidsRawAppCompatAlertDialogButtons() {
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")
        val profileOptions = detail.section(
            "private fun showProfilePreferenceOptions(",
            "private fun steamLaunchModeOptionsState("
        )

        assertTrue(
            "the tuning preference is chosen without a dialog and without a sheet. It used " +
                "to be routed through a Nova glass panel raised over the destination, which " +
                "was better than an AlertDialog and still a modal on the launch path; the " +
                "options are stated as consequences in the comparison strip now",
            !profileOptions.contains("AlertDialog.Builder(") &&
                detail.contains("data class NovaProfilePreferenceOptionsState") &&
                !detail.contains("private fun NovaProfilePreferenceSheet(") &&
                detail.contains("profileOptionsState != null -> NovaPlaySetupComparison(") &&
                detail.contains("consequence = novaProfilePreferenceConsequence(option.value)")
        )
    }

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.countOccurrences(value: String): Int =
        Regex(Regex.escape(value)).findAll(this).count()

    private fun String.containsRegex(pattern: String): Boolean =
        Regex(pattern).containsMatchIn(this)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }

    private fun String.blockStartingAt(startMarker: String): String {
        val markerIndex = indexOf(startMarker)
        require(markerIndex >= 0) { "Missing start marker: $startMarker" }
        val openBrace = indexOf('{', markerIndex)
        require(openBrace >= 0) { "Missing opening brace after: $startMarker" }
        var depth = 0
        for (index in openBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(openBrace, index + 1)
                }
            }
        }
        error("Unbalanced block after: $startMarker")
    }

    @Test
    fun gameDetailGaugeMeasuresPlayedAgainstTheLongestEstimate() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "the bar's full width is the completionist figure, falling back to whatever is actually known",
            gauge.contains("beatTime?.longestSeconds?.takeIf { it > 0 }")
        )
        assertTrue(
            "played past the end caps the bar, because a bar cannot say more than full",
            gauge.contains("(playedSeconds.toFloat() / fullWidthSeconds.toFloat()).coerceIn(0f, 1f)")
        )
        assertFalse(
            "the played figure is not clamped with the bar: the hours someone actually spent " +
                "stay true past the end of an estimate",
            gauge.contains("playedSeconds.coerceAtMost")
        )
    }

    @Test
    fun gameDetailGaugeCutsItsNotchesThroughTheBarRatherThanOntoIt() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        // Overhang top and bottom is the whole difference between a notch in the bar and
        // a mark sitting on it, and it is visible only in the drawing, never in the prose.
        assertTrue(
            "the canvas is taller than the bar so the notches can overhang it",
            gauge.contains(".height(NOVA_GAUGE_BAR + NOVA_GAUGE_NOTCH_OVERHANG * 2)") &&
                gauge.contains("val barTop = NOVA_GAUGE_NOTCH_OVERHANG.toPx()")
        )
        assertTrue(
            "a notch is drawn in the ground colour over a lighter ring, which is what reads as a cut",
            gauge.contains("val notchInk = colors.window") &&
                gauge.contains("val notchRing = colors.textPrimary.copy(alpha = 0.34f)")
        )
        assertTrue(
            "a notch at or past the full width would sit on the end cap and say nothing",
            gauge.contains("it > 0 && it < fullWidthSeconds")
        )
    }

    @Test
    fun gameDetailGaugeSaysWhichEstimateIsWhich() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "three bare numbers do not say which is the main story and which is everything",
            gauge.contains("R.string.nova_game_detail_beat_main") &&
                gauge.contains("R.string.nova_game_detail_beat_extras") &&
                gauge.contains("R.string.nova_game_detail_beat_complete")
        )
        assertTrue(
            "the played figure leads and the estimates follow it, so the row has a reading order",
            gauge.indexOf("color = colors.textPrimary,") in
                0 until gauge.indexOf("color = colors.textSecondary,")
        )
    }

    @Test
    fun gameDetailGaugeDrawsNothingItCannotBack() {
        val gauge = readNovaGameDetail().section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "with neither a duration nor an estimate the block is absent entirely",
            gauge.contains("if (playedSeconds <= 0L && fullWidthSeconds <= 0L)")
        )
        assertTrue(
            "an estimate with nothing played reads Not started rather than zero hours",
            gauge.contains("R.string.nova_game_detail_not_started")
        )
        assertTrue(
            "the bar only appears once there is something to measure against",
            gauge.contains("if (fullWidthSeconds > 0L) {")
        )
    }

    @Test
    fun gameDetailGaugeSurfacesAWrongMatchAndLeadsToItsFix() {
        val detail = readNovaGameDetail()
        val gauge = detail.section(
            "private fun NovaGameDetailBeatGauge(",
            "/**\n * Whether two titles are the same game"
        )

        assertTrue(
            "a fuzzy match that went wrong looks exactly like one that went right, so the name " +
                "it found is shown when it is not plainly the same game",
            gauge.contains("novaSameTitle(matched, gameName).not()") &&
                gauge.contains("R.string.nova_game_detail_matched_as")
        )
        assertTrue(
            "punctuation and case disagree constantly between a launcher and a catalogue, and " +
                "saying so every time would bury the mismatches that matter",
            detail.contains("private fun novaSameTitle(") &&
                detail.contains("value.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }")
        )
        assertTrue(
            "seeing the mismatch is half of it: the line is the way to the studio that fixes the identity",
            gauge.contains("onCorrectMatch") &&
                detail.contains("onCorrectMatch = { onDestination(NovaGameDetailDestination.ARTWORK) }")
        )
    }
}
