package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLibraryStageSourceTest {
    private fun read(path: String): String = File(path).readText()

    @Test
    fun stageIsTheArtworkFirstProductionHomeAndLegacyModesAreRetired() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val state = read("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val strings = read("src/main/res/values/strings.xml")

        assertTrue(activity.contains("layoutMode == NovaLibraryLayoutMode.STAGE"))
        assertTrue(activity.contains("NovaLibraryStage("))
        assertTrue(activity.contains("NovaLibraryLayoutMode.STAGE -> R.string.nova_library_options_layout_stage"))
        assertTrue(activity.contains("NovaLibraryLayoutMode.COMPACT -> R.string.nova_library_options_layout_compact"))
        assertFalse(state.contains("COMPACT_GRID"))
        assertFalse(state.contains("SPOTLIGHT_ROW"))
        assertFalse(state.contains("LIST,"))
        assertFalse(activity.contains("NovaLibrarySpotlightRow("))
        assertFalse(strings.contains("nova_library_options_layout_spotlight"))
        assertFalse(strings.contains("nova_library_options_layout_list"))
        assertTrue(stage.contains("BoxWithConstraints"))
        assertTrue(stage.contains("NovaLibraryUiStateMapper.stageLayoutSpecForViewport("))
    }

    @Test
    fun sharedCinematicBackdropIsTheSingleOwnerAcrossStageGridAndCompact() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val chrome = read("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val production = activity + stage + chrome
        val definition = "internal fun NovaLibraryCinematicBackdrop("
        val owner = "NovaLibraryCinematicBackdrop("

        assertEquals(1, production.windowed(definition.length).count { it == definition })
        assertEquals(2, production.windowed(owner.length).count { it == owner })
        assertEquals(1, activity.windowed(owner.length).count { it == owner })
        assertFalse(activity.contains("NovaLibraryFocusedBackdrop"))
        assertFalse(stage.contains("NovaLibraryStageBackdrop"))
        assertFalse(stage.contains("nova-stage-cinematic-backdrop"))
        assertFalse(stage.contains("Brush.horizontalGradient("))
        assertFalse(stage.contains("Brush.verticalGradient("))
        assertFalse(stage.contains("NovaPolarisStageAtmosphere"))
        assertFalse(stage.contains("nova-stage-polaris-atmosphere"))
        assertFalse(stage.contains("import androidx.compose.foundation.Canvas"))
        assertTrue(stage.contains(".testTag(\"nova-library-stage\")"))
        assertFalse(stage.contains("BoxWithConstraints(modifier = Modifier.fillMaxSize().background("))
        assertFalse(stage.contains("PolarisGame.ARTWORK_KIND_LOGO"))
        assertTrue(stage.contains("PolarisGame.ARTWORK_KIND_ICON"))
        assertTrue(stage.contains("apiClient.loadArtworkInto(view, game, artworkKind)"))
        assertTrue(stage.contains("apiClient.loadCoverInto(view, game)"))
    }

    @Test
    fun stageKeepsLaunchArtworkTouchControllerAndAccessibilityPathsImmediate() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val posterCard = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")

        assertTrue(stage.contains("onPrimaryAction: () -> Unit"))
        assertFalse(stage.contains("onArtworkAction: (PolarisGame) -> Unit"))
        assertTrue(stage.contains("onGameFocused: (PolarisGame) -> Unit"))
        assertTrue(stage.contains("onOpenDetail: (PolarisGame) -> Unit"))
        assertTrue(stage.contains("LazyRow("))
        assertTrue(stage.contains("LazyVerticalGrid("))
        assertTrue(stage.contains("focusRequester = focusRequesters[index]"))
        assertTrue(posterCard.contains(".focusRequester(focusRequester)"))
        assertTrue(posterCard.contains(".onFocusChanged"))
        assertTrue(posterCard.contains(".semantics"))
        assertTrue(posterCard.contains("contentDescription = accessibleLabel"))
        assertTrue(stage.contains("key = { _, game -> game.id }"))
    }

    @Test
    fun stageWiresRevisionKeysPortraitRestoreAndDeclaredPosterDensity() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val posterCard = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")

        assertTrue(posterCard.contains("posterPresentationKey"))
        assertTrue(stage.contains("rememberLazyGridState"))
        assertTrue(stage.contains("restoreFocusGameId = restoreFocusGameId"))
        assertTrue(stage.contains("posterColumns = spec.stagePosterColumns"))
        assertTrue(stage.contains("posterColumns: Int"))
    }

    @Test
    fun renderedCardsConsumeAdaptiveHeightsAndStageActionsAreFocusSafe() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val state = read("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        assertFalse(activity.contains("gameCardHeightDp = layoutSpec.gameCardHeightDp"))
        assertFalse(activity.contains("gameCardHeightDp: Int"))
        assertFalse(activity.contains("cardHeightDp: Int? = null"))
        assertTrue(activity.contains("NovaLibraryPosterCard("))
        assertTrue(activity.contains(".aspectRatio(NovaLibraryUiStateMapper.posterAspectRatio())"))
        assertTrue(state.contains("largeText: Boolean = false"))
        assertTrue(stage.contains("largeText = largeText"))
        assertFalse(stage.contains(".isSuccess"))
        assertTrue(stage.windowed(".getOrDefault(false)".length).count { it == ".getOrDefault(false)" } >= 2)
        assertTrue(stage.contains("private fun NovaStageHeroAction("))
        assertTrue(stage.contains(".height(if (largeText) 42.dp else 40.dp)"))
        assertTrue(stage.contains(".height(if (largeText) 34.dp else 28.dp)"))
        assertTrue(stage.contains("focused = focusState.isFocused || focusState.hasFocus"))
    }

    @Test
    fun activeSessionControlsLiveInsideStageWithoutAStackedHomeHero() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        assertFalse(activity.contains("if (!stageMode || activeSession != null)"))
        assertTrue(activity.windowed("showStandaloneHomeHero(".length).count { it == "showStandaloneHomeHero(" } >= 2)
        assertTrue(activity.contains("activeSession: NovaLibraryActiveSessionUiState?"))
        assertTrue(activity.contains("activeSession = activeSession"))
        assertTrue(activity.contains("activeSession?.let(onResumeSession)"))
        assertTrue(activity.contains("activeSession?.let(onEndSession)"))
        assertTrue(activity.contains("val showStageContent ="))
        assertTrue(activity.contains("NovaLibraryUiStateMapper.shouldRenderStageContent("))
        assertTrue(activity.contains("NovaLibraryUiStateMapper.stageFocusedGame("))
        assertTrue(activity.contains("model.hero.reason != NovaLibraryHeroReason.ACTIVE_SESSION"))
        assertTrue(activity.contains("model.filteredGames.isEmpty() && !showStageContent"))
        assertFalse(activity.contains("model.hero.game != null"))
        assertTrue(stage.contains("sessionTitle: String? = null"))
        assertTrue(stage.contains("nova-stage-session-only-hero"))
        assertTrue(activity.contains("secondaryActionLabel = model.hero.secondaryActionLabel"))
        assertTrue(activity.contains("sessionActionLabel = if ("))
        assertTrue(stage.contains("sessionActionLabel: String? = null"))
        assertTrue(stage.contains("onSessionAction: (() -> Unit)? = null"))
        assertTrue(stage.contains("secondaryActionLabel: String? = null"))
        assertTrue(stage.contains("onSecondaryAction: (() -> Unit)? = null"))
        assertTrue(stage.contains("nova-stage-session-action"))
        assertTrue(stage.contains("nova-stage-secondary-action"))
        assertTrue(activity.contains("NovaLibraryLandscapeStageShell("))
        assertTrue(stage.contains("internal fun NovaLibraryLandscapeStageShell("))
        assertTrue(stage.contains("stageLayoutSpecForViewport("))
    }

    @Test
    fun coldStartQueriesActiveSessionIndependentlyBeforeLibraryLoading() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val onCreate = activity.substringAfter("setContentView(content)").substringBefore("private val hasActiveLibraryOverlay")
        val load = activity.substringAfter("private fun loadGames").substringBefore("private fun refreshActiveSession")
        assertTrue(onCreate.indexOf("refreshActiveSession(") < onCreate.indexOf("loadGames("))
        assertFalse(load.contains("queryActiveSession()") || load.contains("result.activeSession"))

    }

    @Test
    fun sessionRefreshPublicationIsGenerationFencedAcrossRefreshEndStopAndResume() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val onResume = activity.substringAfter("override fun onResume()").substringBefore("private fun recreateForThemeChangeIfNeeded()")
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("private fun loadGames")
        assertTrue(activity.contains("private val activeSessionRefreshGate = NovaActiveSessionRefreshGate()"))
        assertTrue(activity.contains("private var activeSessionImmediateRefreshJob: Job? = null"))
        assertTrue(activity.contains("val generation = beginActiveSessionRefresh()"))
        assertTrue(activity.contains("activeSessionRefreshGate.publishIfCurrent(generation)"))
        assertTrue(activity.contains("generation: Long"))
        assertTrue(activity.contains("activeSessionRefreshGate.isCurrent(generation)"))
        assertTrue(onStop.contains("activeSessionRefreshGate.invalidateForStop()"))
        assertTrue(onResume.contains("activeSessionRefreshGate.shouldRefreshOnResume(isInitialLoading)"))
        assertTrue(onResume.contains("refreshActiveSession(scheduleFollowUps = true)"))
        assertFalse(onResume.contains("&& !isInitialLoading"))
        assertTrue(activity.contains("if (activeSessionImmediateRefreshJob === launched)"))
    }

    @Test
    fun activeSessionSurvivesIndependentGameFailureAndToolbarActionsStayTouchSized() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val state = read("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        assertTrue(activity.contains("NovaLibraryUiStateMapper.shouldShowLoadFailure("))
        assertTrue(state.contains("heroReason != NovaLibraryHeroReason.ACTIVE_SESSION"))
        assertTrue(state.contains("if (largeText) 74 else 60"))
        assertTrue(stage.windowed("minHeight = 48.dp".length).count { it == "minHeight = 48.dp" } >= 2)
        assertTrue(stage.contains(".padding(horizontal = 10.dp, vertical = 5.5.dp)"))
        assertTrue(stage.contains("nova-library-toolbar-options"))
        assertTrue(stage.contains("nova-library-toolbar-system-menu"))
    }

    @Test
    fun libraryToolbarSourceKeepsIdentityMetadataAndActionsInRightAlignedOrder() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val landscape = stage
            .substringAfter("internal fun NovaLibraryLandscapeToolbarContent(")
            .substringBefore("internal fun NovaLibraryPortraitToolbarContent(")
        val portrait = stage
            .substringAfter("internal fun NovaLibraryPortraitToolbarContent(")
            .substringBefore("internal fun NovaLibraryStage(")

        val landscapeIdentity = landscape.indexOf("NovaLibraryToolbarIdentity(")
        val landscapeSpacer = landscape.indexOf("Spacer(modifier = Modifier.weight(1f))")
        val landscapeMeta = landscape.indexOf("NovaLibraryResultAndLayoutMeta(")
        val landscapeOptions = landscape.indexOf("NovaLibraryToolbarOptionsAction(")
        val landscapeSystem = landscape.indexOf("NovaLibraryToolbarSystemAction(")
        assertTrue(
            "landscape toolbar must be identity, weighted spacer, metadata, Options, then rightmost System",
            landscapeIdentity >= 0 &&
                landscapeIdentity < landscapeSpacer &&
                landscapeSpacer < landscapeMeta &&
                landscapeMeta < landscapeOptions &&
                landscapeOptions < landscapeSystem,
        )
        assertTrue(stage.contains("nova-library-toolbar-identity"))
        assertTrue(stage.contains("nova-library-toolbar-meta"))
        assertTrue(stage.contains("nova-library-toolbar-options"))
        assertTrue(stage.contains("nova-library-toolbar-system-menu"))

        val portraitIdentity = portrait.indexOf("NovaLibraryToolbarIdentity(")
        val portraitMeta = portrait.indexOf("NovaLibraryResultAndLayoutMeta(")
        val portraitOptions = portrait.indexOf("NovaLibraryToolbarOptionsAction(")
        val portraitSystem = portrait.indexOf("NovaLibraryToolbarSystemAction(")
        assertTrue(portrait.contains("BoxWithConstraints("))
        assertTrue(portrait.contains("val showMetadata = !largeText && maxWidth >= 400.dp"))
        assertTrue(
            "portrait toolbar must keep optional metadata before Options and rightmost System",
            portraitIdentity >= 0 &&
                portraitIdentity < portraitMeta &&
                portraitMeta < portraitOptions &&
                portraitOptions < portraitSystem,
        )
        assertTrue(portrait.contains("minHeight = 48.dp"))
    }

    @Test
    fun adaptiveControllerChromeStillObservesInputWithoutStealingNavigation() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        assertTrue(activity.contains("private var controllerHintChromeState by mutableStateOf(NovaControllerHintChromeState())"))
        assertTrue(activity.contains("override fun dispatchKeyEvent(event: KeyEvent): Boolean"))
        assertTrue(activity.contains("registerSuccessfulLibraryInput(NovaControllerHintChromeEvent.CONTROLLER_INPUT)"))
        assertTrue(activity.contains("override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean"))
        assertTrue(activity.contains("override fun dispatchTouchEvent(event: MotionEvent): Boolean"))
        assertTrue(activity.contains("controllerHintChromeState.visible"))
        assertTrue(activity.contains("AnimatedVisibility("))
        assertTrue(activity.contains("visible = stageMode || controllerHintsVisible"))
    }

    @Test
    fun stageSeparatesPresentationIdentityFromLoaderFenceAndOmitsUnavailableMarks() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val posterCard = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val artworkSources = stage + posterCard
        val presentationTag = "R.id.nova_artwork_presentation_key"

        assertTrue(artworkSources.windowed("view.getTag($presentationTag)".length).count { it == "view.getTag($presentationTag)" } >= 2)
        assertTrue(artworkSources.windowed("view.setTag($presentationTag".length).count { it == "view.setTag($presentationTag" } >= 2)
        assertFalse(stage.contains("view.tag = heroKey"))
        assertFalse(stage.contains("view.tag = logoKey"))
        assertFalse(stage.contains("view.tag = iconKey"))
        assertFalse(stage.contains("view.tag = posterKey"))
        assertFalse(stage.contains("view.tag = posterPresentationKey"))
        assertFalse(stage.contains("val hasLogo = game.logoArtwork != null"))
        assertFalse(stage.contains("if (hasLogo)"))
        assertFalse(stage.contains("nova-stage-logo"))
        assertTrue(stage.contains("val hasIcon = game.iconArtwork != null"))
        assertTrue(stage.contains("if (hasIcon)"))
        assertTrue(stage.contains("modifier = Modifier.weight(1f).testTag(\"nova-stage-title\")"))
    }

    @Test
    fun everyStagePosterSurfaceDelegatesToTheSharedCleanDetailOnlyCard() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val grid = stage
            .substringAfter("private fun NovaLibraryStagePosterGrid(")
            .substringBefore("internal fun NovaLibraryStageRow(")
        val row = stage
            .substringAfter("internal fun NovaLibraryStageRow(")
            .substringBefore("private const val STAGE_FOCUS_REQUEST_ATTEMPTS")
        val posterRegions = grid + row

        assertTrue(grid.contains("NovaLibraryPosterCard("))
        assertTrue(row.contains("NovaLibraryPosterCard("))
        assertTrue(stage.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" } == 2)
        assertTrue(grid.contains("showPosterTitle = showPosterTitles"))
        assertTrue(row.contains("showPosterTitle = showPosterTitles"))
        assertFalse(stage.contains("private fun NovaLibraryStageCard("))
        assertFalse(stage.contains("private fun NovaStagePill("))
        assertFalse(grid.contains("AndroidView("))
        assertFalse(grid.contains(".border("))
        listOf(
            "NovaStagePill(",
            "stageCardNeedsTextScrim(",
            "Brush.verticalGradient(",
            "nova_library_card_action_details",
            "nova_library_filter_recent",
            "game.hdrSupported",
            "game.lastLaunched",
            "game.source",
            "game.category",
            "Text(",
        ).forEach { forbidden ->
            assertFalse("legacy Stage poster chrome remains: $forbidden", posterRegions.contains(forbidden))
        }
    }

    @Test
    fun stageFocusOwnershipUsesTransientBlurSafeMapper() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")

        assertTrue(stage.contains("focusedCardId = NovaLibraryUiStateMapper.stageFocusOwnerAfterChange("))
        assertTrue(stage.contains("currentOwnerId = focusedCardId"))
        assertTrue(stage.contains("gameId = game.id"))
        assertTrue(stage.contains("isFocused = isFocused"))
        assertFalse(stage.contains("else if (focusedCardId == game.id) focusedCardId = null"))
    }


    @Test
    fun activityMountsTheSharedBackdropBelowParticlesAndWindowContent() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val screen = activity
            .substringAfter("private fun NovaLibraryScreen(")
            .substringBefore("private fun NovaLibraryHomeHero(")
        val backdrop = screen.indexOf("NovaLibraryCinematicBackdrop(")
        val particles = screen.indexOf("if (surfaces.particlesEnabled)")
        val windowContent = screen.indexOf(".background(surfaces.backgroundScrim)")

        assertTrue(backdrop >= 0)
        assertTrue(backdrop < particles)
        assertTrue(backdrop < windowContent)
        assertTrue(activity.contains("reserveControllerHintSpace = true"))
        assertFalse(activity.contains("reserveControllerHintSpace = !stageMode"))
        assertTrue(activity.contains("visible = stageMode || controllerHintsVisible"))
    }

    @Test
    fun sharedCinematicBackdropUsesRevisionFencedHeroFirstArtworkAndThemeGradients() {
        val chrome = read("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val imageUpdate = chrome.substringAfter("update = { view ->").substringBefore("modifier = Modifier")

        assertTrue(chrome.contains("internal fun NovaLibraryCinematicBackdrop("))
        assertTrue(chrome.contains("game: PolarisGame?"))
        assertTrue(chrome.contains("modifier: Modifier = Modifier"))
        assertTrue(chrome.contains("val hasCachedHero = game.artworkAsset(PolarisGame.ARTWORK_KIND_HERO)?.cached == true"))
        assertTrue(chrome.contains("if (hasCachedHero) PolarisGame.ARTWORK_KIND_HERO else PolarisGame.ARTWORK_KIND_POSTER"))
        assertTrue(chrome.contains("PolarisApiClient.artworkPresentationKey(game, artworkKind)"))
        assertEquals(1, chrome.windowed("Crossfade(".length).count { it == "Crossfade(" })
        assertTrue(chrome.contains("animationSpec = tween(durationMillis = 320)"))
        assertTrue(chrome.contains("targetState = backdropTarget"))
        assertTrue(chrome.contains("R.id.nova_artwork_presentation_key"))
        assertTrue(imageUpdate.contains("view.getTag(R.id.nova_artwork_presentation_key) != target.presentationKey"))
        assertTrue(imageUpdate.contains("view.setTag(R.id.nova_artwork_presentation_key, target.presentationKey)"))
        assertTrue(imageUpdate.indexOf("view.setImageDrawable(null)") < imageUpdate.indexOf("apiClient.loadArtworkInto("))
        assertTrue(imageUpdate.contains("apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)"))
        assertTrue(imageUpdate.contains("apiClient.loadCoverInto(view, target.game)"))
        assertTrue(chrome.contains("scaleType = ImageView.ScaleType.CENTER_CROP"))
        assertTrue(chrome.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(chrome.contains("isFocusable = false"))
        assertTrue(chrome.contains("isClickable = false"))
        assertTrue(chrome.contains("LocalNovaComposeColors.current"))
        assertTrue(chrome.contains("LocalNovaLibrarySurfaces.current"))
        assertTrue(chrome.contains("Brush.horizontalGradient("))
        assertTrue(chrome.contains("colors.window.copy(alpha = 0.75f)"))
        assertTrue(chrome.contains("colors.window.copy(alpha = 0.22f)"))
        assertTrue(chrome.contains("1.0f to Color.Transparent"))
        assertTrue(chrome.contains("Brush.verticalGradient("))
        assertTrue(chrome.contains("colors.window.copy(alpha = 0.14f)"))
        assertTrue(chrome.contains("colors.window.copy(alpha = 0.78f)"))
        assertFalse(chrome.contains("0xFF"))
        assertFalse(chrome.contains("PolarisStage"))
    }


    @Test
    fun stageLandscapeRailConsumesMapperOwnedRatioAndPresentationContracts() {
        val state = read("src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val row = stage
            .substringAfter("internal fun NovaLibraryStageRow(")
            .substringBefore("private const val STAGE_FOCUS_REQUEST_ATTEMPTS")

        assertTrue(row.contains("NovaLibraryUiStateMapper.posterPresentationSpec("))
        assertTrue(row.contains("NovaLibraryUiStateMapper.portraitPosterSizeForWidth("))
        assertTrue(row.contains("NovaLibraryUiStateMapper.portraitPosterSizeForRail("))
        assertTrue(row.contains(".widthDp"))
        assertTrue(row.contains(".heightDp"))
        assertTrue(row.contains("captionBudgetDp = stagePosterCaptionBudgetDp("))
        assertTrue(row.contains("railHeightDp - captionBudgetDp"))
        assertTrue(row.contains("artworkWidthDp = posterSize.widthDp"))
        assertTrue(row.contains("artworkHeightDp = posterSize.heightDp"))
        assertTrue(row.contains("cellWidthDp = artworkWidthDp + 2 * presentationSpec.focusGutterDp"))
        assertTrue(row.contains("cellHeightDp = artworkHeightDp + captionBudgetDp"))
        assertTrue(row.contains("cardWidthDp = cellWidthDp"))
        assertTrue(row.contains(".width(cellWidthDp.dp)"))
        assertTrue(row.contains(".height(cellHeightDp.dp)"))
        assertTrue(row.contains("layoutMode = NovaLibraryLayoutMode.STAGE"))
        assertTrue(stage.contains("private const val STAGE_POSTER_CAPTION_BUDGET_DP = 36"))
        assertTrue(stage.contains("private const val STAGE_LARGE_TEXT_POSTER_CAPTION_BUDGET_DP = 64"))
        assertFalse(stage.contains(".aspectRatio(2f / 3f)"))
        assertFalse(stage.contains("NovaLibraryUiStateMapper.stageCardHeightDp("))
        assertFalse(stage.contains("NovaLibraryUiStateMapper.stageConstrainedCardHeightDp("))
        assertFalse(state.contains("fun stageCardHeightDp("))
        assertFalse(state.contains("fun stageConstrainedCardHeightDp("))
        assertFalse(state.contains("1.6f"))
    }

    @Test
    fun sharedPosterCardUsesMapperOwnedStableCinematicPresentation() {
        val source = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")

        assertTrue(source.contains("internal fun NovaLibraryPosterCard("))
        assertTrue(source.contains("layoutMode: NovaLibraryLayoutMode"))
        assertTrue(source.contains("NovaLibraryUiStateMapper.posterPresentationSpec(layoutMode)"))
        assertTrue(source.contains("NovaLibraryUiStateMapper.posterAspectRatio()"))
        assertTrue(source.contains("NovaPosterAnimationDurationMillis = 180"))
        assertTrue(source.contains("animationSpec = tween(durationMillis = NovaPosterAnimationDurationMillis)"))
        assertTrue(source.contains(".zIndex(if (focused) 1f else 0f)"))
        assertTrue(source.contains("scaleX = scale") && source.contains("scaleY = scale"))
        assertTrue(source.contains("translationY = -lift.toPx()"))
        assertFalse(source.contains("NovaFocusMotionSpec.CardFocusedScale"))
        assertFalse(source.contains(".novaFocusMotion("))
    }

    @Test
    fun sharedPosterCardKeepsArtworkCleanAndCaptionBelowTheSurface() {
        val source = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val artwork = source
            .substringAfter("private fun NovaLibraryPosterArtwork(")
            .substringBefore("@Composable\nprivate fun NovaLibraryPosterCaption(")
        val caption = source
            .substringAfter("private fun NovaLibraryPosterCaption(")
            .substringBefore("private fun novaLibraryPosterMetadata(")

        assertFalse(source.contains(".border("))
        listOf(
            "NovaBadge(",
            "NovaMiniBadge(",
            "NovaStagePill(",
            "NovaLibraryCardBadgeRow(",
            "NovaLibraryCardTitleScrim(",
            "Brush.verticalGradient(",
        ).forEach { forbidden -> assertFalse("forbidden visual chrome: $forbidden", source.contains(forbidden)) }
        assertTrue(artwork.contains(".aspectRatio(NovaLibraryUiStateMapper.posterAspectRatio())"))
        assertTrue(artwork.indexOf(".graphicsLayer {") < artwork.indexOf(".testTag("))
        assertFalse(artwork.contains("Text("))
        assertTrue(source.contains("if (showPosterTitle) {"))
        assertTrue(caption.contains("maxLines = if (layoutMode == NovaLibraryLayoutMode.COMPACT) 1 else 2"))
        assertTrue(caption.contains(".testTag(\"nova-poster-caption-${'$'}{game.id}\")"))
        assertTrue(source.indexOf("NovaLibraryPosterArtwork(") < source.indexOf("NovaLibraryPosterCaption("))
    }

    @Test
    fun sharedPosterCardOwnsDetailSemanticsAndRevisionFencedArtwork() {
        val source = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")

        assertTrue(source.contains(".semantics(mergeDescendants = true)"))
        assertTrue(source.contains("contentDescription = accessibleLabel"))
        assertTrue(source.contains("role = Role.Button"))
        assertTrue(source.contains(".combinedClickable(") && source.contains("onClick = onOpenDetail"))
        assertTrue(source.windowed(".combinedClickable(".length).count { it == ".combinedClickable(" } == 1)
        assertFalse(source.contains(".focusable()"))
        assertFalse(source.contains("import androidx.compose.foundation.focusable"))
        assertTrue(source.contains("game.sourceLabel") && source.contains("game.categoryLabel"))
        assertTrue(source.contains("game.hdrSupported") && source.contains("game.lastLaunched > 0L"))
        assertTrue(source.contains("R.string.badge_hdr"))
        assertTrue(source.contains("R.string.nova_library_filter_recent"))
        assertTrue(source.contains("R.string.nova_library_card_action_details"))
        assertTrue(source.contains("R.id.nova_artwork_presentation_key"))
        assertTrue(source.contains("PolarisApiClient.artworkPresentationKey("))
        assertTrue(source.contains("posterLoader: ((ImageView, PolarisGame) -> Unit)? = null"))
        assertTrue(source.contains("val posterLoaderIdentity: Any = posterLoader ?: apiClient"))
        assertTrue(source.contains("remember(artworkRevisionKey, posterLoaderIdentity)"))
        assertTrue(source.contains("posterLoader?.invoke(view, game) ?: apiClient.loadCoverInto(view, game)"))
        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(source.contains("isFocusable = false"))
        assertTrue(source.contains("isClickable = false"))
        val signatureStart = source.indexOf("internal fun NovaLibraryPosterCard(")
        val signature = source.substring(signatureStart, source.indexOf("\n) {", signatureStart) + 4)
        assertTrue(signature.contains("onOpenDetail: () -> Unit"))
        assertFalse(signature.contains("onLaunch") || signature.contains("onStream") || signature.contains("onPrimaryAction"))
        assertFalse(source.contains("launchGame("))
    }

    @Test
    fun sharedPosterCardSupportsFocusAndNavigationAcrossStageAndActivityCallSites() {
        val source = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")

        assertTrue(source.contains("focusRequester: FocusRequester? = null"))
        assertTrue(source.contains("onFocusChanged: (Boolean) -> Unit = {}"))
        assertTrue(source.contains("onFocused: () -> Unit = {}"))
        assertTrue(source.contains("onNavigate: ((Int) -> Boolean)? = null"))
        val modifierStart = source.indexOf("modifier = modifier")
        val requesterIndex = source.indexOf(".then(focusRequesterModifier)", modifierStart)
        val focusObserverIndex = source.indexOf(".onFocusChanged", modifierStart)
        val clickOwnerIndex = source.indexOf(".combinedClickable(", modifierStart)
        assertTrue(requesterIndex >= 0 && requesterIndex < focusObserverIndex && focusObserverIndex < clickOwnerIndex)
        assertTrue(source.contains("Key.DirectionLeft -> onNavigate?.invoke(-1) ?: false"))
        assertTrue(source.contains("Key.DirectionRight -> onNavigate?.invoke(1) ?: false"))
        assertTrue(stage.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" } == 2)
        assertTrue(activity.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" } == 2)
    }



    @Test
    fun task9RequiresDurablePlainArtworkDefaultSemanticAndSourceContracts() {
        val requiredMethods = mapOf(
            "src/test/java/com/papi/nova/ui/NovaLibraryPreferencesTest.kt" to listOf(
                "fun freshOptionsStateAndLoadedOptionsDefaultToPlainArtwork()",
                "fun persistedPosterTitleChoicesRoundTripWithoutResettingUsers()",
            ),
            "src/test/java/com/papi/nova/ui/NovaLibraryLayoutV2Test.kt" to listOf(
                "fun freshOptionsStateDefaultsToPlainPosterArtwork()",
            ),
            "src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt" to listOf(
                "fun task9SharedPosterCardKeepsMetadataInAccessibilityOnly()",
                "fun task9SharedPosterCardUsesScaleOnlyWithoutVisualBadgesOrBorders()",
                "fun task9StageGridCompactAndRecentUseOnlySharedPosterCard()",
                "fun task9StageIdentityUsesOneManifestIconAndOneRenderedTitle()",
            ),
        )

        requiredMethods.forEach { (path, markers) ->
            val source = read(path)
            markers.forEach { marker ->
                assertTrue("Task 9 durable contract marker missing from $path: $marker", source.contains(marker))
            }
        }
    }

    @Test
    fun cinematicControllerHintsAreBorderlessRightAlignedAndPreserveFullSemantics() {
        val chrome = read("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val helperStart = chrome.indexOf("internal fun NovaLibraryCinematicControllerHints(")

        assertTrue("cinematic chrome should own the library-only controller hint renderer", helperStart >= 0)
        val helper = chrome.substring(helperStart)
        assertTrue(helper.contains("hints: List<NovaControllerHint>"))
        assertTrue(helper.contains("semanticsDescription: String"))
        assertTrue(helper.contains("compact: Boolean"))
        assertTrue(helper.contains("modifier: Modifier = Modifier"))
        assertTrue(helper.contains("val colors = LocalNovaComposeColors.current"))
        assertTrue(helper.contains("val surfaces = LocalNovaLibrarySurfaces.current"))
        assertTrue(helper.contains(".fillMaxWidth()"))
        assertTrue(helper.contains(".heightIn(min = 34.dp)"))
        assertTrue(helper.contains("contentAlignment = Alignment.CenterEnd"))
        // The hint row is chrome, not content: it carries no backing plate of its own and
        // reads against the backdrop's own bottom gradient.
        assertFalse(helper.contains("Brush.horizontalGradient("))
        assertFalse(helper.contains("focusedArtworkScrim"))
        assertTrue(helper.contains("contentDescription = semanticsDescription"))
        assertTrue(helper.contains(".testTag(\"nova-library-cinematic-controller-hints\")"))
        assertTrue(helper.contains(".testTag(\"nova-library-cinematic-controller-hints-row\")"))
        assertTrue(helper.contains(".widthIn(max = rowMaxWidth)"))
        assertTrue(helper.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(helper.contains("end = 12.dp"))
        assertTrue(helper.contains("vertical = 6.dp"))
        assertTrue(helper.contains("Arrangement.spacedBy(itemSpacing)"))
        assertTrue(helper.contains("CircleShape"))
        assertTrue(helper.contains(".background(colors.accent.copy("))
        assertFalse("library cinematic hints must not restore the old bordered panel", helper.contains(".border("))
        assertFalse("library cinematic hints must not use an enclosing panel surface", helper.contains("surfaces.panel"))
        assertFalse("library cinematic hints must not use an enclosing tile surface", helper.contains("surfaces.tile"))

        val screenStart = activity.indexOf("private fun NovaLibraryScreen(")
        val screenEnd = activity.indexOf("@Composable\n    private fun NovaLibraryHomeHero(", screenStart)
        val screen = activity.substring(screenStart, screenEnd)
        assertTrue(screen.contains("val controllerHints = novaLibraryControllerHints(isLandscape)"))
        assertTrue(screen.contains("val visibleControllerHints = if (largeText)"))
        assertTrue(screen.contains("controllerHints.filterIndexed { index, _ -> index in LARGE_TEXT_HINT_INDICES }"))
        assertTrue(screen.contains("val controllerHintDescription = controllerHints.joinToString(separator = \" · \")"))
        assertTrue(screen.contains("visible = stageMode || controllerHintsVisible"))
        assertTrue(screen.contains("NovaLibraryCinematicControllerHints("))
        assertTrue(screen.contains("hints = visibleControllerHints"))
        assertTrue(screen.contains("semanticsDescription = controllerHintDescription"))
        assertFalse(screen.contains("NovaControllerHintBar("))
        assertFalse(activity.contains("import com.papi.nova.ui.compose.NovaControllerHintBar"))
        assertFalse(activity.contains("controllerHintBarLandscapeStartPadding"))

        val mappingStart = activity.indexOf("private fun novaLibraryControllerHints(")
        val mappingEnd = activity.indexOf("@Composable\n    private fun NovaLibraryHomeHero(", mappingStart)
        val mapping = activity.substring(mappingStart, mappingEnd)
        val mappingTokens = listOf(
            "R.string.nova_controller_hint_a",
            "R.string.nova_controller_hint_select",
            "R.string.nova_controller_hint_b",
            "R.string.nova_controller_hint_back",
            "R.string.nova_controller_hint_x",
            "R.string.nova_controller_hint_library",
            "R.string.nova_controller_hint_y",
            "R.string.nova_controller_hint_layout",
            "R.string.menu_button",
            "R.string.nova_controller_hint_system",
            "R.string.nova_controller_hint_lb_rb",
            "R.string.nova_controller_hint_library_system",
        )
        var tokenCursor = 0
        mappingTokens.forEach { token ->
            tokenCursor = mapping.indexOf(token, tokenCursor)
            assertTrue("controller mapping must retain ${'$'}token in order", tokenCursor >= 0)
            tokenCursor += token.length
        }
        assertEquals(6, mapping.windowed("NovaControllerHint(".length).count { it == "NovaControllerHint(" })

        assertTrue(activity.contains("reserveControllerHintSpace = true"))
        assertFalse(activity.contains("reserveControllerHintSpace = !stageMode"))
        assertTrue(activity.contains(".padding(bottom = controllerHintBarBottomPadding)"))
        assertTrue(stage.contains("NovaLibraryUiStateMapper.stageControllerHintFooterHeightDp()"))
    }

    @Test
    fun cinematicControllerHintComposeFixtureProvesSafeBoundsSemanticsAndStageSeparation() {
        val source = read("src/androidTest/java/com/papi/nova/ui/NovaLibraryStageComposeTest.kt")

        assertTrue(source.contains("fun rp6LargeTextCinematicHintsStayRightAlignedAndClearOfStageRail()"))
        assertTrue(source.contains("private fun NovaLibraryCinematicControllerHintsRp6Fixture("))
        assertTrue(source.contains("Density(density.density, fontScale = 2f)"))
        assertTrue(source.contains(".requiredSize(833.dp, 390.dp)"))
        assertTrue(source.contains("assertContentDescriptionEquals(semanticsDescription)"))
        assertTrue(source.contains("rootBounds.right - rowBounds.right"))
        assertTrue(source.contains("trailingGapPx <= with(density) { 12.dp.toPx() } + 0.5f"))
        assertTrue(source.contains("rowBounds.width < rootBounds.width"))
        assertTrue(source.contains("rootBounds.height + 0.5f >= with(density) { 44.dp.toPx() }"))
        assertTrue(source.contains("railBounds.bottom <= rootBounds.top + 0.5f"))
    }

    @Test
    fun primaryStageActionUsesCompactVisibleSurfaceInsideAccessibleTarget() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val start = stage.indexOf("private fun NovaStageHeroAction(")
        val end = stage.indexOf("private fun NovaLibraryStagePosterGrid(", start)
        assertTrue(start >= 0 && end > start)
        val action = stage.substring(start, end)

        // The accessible target keeps its size; the emphasized variant widens only to seat
        // the controller glyph, and the visible surface always stays inside the target.
        assertTrue(action.contains("largeText -> 140.dp"))
        assertTrue(action.contains("showGlyph -> 138.dp"))
        assertTrue(action.contains("else -> 116.dp"))
        assertTrue(action.contains(".height(if (largeText) 42.dp else 40.dp)"))
        assertTrue(action.contains("largeText -> 132.dp"))
        assertTrue(action.contains("showGlyph -> 130.dp"))
        assertTrue(action.contains("else -> 108.dp"))
        assertTrue(action.contains(".height(if (largeText) 34.dp else 28.dp)"))
        assertTrue(action.contains(".testTag(\"${'$'}{testTag}-surface\")"))
        assertTrue(action.contains(".testTag(\"${'$'}{testTag}-label\")"))
        assertTrue(action.contains("val focusedScale = if (focused) 1.02f else 1f"))
        assertTrue(action.contains("colors.accent"))
        assertTrue(action.contains("maxLines = 1"))
        assertTrue(action.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(action.contains("role = Role.Button; contentDescription = label"))
        assertTrue(action.contains(".combinedClickable(onClick = onClick, onLongClick = onClick)"))
        assertTrue(action.contains(".focusable()"))
        assertFalse("Stage CTA must not restore the hard white outline", action.contains(".border("))

        val composeTest = read("src/androidTest/java/com/papi/nova/ui/NovaLibraryStageComposeTest.kt")
        assertTrue(composeTest.contains("nova-stage-primary-action-surface"))
        assertTrue(composeTest.contains("surfaceHeightDp <= 35f"))
        assertTrue(composeTest.contains("surfaceWidthDp <= 133f"))
        assertTrue(composeTest.contains("actionHeightDp >= 41.5f"))
        assertTrue(composeTest.contains("assertContentDescriptionEquals(\"Review & Launch\")"))
    }

}
