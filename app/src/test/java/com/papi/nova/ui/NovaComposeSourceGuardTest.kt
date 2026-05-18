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
            "library model mapping should be keyed to the data that affects filtering",
            rememberedModel.contains("remember(games, searchQuery, filterState)")
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
            "controller select should not place search into a D-pad-trapping edit mode",
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
            "@Composable\nprivate fun MangoHudCard("
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
                launchControls.indexOf("text = playLabel") < launchControls.indexOf("text = launchOptionsLabel")
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
    fun streamHudUsesCompactBoundedLabels() {
        val source = readNovaStreamHudContent()
        val fullHud = source.section(
            "private fun NovaStreamHudFull(",
            "@Composable\nprivate fun NovaStreamHudBanner("
        )
        val banner = source.section(
            "private fun NovaStreamHudBanner(",
            "@Composable\nprivate fun NovaStreamHudFpsOnly("
        )

        assertTrue(
            "full HUD should use the shorter HUD-specific status label",
            fullHud.contains("text = state.autopilotHudLabel")
        )
        assertTrue(
            "full HUD status label should have a max width so it cannot crowd the FPS label",
            fullHud.contains(".widthIn(max = 96.dp)")
        )
        assertTrue(
            "banner HUD should use a stable overlay width instead of unconstrained wrap content",
            banner.contains("modifier = modifier.width(320.dp)")
        )
        assertTrue(
            "banner compact status should be horizontally bounded",
            banner.contains(".widthIn(min = 28.dp, max = 42.dp)")
        )
        assertTrue(
            "banner HUD should use explicit compact line height for the status chip",
            banner.contains("lineHeight = 11.sp")
        )
    }

    private fun readNovaLibraryActivity(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

    private fun readNovaStreamHudContent(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt")

    private fun readNovaGameDetailSheet(): String =
        readSource("src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt")

    private fun readNovaFocusComponents(): String =
        readSource("src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt")

    private fun readSource(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

    private fun String.containsRegex(pattern: String): Boolean =
        Regex(pattern).containsMatchIn(this)

    private fun String.section(startMarker: String, endMarker: String): String =
        substring(indexOf(startMarker), indexOf(endMarker))
}
