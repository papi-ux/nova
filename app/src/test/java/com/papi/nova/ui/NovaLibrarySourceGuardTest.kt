package com.papi.nova.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards for the library.
 *
 * Moved verbatim out of NovaComposeSourceGuardTest, so each migration group owns the guards
 * on its own files.
 */
class NovaLibrarySourceGuardTest {
    @Test
    fun libraryFilterPagesKeepLongListsReachable() {
        // The filter sheet became the Sources and More Filters pages inside Library Options: host
        // drawn Choice pages, whose list scrolls and opens on the current choice.
        val activity = readNovaLibraryActivity()
        val commonPages = readSource("src/main/java/com/papi/nova/ui/panel/NovaCommonPages.kt")
        val choicePage = commonPages.section(
            "private fun <T> NovaPageScope.ChoicePage(",
            "private fun <T> NovaPageScope.MultiChoicePage("
        )

        assertTrue(
            "filter pages should keep long source/category/genre lists reachable",
            activity.contains("key = LibraryPage.KEY_SOURCES") &&
                activity.contains("key = LibraryPage.KEY_MORE") &&
                activity.windowed("NovaCommonPage.Choice(".length).count { it == "NovaCommonPage.Choice(" } >= 3 &&
                choicePage.contains("PageList {") &&
                commonPages.contains("LazyColumn(\n        state = listState,")
        )
        assertFalse(
            "the filter lists are pages in the Options panel, not a Material sheet over the library (R10)",
            activity.contains("ModalBottomSheet(") || activity.contains("private fun NovaLibraryFilterSheet(")
        )
    }

    @Test
    fun libraryOptionsPanelExposesSortAndLayoutControls() {
        val activity = readNovaLibraryActivity()
        val panels = readNovaLibraryPanels()
        val strings = readSource("src/main/res/values/strings.xml")
        val screen = activity.section(
            "private fun NovaLibraryScreen(",
            "private fun NovaLibraryHomeHero("
        )
        val options = panels.section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )

        assertTrue(
            "library activity should keep quick options as durable Compose state, and read whether a panel is open from the panel window",
            activity.contains("private var optionsState by mutableStateOf(NovaLibraryOptionsState())") &&
                activity.contains("get() = novaSurfaces.panel.isOpen")
        )
        assertTrue(
            "remembered library model should be keyed by options state so sort changes are cheap and deliberate",
            activity.contains("remember(games, searchQuery, filterState, activeSession, optionsState)") &&
                activity.contains("optionsState = optionsState")
        )
        assertTrue(
            "library shell should pass an explicit Options opener into rail/header actions",
            activity.contains("onOpenOptions = ::openLibraryOptions") &&
                activity.contains("onOpenOptions = onOpenOptions")
        )
        assertTrue(
            "Options and System share one panel window, which swaps one for the other so they never stack",
            activity.contains("if (surfaces.panel.swapToLibraryPeer(root)) return") &&
                readNovaLibraryPanels().contains("if (depth != 1 || top?.key != root.key) switchRoot(root, root.edge)") &&
                activity.contains("surfaces.open(") &&
                !screen.contains("NovaLibraryOptionsSheet(") &&
                !screen.contains("activeFilterSheet")
        )
        assertTrue(
            "Options should expose Sort, Layout, and Poster title rows rather than hiding browsing decisions in the rail",
            options.contains("R.string.nova_library_options_sort_title") &&
                options.contains("R.string.nova_library_options_layout_title") &&
                options.contains("R.string.nova_library_options_poster_titles_title") &&
                options.contains("onChange = actions.onLayoutMode") &&
                options.contains("onChange = actions.onPosterTitles") &&
                options.contains("style = NovaValueStyle.Switch") &&
                activity.contains("key = LibraryPage.KEY_SORT")
        )
        assertTrue(
            "library options should be persisted so sort, layout, poster titles, and filters survive relaunches",
            activity.contains("NovaLibraryPreferences.loadOptions(libraryPreferences)") &&
                activity.contains("NovaLibraryPreferences.loadFilterState(libraryPreferences)") &&
                activity.contains("NovaLibraryPreferences.persistOptions(libraryPreferences(), nextState)") &&
                activity.contains("NovaLibraryPreferences.persistFilterState(libraryPreferences(), normalized)") &&
                activity.contains("updateLibraryOptions { it.copy(sortMode = mode) }") &&
                activity.contains("updateLibraryOptions { it.copy(layoutMode = layoutMode) }") &&
                activity.contains("updateLibraryOptions { it.copy(showPosterTitles = show) }") &&
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
            "landscape should keep one showcase strip above a full-width grid. The toolbar and the continue card used to be two strips, and the grid was left with a single poster row because of it.",
            screen.contains("NovaLibraryLandscapeShowcaseStripContent(") &&
                screen.contains("NovaLibraryContent(")
        )
    }

    @Test
    fun libraryOptionsIsAStartEdgePanelNotAFullWidthMaterialSheet() {
        // The drawer's anchoring, scrim and rhythm are the panel frame's now (R6): attached to the
        // start edge at full height, rounded on the inner edge, the screen scrim with its blur.
        val activity = readNovaLibraryActivity()
        val frame = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt")

        assertTrue(
            "library options should open at the start edge of the panel window instead of a full-width bottom sheet",
            readNovaLibraryPanels().contains("get() = if (this is LibraryPage.Options) NovaEdge.Start else NovaEdge.End") &&
                frame.contains("NovaMenuPreferences.readabilityScrimAlpha(") &&
                frame.contains("RoundedCornerShape(topEnd = NovaRadius.drawer, bottomEnd = NovaRadius.drawer)")
        )
        assertFalse(
            "library options should not use the giant Material bottom sheet or a Compose Dialog of its own",
            activity.contains("ModalBottomSheet(") || activity.contains("DialogProperties(")
        )
    }

    @Test
    fun libraryPanelsPutThePrimaryBrowseTaskFirst() {
        val panels = readNovaLibraryPanels()
        val options = panels.section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )
        val systemPage = panels.section(
            "internal fun NovaPageScope.NovaLibrarySystemPage(",
            "internal fun NovaPageScope.NovaLibrarySearchPage("
        )

        assertTrue(
            "Options should open on the filter row, the primary browse task, with Refresh after search at the foot",
            options.indexOf("item(key = \"filter\"") in 0 until options.indexOf("item(key = \"search\"") &&
                options.indexOf("item(key = \"search\"") < options.indexOf("item(key = \"refresh\"") &&
                options.section("item(key = \"filter\"", "item(key = \"sources\"").contains("Modifier.novaInitialFocus()")
        )
        assertFalse(
            "Options should not spend first-paint height on prose hint copy",
            options.contains("R.string.nova_library_options_hint")
        )
        assertTrue(
            "both panels list their rows with padding at top and bottom, so no row is cut at rest (R13)",
            options.contains("contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm)") &&
                systemPage.contains("contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm)") &&
                systemPage.contains("state = listState")
        )
        assertTrue(
            "System opens with focus on its first row, never on the panel",
            systemPage.section("item(key = \"switch-host\"", "item(key = \"settings\"").contains("Modifier.novaInitialFocus()")
        )
    }

    @Test
    fun librarySystemPanelExposesTopLevelSafeActions() {
        val activity = readNovaLibraryActivity()
        val panels = readNovaLibraryPanels()
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
        val systemPage = panels.section(
            "internal fun NovaPageScope.NovaLibrarySystemPage(",
            "internal fun NovaPageScope.NovaLibrarySearchPage("
        )
        val systemUi = activity.section(
            "private fun librarySystemUi(): NovaLibrarySystemUi {",
            "private fun librarySourcesPage(): NovaPage {"
        )

        assertTrue(
            "System should be a page family member drawn by the library's panel window",
            panels.contains("data class System(override val title: String) : LibraryPage") &&
                activity.contains("is LibraryPage.System -> NovaLibrarySystemPage(") &&
                activity.contains("private fun openLibrarySystem()") &&
                activity.contains("onOpenSystemMenu = ::openLibrarySystem")
        )
        assertFalse(
            "the library screen draws no modal of its own; Options and System are one window's peer panels",
            screen.contains("NovaSystemMenuSheet(") || screen.contains("NovaLibraryOptionsSheet(") ||
                activity.contains("private var activeSystemMenu")
        )
        assertTrue(
            "B goes through the key gate as Back on release, which the panel window answers before the library, so the screen reads B nowhere itself",
            // The gate is every Nova screen's since the closing step, so the library takes it
            // without an override (NovaActivityKeyGateTest).
            !activity.contains("override val novaKeyGate") &&
                !keyHandler.contains("KEYCODE_BUTTON_B") &&
                activity.contains("dismissActiveLibraryOverlay()")
        )
        assertTrue(
            "L1 and R1 hop between the two peers in the same window, and neither panel traps the D-pad sideways",
            activity.contains("onShoulder = ::onLibraryPanelShoulder") &&
                activity.contains("NovaShoulder.Left -> if (NovaSpaceUiState.singleSpace(allGames) == null) openLibraryOptions()") &&
                activity.contains("NovaShoulder.Right -> openLibrarySystem()") &&
                !panels.contains("Key.DirectionLeft") && !panels.contains("Key.DirectionRight") &&
                !panels.contains("KEYCODE_DPAD_")
        )
        assertTrue(
            "Menu/Start should be a destination-to-System shortcut, not a close toggle; Back/B owns dismiss",
            menuKeyHandler.contains("openLibrarySystem()") &&
                !menuKeyHandler.contains("close()")
        )
        assertTrue(
            "System should show the active host and Polaris readiness in its header",
            systemUi.contains("serverDisplayName") &&
                systemUi.contains("R.string.nova_system_menu_host_format") &&
                systemUi.contains("R.string.nova_system_menu_host_named_format") &&
                systemUi.contains("R.string.nova_system_menu_status_polaris_ready") &&
                systemUi.contains("R.string.nova_system_menu_status_offline") &&
                systemPage.contains("item(key = \"header\"")
        )
        assertTrue(
            "System should expose the short top-level Nova/system actions only",
            systemPage.contains("R.string.nova_system_menu_switch_host") &&
                systemPage.contains("R.string.nova_system_menu_settings") &&
                systemPage.contains("R.string.nova_system_menu_polaris_sync") &&
                systemPage.contains("R.string.nova_system_menu_manage_server") &&
                systemPage.contains("R.string.nova_system_menu_help_diagnostics") &&
                systemPage.contains("R.string.nova_system_menu_about")
        )
        assertTrue(
            "System rows should route to existing workflows and close the panel before launching secondary surfaces",
            systemPage.contains("val leave: (() -> Unit) -> Unit = { action -> if (isTop) closeThen(action = action) }") &&
                systemPage.contains("leave(actions.onSwitchHost)") &&
                systemPage.contains("leave(actions.onSettings)") &&
                systemPage.contains("panel.push(actions.polarisSyncPage())") &&
                systemPage.contains("leave(actions.onManageServer)") &&
                systemPage.contains("leave(actions.onHelp)") &&
                systemPage.contains("leave(actions.onAbout)") &&
                systemPage.contains("NovaRow(")
        )
        assertFalse(
            "System should not waste first-paint height on non-action footer copy",
            systemPage.contains("R.string.nova_system_menu_safe_hint")
        )
        assertFalse(
            "destructive stream/session actions should stay out of the top-level system panel",
            systemPage.contains("onEndSession") ||
                systemPage.contains("displayQuitConfirmationDialog") ||
                systemPage.contains("ServerHelper.doQuit")
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
            "shared Activity and Stage poster views should be revision-aware, and the backdrop keys its hero the same way (it draws no posters now)",
            posterCard.contains("PolarisApiClient.artworkPresentationKey(") &&
                posterCard.contains("PolarisGame.ARTWORK_KIND_POSTER") &&
                chrome.contains("PolarisApiClient.artworkPresentationKey(")
        )
        assertTrue(
            "the home Hero cover should recreate when the Poster revision changes, and the backdrop stays fenced by its presentation key",
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
    fun libraryCinematicBackdropDrawsOnlyARealHeroOrTheAmbientField() {
        val backdrop = readSource("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val mapper = readSource("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")

        assertTrue(
            "the backdrop asks the mapper which artwork it may draw, so one rule covers every entry",
            backdrop.contains("NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game) ?: return@let null")
        )
        assertTrue(
            "only a real hero is drawn: cached for a desktop title, asked for a Space title, never a launcher's own mark, " +
                "whether Steam Big Picture's bundled one or the one a host serves for Heroic. A Space title's used to " +
                "have to be listed, and the host lists only what it has already fetched, so none was ever asked for",
            mapper.contains("fun cinematicBackdropArtworkKind(game: PolarisGame?): String?") &&
                mapper.contains("return if (hero.cached) PolarisGame.ARTWORK_KIND_HERO else null") &&
                mapper.contains("PolarisApiClient.hostHasNoArtworkFor(game)) null else PolarisGame.ARTWORK_KIND_HERO") &&
                mapper.contains("WorkerLaunchContract.isLauncherEntry(game.space?.target)")
        )
        assertTrue(backdrop.contains("apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)"))
        assertFalse(
            "a 2:3 poster stretched across a landscape screen crops to a slice of its wordmark: papi saw a giant VIRTUAL DESKTOP behind the whole library",
            backdrop.contains("apiClient.loadCoverInto(") || backdrop.contains("PolarisGame.ARTWORK_KIND_POSTER")
        )
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
            "landscape library should promote the continue action before grid content so the picker remains visible on Retroid. It is a slot inside the one showcase strip rather than a card of its own, because two stacked full-width strips were each about half empty.",
            landscape.indexOf("NovaLibraryLandscapeShowcaseStripContent(") in
                0 until landscape.indexOf("NovaLibraryContent(") &&
                landscape.contains("NovaLibraryShowcaseContinue(")
        )
        val continueCard = source.section(
            "private fun RowScope.NovaLibraryShowcaseContinue(",
            "private fun NovaLibraryTopHeader("
        )
        assertTrue(
            "the strip card's own action (Resume Stream while a game is live) is the highlighted button and End Session stays secondary, so the next step reads at a glance: End is a split confirm, quiet until armed",
            continueCard.substringBefore("val secondaryLabel").contains("primary = true") &&
                continueCard.substringAfter("val secondaryLabel").contains("NovaSplitConfirm(") &&
                !continueCard.substringAfter("val secondaryLabel").contains("primary = true")
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
            "landscape should keep a mapper-owned clearance for the overlaid controller hints instead of letting poster rows settle under the bar. The shell used to hold that back as a slab the grid could never draw into, and the grid paid for it a second time in its own inset; the inset alone buys the clearance now, so the mapper has to own the rule that says so.",
            screen.contains("NovaLibraryUiStateMapper.controllerHintBarBottomPaddingDp(isLandscape).dp") &&
                mapper.contains("fun landscapeHintClearanceDp(): Int") &&
                mapper.contains("fun controllerHintBarMinHeightDp(): Int")
        )
        assertTrue(
            "game grid should use mapper-owned padding: sides that line the artwork up with the bar's content, a top inset that is the focus rise, and extra bottom scroll room so the final poster row can settle above the footer",
            content.contains("contentPadding = PaddingValues(") &&
                content.contains("NovaLibraryUiStateMapper.gridSidePaddingDp(layoutMode)") &&
                content.contains("top = viewportSpec.topInsetDp.dp") &&
                content.contains("bottom = NovaLibraryUiStateMapper.gridBottomContentPaddingDp(isLandscape).dp") &&
                mapper.contains("fun gridBottomContentPaddingDp(isLandscape: Boolean): Int")
        )
        assertFalse(
            "no panel frames the posters: papi asked for the box around them to go, and a bordered panel under a bordered bar read as two overlapping backgrounds",
            content.contains("NovaLibraryPanel(")
        )
        assertTrue(
            "the grid's whole height is its viewport, and a focus scroll keeps the focus rise clear at its top edge the way the first row's inset does",
            content.contains("viewportHeightDp = maxHeight.value.toInt().coerceAtLeast(1)") &&
                content.contains("CompositionLocalProvider(LocalBringIntoViewSpec provides focusScrollSpec)")
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
        // This behaviour is NovaSearchTextField's now, and both search fields call it, so
        // this covers the settings one too -- which is the field that had none of it and is on
        // the screen most likely to be driven with a controller.
        val searchField = readSource("src/main/java/com/papi/nova/ui/compose/NovaSearchTextField.kt")

        assertTrue(
            "search should keep a browse mode before explicitly editing text",
            searchField.contains("var editing by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "search should not show the IME just because D-pad focus lands on it",
            searchField.contains("readOnly = !editing")
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
            searchField.contains("Key.DirectionDown -> leaveEditing(FocusDirection.Down)")
        )
        assertFalse(
            "search should not wait for edit mode before releasing D-pad navigation",
            searchField.contains("Key.DirectionDown -> if (editing)")
        )
        assertTrue(
            "controller select should explicitly enter search edit mode on TV remotes, on the " +
                "release of a press that began on the field, as every control acts (R8)",
            searchField.contains("NovaKeys.isActivation(native.keyCode) && !editing ->") &&
                searchField.contains("KeyEventType.KeyUp -> if (latch.release(native.keyCode) && !native.isCanceled) beginEditing()")
        )
        assertFalse(
            "controller select should not be swallowed without activating search",
            searchField.contains("Key.DirectionCenter -> true")
        )
    }

    @Test
    fun libraryOptionsKeepsTheDpadInsideAndShouldersToItsPeer() {
        // Right used to jump to System from anywhere in Options, even inside the filter chips,
        // which could then never be walked. The panel window holds focus in by construction, the
        // filter row takes Left and Right itself, and L1 and R1 swap to the peer.
        val activity = readNovaLibraryActivity()
        val options = readNovaLibraryPanels().section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )

        assertTrue(
            "the quick filters are one value row, whose Left and Right change the filter and never move focus",
            options.contains("NovaValueRow(\n                title = stringResource(R.string.nova_library_panel_filter),") &&
                !options.contains("onPreviewKeyEvent") &&
                !options.contains("horizontalScroll")
        )
        assertTrue(
            "the peers swap on the shoulders, in the same window",
            activity.contains("onShoulder = ::onLibraryPanelShoulder") &&
                activity.contains("NovaShoulder.Right -> openLibrarySystem()")
        )
    }

    @Test
    fun libraryOptionsKeepsBottomRowsScrollableAboveSafeArea() {
        val options = readNovaLibraryPanels().section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )
        val frame = readSource("src/main/java/com/papi/nova/ui/panel/NovaPanelFrame.kt")

        assertTrue(
            "Options scrolls its own list, padded at both ends, inside a frame that keeps clear of the safe area, so the last rows scroll above gesture and nav chrome",
            options.contains("LazyColumn(\n        state = listState,") &&
                options.contains("contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm)") &&
                frame.contains(".windowInsetsPadding(WindowInsets.safeDrawing.only(outer + WindowInsetsSides.Vertical))")
        )
    }

    @Test
    fun libraryOptionsOwnsBrowseControlsAndCutsNoChipAtTheEdge() {
        val activity = readNovaLibraryActivity()
        val options = readNovaLibraryPanels().section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )

        assertTrue(
            "Options should own library refresh, search, filters, sort, and layout instead of a permanent rail",
            options.contains("R.string.nova_refresh") &&
                options.contains("R.string.nova_library_panel_search") &&
                options.contains("val filterOptions = QuickFilters.map") &&
                options.contains("R.string.nova_library_options_sort_title") &&
                options.contains("NovaOption(NovaLibraryLayoutMode.COMPACT") &&
                activity.contains("NovaLibrarySortMode.entries.map")
        )
        assertFalse(
            "no strip of chips scrolls sideways and ends mid chip at the panel edge (R13): the filters are a value row",
            options.contains(".horizontalScroll(") ||
                activity.contains("filterChipWidthDp(")
        )
    }

    @Test
    fun libraryRestoresLastFocusedGameAndOptionsRow() {
        val source = readNovaLibraryActivity()
        val posterFocus = source.section(
            "private fun rememberLibraryPosterFocusRequester(",
            "@Composable\n    private fun NovaLibraryLoadingGrid("
        )
        val options = readNovaLibraryPanels().section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
        )

        assertTrue(
            "library should keep last focused game id in activity state for detail-sheet returns",
            source.contains("private var lastFocusedGameId by mutableStateOf<String?>(null)") &&
                source.contains("onGameFocused = { lastFocusedGameId = it.id }")
        )
        assertTrue(
            "shared poster call sites should request focus once when they match the remembered game " +
                "(restore and cold start share the settled request path)",
            source.windowed("rememberLibraryPosterFocusRequester(".length)
                .count { it == "rememberLibraryPosterFocusRequester(" } == 3 &&
                posterFocus.contains("val focusRequester = remember { FocusRequester() }") &&
                posterFocus.contains("if ((restoreFocus || coldStartFocus) && !restoreAttempted)") &&
                posterFocus.contains("focusRequester.requestFocus()")
        )
        assertTrue(
            "Options rows mark themselves so a page that pops returns focus to the row that pushed it",
            options.contains("Modifier.novaRestorableFocus(\"sources\", 1)") &&
                options.contains("Modifier.novaRestorableFocus(\"more\", 2)") &&
                options.contains("Modifier.novaRestorableFocus(\"sort\", 5)") &&
                options.contains("Modifier.novaRestorableFocus(\"search\", 8)")
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
        val options = readNovaLibraryPanels().section(
            "internal fun NovaPageScope.NovaLibraryOptionsPage(",
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows("
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
            "Library Options should show a clear filters action when filters/search are active",
            source.contains("clearable = hasClearableFilters(searchQuery, filterState)") &&
                options.contains("if (ui.clearable) {") &&
                options.contains("R.string.nova_library_filter_clear_all")
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
            api.contains("throw PolarisArtworkSearchUnavailableException(") &&
                api.contains("NovaArtworkSearchFailure.codeFrom(failure)") &&
                api.contains("class PolarisArtworkSearchUnavailableException(") &&
                api.contains("val code: String?,") &&
                api.contains("val httpStatus: Int,") &&
                !api.contains("class PolarisArtworkSearchUnavailableException(\n    val body") &&
                api.contains("IOException(\"artwork candidate search HTTP \$httpStatus") &&
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
                searchHandler.contains("catch (e: com.papi.nova.api.PolarisArtworkSearchUnavailableException)") &&
                searchHandler.contains("NovaArtworkSearchFailure.messageRes(e.code)") &&
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
    fun artworkRequestsPoolConnectionsOnAnInstanceScopedTlsClient() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        assertTrue(
            "production artwork calls should reuse the instance-scoped pooled client",
            api.contains("private fun executeArtwork(request: Request) = artworkClient.newCall(request).execute()") &&
                api.contains("SSLContext.getInstance(\"TLS\").apply") &&
                api.contains(".sslSocketFactory(sslContext.socketFactory, trustManager)")
        )
        assertTrue(
            "the artwork pool must be set explicitly because newBuilder() copies the API path's no-keep-alive pool",
            api.contains(".connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))")
        )
        assertTrue(
            "TLS-bearing clients must stay instance-scoped so re-pairing (a new PolarisApiClient) rebuilds TLS state; never cache them at companion/static scope",
            api.contains("private val artworkClient: OkHttpClient by lazy") &&
                api.contains("private var perCallClientCache: OkHttpClient? = null") &&
                api.contains("private fun buildPerCallClient(): OkHttpClient")
        )
        assertTrue(
            "transient-TLS recovery must rebuild the per-call client (dropping cached TLS sessions), and only idempotent requests may ride the retry helper",
            api.contains("private fun resetCallClient()") &&
                api.contains("runWithTransientTlsRetry(onTransient = { resetCallClient() })") &&
                !api.section("fun launchGame(", "fun unlockScreen(")
                    .contains("executeWithTransientRetry")
        )
        assertFalse(
            "artwork fetches must not force per-request sockets",
            api.section("private suspend fun fetchArtwork(url: String", "/**\n     * Toggle MangoHud")
                .contains("header(\"Connection\", \"close\")")
        )
    }

    @Test
    fun artworkFetchLogsUseFixedClassificationWithoutUrlsOrExceptionMessages() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val fetch = api.section(
            "private suspend fun fetchArtwork(url: String",
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
            // Pinned the English sentence itself until it became a string resource. The
            // resource name says which branch this is without depending on its wording, and
            // the sentence was doing double duty as on-screen error state, so it had to move.
            source.contains("launchErrorMessage = message") &&
                source.contains("R.string.nova_library_launch_missing_session") &&
                source.contains("Failed to launch ${'$'}{game.name}")
        )
        assertTrue(
            "stale launch recovery should clear on refresh, detail navigation, and valid launch/resume paths",
            source.blockStartingAt("private fun loadGames(").contains("launchErrorMessage = null") &&
                source.contains("private fun showGameDetail(game: PolarisGame) = showDetail(game)") &&
                source.blockStartingAt("private fun showDetail(").contains("launchErrorMessage = null") &&
                source.blockStartingAt("private fun launchGame(").contains("launchErrorMessage = null") &&
                source.blockStartingAt("private fun resumeActiveSession(").contains("launchErrorMessage = null")
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
            "closed-screen shoulders should open the spatial panels directly instead of cycling source/filter chips, and the open panels name them too",
            keyHandler.contains("KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> {") &&
                keyHandler.contains("openLibraryOptions()") &&
                keyHandler.contains("KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> {") &&
                keyHandler.contains("openLibrarySystem()") &&
                activity.contains("label = getString(R.string.nova_controller_hint_library_system)")
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
            "fun NovaSettingsContent(",
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
                libraryScreen.contains("NovaLibraryLandscapeShowcaseStripContent(") &&
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
        // Group 4 moved Settings' pane onto NovaPageStackHost, which draws its own hint row
        // under the page, so the pane (not a separate bar) is what stays weighted.
        assertTrue(
            "settings should keep the main rows weighted above the hint row instead of letting rows consume and clip the bottom controls",
            settingsContent.contains("val hints = novaSettingsHints(") &&
                settingsContent.contains("Row(\n                modifier = Modifier\n                    .weight(1f)") &&
                settingsContent.contains("paneHost(Modifier.weight(1f).fillMaxHeight())") &&
                settingsContent.contains("NovaPageStackHost(") &&
                settingsContent.contains("hints = hints")
        )
    }

    @Test
    fun libraryEmptyAndErrorTextIsBoundedAndCentered() {
        // The recovery states are the one state page now, drawn in the library without a card (R5):
        // the state page centres its text and bounds its column.
        val recoveryState = readNovaLibraryActivity().section(
            "private fun NovaLibraryRecoveryState(",
            "private fun NovaLibraryPanel("
        )
        val stateScreen = readSource("src/main/java/com/papi/nova/ui/panel/NovaStateScreen.kt")

        assertTrue(
            "empty/error copy should be a state page, centered for TV and narrow portrait layouts",
            recoveryState.contains("NovaStateScreen(") &&
                recoveryState.contains("NovaStatePage.Problem(") &&
                stateScreen.contains("textAlign = TextAlign.Center")
        )
        assertTrue(
            "empty/error copy should be width bounded so long messages do not run edge to edge",
            stateScreen.contains(".widthIn(max = NovaPanelMetrics.StateColumnMaxWidth)")
        )
        assertTrue(
            "B leaves the library rather than running the recovery action",
            recoveryState.contains("back = NovaAction(getString(R.string.nova_panel_back)) { finishWithTransition() }")
        )
        assertFalse(
            "no centred card around the recovery state (R6)",
            recoveryState.contains(".widthIn(max = 360.dp)")
        )
    }

    @Test
    fun composeLibraryEndSessionIsConfirmedByItsSplitAndClearsTheSession() {
        // The active session card had no caller and is gone; the hero, the strip and the detail
        // page carry the session, and libraryHeroExposesEndSessionForOwnedActiveStreams pins the
        // hero's End.
        val source = readNovaLibraryActivity()
        val endActiveSession = source.section(
            "private fun endActiveSession(",
            "private fun openServerManagement("
        )

        assertTrue(
            "Compose library should pass an end-session callback into the screen",
            source.contains("onEndSession = ::endActiveSession")
        )
        assertFalse(
            "every End is confirmed by its split, so ending asks nothing more",
            endActiveSession.contains("displayQuitConfirmationDialog")
        )
        assertTrue(
            "ending from the library should route through the quit path and clear the card",
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
        val panels = readNovaLibraryPanels()
        val optionsSheet = panels.section(
            "private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows(",
            "private fun ArtworkRetryAll("
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
            panels.contains("NovaSectionLabel(stringResource(R.string.nova_artwork_library_update_title))") &&
                optionsSheet.contains("NovaArtworkLibraryUpdateUiState.Running") &&
                optionsSheet.contains("LinearProgressIndicator(") &&
                optionsSheet.contains("onClick = actions.onCancelArtwork") &&
                optionsSheet.contains("actions.onRetryArtwork(summary.failedGameIds)") &&
                activity.contains("onCancelArtwork = ::cancelArtworkLibraryUpdate") &&
                activity.contains("onRetryArtwork = { ids -> startArtworkLibraryUpdate(ids) }")
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
        // The update's rows are drawn by Library Options, in the library's panels file.
        val activity = readNovaLibraryActivity() + readNovaLibraryPanels()
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
                card.contains(".novaClickable(") &&
                card.contains("onOpenDetail()")
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
    fun task9SharedPosterCardUsesLiftAndTheOneRingWithoutBadgesOrBorders() {
        // The spec's one focus look for posters: lift plus the ring, no scale (section 2). A scaled
        // poster grew past its neighbours and read as a different focus from every other control.
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
            "shared PosterCard focus treatment is the lift with the approved alpha support, and the one 3dp ring",
            card.contains("val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)") &&
                card.contains("val alpha by animateFloatAsState(") &&
                card.contains("targetValue = if (focused) 1f else presentationSpec.unfocusedAlpha") &&
                card.contains("val lift by animateDpAsState(") &&
                card.contains("targetValue = if (focused) NovaPosterFocusedLift else 0.dp") &&
                artwork.contains("translationY = -lift.toPx()") &&
                artwork.contains("this.alpha = alpha") &&
                artwork.contains("NovaPanelMetrics.FocusRingWidth") &&
                artwork.contains("tween(durationMillis = NovaPanelMetrics.FocusMillis)")
        )
        assertFalse(
            "posters no longer scale on focus",
            artwork.contains("scaleX = scale") || card.contains("val scale by animateFloatAsState(")
        )
        assertFalse("PosterCard implementation must remain borderless at rest", implementation.contains(".border("))
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
        val clickOwnerIndex = poster.indexOf(".novaClickable(", modifierStart)

        assertTrue(poster.contains("internal fun NovaLibraryPosterCard("))
        assertTrue(poster.contains(".semantics(mergeDescendants = true)"))
        assertEquals(1, poster.windowed(".novaClickable(".length).count { it == ".novaClickable(" })
        assertFalse(poster.contains(".combinedClickable("))
        assertFalse("novaClickable already owns focus and activation", poster.contains(".focusable()"))
        assertFalse(poster.contains("import androidx.compose.foundation.focusable"))
        assertTrue(
            "FocusRequester and onFocusChanged must precede novaClickable so they observe its focus target",
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
        assertTrue(
            "every surface that can focus a game has to report it so the backdrop follows. The count dropped by one when the standalone continue card became a slot in the showcase strip, which has a button rather than a focusable card.",
            activity.windowed("onGameFocused = onGameFocused".length)
                .count { it == "onGameFocused = onGameFocused" } >= 6,
        )
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaLibraryPanels(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryPanels.kt")

    private fun readNovaGameDetail(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaPlaySetup.kt") +
        readSource("src/main/java/com/papi/nova/ui/NovaPlaySetupHostScope.kt") +
        readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailContent.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailOverview.kt") +
            readSource("src/main/java/com/papi/nova/ui/NovaGameDetailDestinations.kt")

    private fun readNovaSettingsScreen(): String =
        readSource("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt")

    private fun readNovaFocusComponents(): String =
        readSource("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt")

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
}
