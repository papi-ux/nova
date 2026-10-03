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
        assertTrue(stage.contains("novaLibraryStageGeometry("))
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
        assertTrue(stage.contains("blendMode = androidx.compose.ui.graphics.BlendMode.DstIn"))
        assertFalse(stage.contains("Brush.verticalGradient("))
        assertFalse(stage.contains("NovaPolarisStageAtmosphere"))
        assertFalse(stage.contains("nova-stage-polaris-atmosphere"))
        assertFalse(stage.contains("import androidx.compose.foundation.Canvas"))
        assertTrue(stage.contains(".testTag(\"nova-library-stage\")"))
        assertFalse(stage.contains("BoxWithConstraints(modifier = Modifier.fillMaxSize().background("))
        assertTrue(stage.contains("PolarisGame.ARTWORK_KIND_LOGO"))
        assertFalse(stage.contains("PolarisGame.ARTWORK_KIND_ICON"))
        assertTrue(stage.contains("apiClient.loadArtworkInto(view, game, kind)"))
        assertTrue(stage.contains("apiClient.loadCoverInto(view, game)"))
    }

    @Test
    fun stageKeepsLaunchArtworkTouchControllerAndAccessibilityPathsImmediate() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val poster = read("src/main/java/com/papi/nova/ui/NovaLibraryPosterCard.kt")
        assertTrue(stage.contains("onOpenDetail: (PolarisGame) -> Unit"))
        val selectedClick = stage.substringAfter("internal fun NovaLibraryStage(")
            .substringAfter(".novaClickable(role = Role.Button)")
            .substringBefore(".testTag(\"nova-stage-selected-focus\")")
        assertTrue(selectedClick.contains("onOpenDetail(selected)"))
        assertTrue(selectedClick.contains("haptics.novaConfirm()"))
        assertTrue(stage.contains(".focusRequester(stageFocus)"))
        assertTrue(stage.contains("Key.DirectionLeft -> -1; Key.DirectionRight -> 1"))
        assertTrue(stage.contains("key = { _, game -> game.id }"))
        assertTrue(poster.contains("contentDescription = accessibleLabel"))
        assertFalse(stage.contains("launchGame("))
    }

    @Test
    fun stageWiresRevisionKeysPortraitRestoreAndDeclaredPosterDensity() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        assertTrue(stage.contains("restoreFocusGameId?.takeIf { it in ids }"))
        assertTrue(stage.contains("PolarisApiClient.artworkPresentationKey(game, PolarisGame.ARTWORK_KIND_LOGO)"))
        assertTrue(stage.contains("novaLibraryStageGeometry("))
        assertTrue(activity.contains("if (!isLandscape && model.optionsState.layoutMode == NovaLibraryLayoutMode.STAGE)"))
        assertFalse(stage.contains("stageRailPosterWidthDp("))
    }

    @Test
    fun renderedCardsConsumeAdaptiveHeightsAndStageActionsAreFocusSafe() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        assertTrue(stage.contains("geometry.selected.widthDp.dp"))
        assertTrue(stage.contains("geometry.neighbour.widthDp.dp"))
        assertTrue(stage.contains(".focusProperties { canFocus = false }"))
        assertTrue(stage.contains("stageFocus.requestFocus() }.getOrDefault(false)"))
        assertFalse(stage.contains("NovaStageHeroAction("))
        assertFalse(activity.contains("primaryActionLabel = stringResource(R.string.nova_library_review_and_launch)"))
    }

    @Test
    fun activeSessionControlsLiveInsideStageWithoutAStackedHomeHero() {
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val strip = read("src/main/java/com/papi/nova/ui/NovaLibraryHero.kt")
        assertTrue(activity.contains("continueSlot = if (showContinue)"))
        assertTrue(activity.contains("NovaLibraryShowcaseContinue("))
        assertTrue(activity.contains("activeSession?.let(onResumeSession)"))
        assertTrue(activity.contains("activeSession?.let(onEndSession)"))
        assertTrue(strip.contains("NovaSplitConfirm("))
        assertTrue(strip.contains("NOVA_LIBRARY_END_FAILED_TAG"))
        assertTrue(strip.contains("NovaLibraryEndingNotice()"))
        assertFalse(stage.contains("nova-stage-session-action"))
        assertFalse(stage.contains("nova-stage-secondary-action"))
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
        assertTrue(activity.contains("visible = space == null && (stageMode || controllerHintsVisible)"))
    }

    @Test
    fun stageSeparatesPresentationIdentityFromLoaderFenceAndOmitsUnavailableMarks() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        assertTrue(stage.contains("val logo = game.logoArtwork?.cached == true"))
        assertTrue(stage.contains("if (logo) {"))
        assertTrue(stage.contains("view.getTag(R.id.nova_artwork_presentation_key) != logoKey"))
        assertTrue(stage.contains("view.setTag(R.id.nova_artwork_presentation_key, logoKey)"))
        assertTrue(stage.contains("artworkLoader(view, game, PolarisGame.ARTWORK_KIND_LOGO)"))
        assertTrue(stage.contains("Text(game.name"))
        assertFalse(stage.contains("ARTWORK_KIND_ICON"))
    }

    @Test
    fun everyStagePosterSurfaceDelegatesToTheSharedCleanDetailOnlyCard() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        assertEquals(2, stage.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" })
        assertTrue(stage.contains("focusedOverride = stageFocused"))
        assertTrue(stage.contains("running = selected.id == runningGameId"))
        assertTrue(stage.contains("running = game.id == runningGameId"))
        assertFalse(stage.contains("private fun NovaLibraryStageCard("))
        assertFalse(stage.contains("private fun NovaStagePill("))
    }

    @Test
    fun stageFocusOwnershipUsesTransientBlurSafeMapper() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        assertEquals(1, stage.windowed(".focusRequester(stageFocus)".length).count { it == ".focusRequester(stageFocus)" })
        assertTrue(stage.contains("var selectedId by remember(ids)"))
        assertTrue(stage.contains("selectedId = game.id"))
        assertTrue(stage.contains(".testTag(\"nova-stage-selected-focus\")"))
        assertFalse(stage.contains("focusRequesters[index]"))
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
        assertTrue(activity.contains("visible = space == null && (stageMode || controllerHintsVisible)"))
    }

    @Test
    fun sharedCinematicBackdropUsesRevisionFencedHeroOnlyArtworkAndThemeGradients() {
        val chrome = read("src/main/java/com/papi/nova/ui/NovaLibraryCinematicChrome.kt")
        val imageUpdate = chrome.substringAfter("update = { view ->").substringBefore("modifier = Modifier")

        assertTrue(chrome.contains("internal fun NovaLibraryCinematicBackdrop("))
        assertTrue(chrome.contains("game: PolarisGame?"))
        assertTrue(chrome.contains("modifier: Modifier = Modifier"))
        // One rule for every entry, owned by the mapper: a real hero or the ambient field. A
        // poster stretched full-bleed was a slice of its wordmark behind the whole library.
        assertTrue(chrome.contains("NovaLibraryUiStateMapper.cinematicBackdropArtworkKind(game) ?: return@let null"))
        assertFalse(chrome.contains("PolarisGame.ARTWORK_KIND_POSTER"))
        assertTrue(chrome.contains("PolarisApiClient.artworkPresentationKey(game, artworkKind)"))
        assertEquals(1, chrome.windowed("Crossfade(".length).count { it == "Crossfade(" })
        assertTrue(chrome.contains("animationSpec = tween(durationMillis = 320)"))
        assertTrue(chrome.contains("targetState = backdropTarget"))
        assertTrue(chrome.contains("R.id.nova_artwork_presentation_key"))
        assertTrue(imageUpdate.contains("view.getTag(R.id.nova_artwork_presentation_key) != target.presentationKey"))
        assertTrue(imageUpdate.contains("view.setTag(R.id.nova_artwork_presentation_key, target.presentationKey)"))
        assertTrue(imageUpdate.indexOf("view.setImageDrawable(null)") < imageUpdate.indexOf("apiClient.loadArtworkInto("))
        assertTrue(imageUpdate.contains("apiClient.loadArtworkInto(view, target.game, PolarisGame.ARTWORK_KIND_HERO)"))
        assertFalse(imageUpdate.contains("apiClient.loadCoverInto("))
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
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val geometry = read("src/main/java/com/papi/nova/ui/NovaLibraryStageGeometry.kt")
        assertTrue(stage.contains("novaLibraryStageGeometry((maxWidth + 20.dp).value.roundToInt(), maxHeight.value.roundToInt(), density.fontScale, captionHeightDp)"))
        assertTrue(geometry.contains("NovaPortraitPosterSize(selectedUnits * 2, selectedUnits * 3)"))
        assertTrue(geometry.contains("posterGapDp: Int = 12"))
        assertFalse(stage.contains("STAGE_POSTER_WIDTH_FRACTION"))
        assertFalse(stage.contains("NovaStageEdgeScrollSpec"))
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
        assertTrue(source.contains(".zIndex(if (visualFocused) 1f else 0f)"))
        // Lift plus the one ring (spec section 2), no scale.
        assertFalse(source.contains("scaleX = scale") || source.contains("scaleY = scale"))
        assertTrue(source.contains("NovaPanelMetrics.FocusRingWidth"))
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
        assertTrue(artwork.contains("if (running)"))
        assertTrue(artwork.contains("nova-poster-running-"))
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
        // Acts on release, and only on the poster the press began on.
        assertTrue(source.contains(".novaClickable(") && source.contains("onOpenDetail()"))
        assertTrue(source.windowed(".novaClickable(".length).count { it == ".novaClickable(" } == 1)
        assertFalse(source.contains(".focusable()"))
        assertFalse(source.contains("import androidx.compose.foundation.focusable"))
        assertTrue(source.contains("game.sourceLabel") && source.contains("game.categoryLabel"))
        assertTrue(source.contains("game.hdrSupported") && source.contains("game.lastLaunched > 0"))
        assertTrue(source.contains("R.string.badge_hdr"))
        assertTrue(source.contains("R.string.nova_library_meta_last_played"))
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
        val clickOwnerIndex = source.indexOf(".novaClickable(", modifierStart)
        assertTrue(requesterIndex >= 0 && requesterIndex < focusObserverIndex && focusObserverIndex < clickOwnerIndex)
        assertTrue(source.contains("Key.DirectionLeft -> onNavigate?.invoke(-1) ?: false"))
        assertTrue(source.contains("Key.DirectionRight -> onNavigate?.invoke(1) ?: false"))
        assertTrue(stage.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" } == 2)
        assertTrue(activity.windowed("NovaLibraryPosterCard(".length).count { it == "NovaLibraryPosterCard(" } == 1)
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
            "src/test/java/com/papi/nova/ui/NovaLibrarySourceGuardTest.kt" to listOf(
                "fun task9SharedPosterCardKeepsMetadataInAccessibilityOnly()",
                // Posters moved from a scale to the lift and the one ring (spec section 2).
                "fun task9SharedPosterCardUsesLiftAndTheOneRingWithoutBadgesOrBorders()",
                "fun task9StageGridAndCompactUseOnlySharedPosterCard()",
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
    fun cinematicControllerHintsAreThePanelHintBarRightAlignedAndPreserveFullSemantics() {
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
        assertTrue(helper.contains(".fillMaxWidth()"))
        assertTrue(helper.contains(".heightIn(min = 34.dp)"))
        assertTrue(helper.contains("contentAlignment = Alignment.CenterEnd"))
        // No wide scrim of its own behind the row: it reads against the backdrop's bottom gradient.
        assertFalse(helper.contains("Brush.horizontalGradient("))
        assertFalse(helper.contains("focusedArtworkScrim"))
        assertTrue(helper.contains("contentDescription = semanticsDescription"))
        assertTrue(helper.contains(".testTag(\"nova-library-cinematic-controller-hints\")"))
        assertTrue(helper.contains(".testTag(\"nova-library-cinematic-controller-hints-row\")"))
        assertTrue(helper.contains(".widthIn(max = rowMaxWidth)"))
        assertTrue(helper.contains("end = 12.dp"))
        assertTrue(helper.contains("vertical = 6.dp"))
        // N13 (smoke test 2026-09-29): the borderless round chips read at low contrast on the
        // Shield. The row is the one panel hint bar now, its plate as wide as its hints and at the
        // end, and its hints wrap rather than scroll. Its own words are cleared, so the row's
        // description, every hint including those it leaves out, is the one that is read.
        assertTrue(helper.contains("NovaPanelHintBar(hints = hints)"))
        assertTrue(helper.contains(".width(IntrinsicSize.Max)"))
        assertTrue(helper.contains(".clearAndSetSemantics { }"))
        assertFalse("hints wrap rather than scroll one under the edge", helper.contains(".horizontalScroll("))
        assertFalse("no second chip style beside the panels' own", helper.contains("CircleShape"))

        val screenStart = activity.indexOf("private fun NovaLibraryScreen(")
        val screenEnd = activity.indexOf("@Composable\n    private fun NovaLibraryHomeHero(", screenStart)
        val screen = activity.substring(screenStart, screenEnd)
        // A remote is named by its own keys, a controller by its own (C04).
        assertTrue(screen.contains("val controllerHints = if (lastInputRemote) novaLibraryRemoteHints() else novaLibraryControllerHints(isLandscape)"))
        assertTrue(screen.contains("val visibleControllerHints = when {"))
        assertTrue(screen.contains("controllerHints.filterIndexed { index, _ -> index in LARGE_TEXT_HINT_INDICES }"))
        assertTrue(screen.contains("val controllerHintDescription = controllerHints.joinToString(separator = \" · \")"))
        assertTrue(screen.contains("visible = space == null && (stageMode || controllerHintsVisible)"))
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
            "R.string.nova_controller_hint_options",
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
        assertFalse(stage.contains("stageControllerHintFooterHeightDp()"))
    }

    @Test
    fun stageComposeFixtureProvesLargeTextIdentityCounterAndRailSeparation() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val native = read("src/androidTest/java/com/papi/nova/ui/NovaLibraryStageComposeTest.kt")
        assertTrue(stage.contains("lineHeight = 14.sp"))
        assertTrue(stage.contains(".testTag(\"nova-stage-position\")"))
        assertTrue(native.contains("largeTextIdentityRailAndCounterStayWithinTheStageContent"))
    }

    @Test
    fun primaryStageActionUsesCompactVisibleSurfaceInsideAccessibleTarget() {
        val stage = read("src/main/java/com/papi/nova/ui/NovaLibraryStage.kt")
        val activity = read("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        assertFalse(stage.contains("nova-stage-primary-action"))
        assertFalse(stage.contains("NovaStageHeroAction("))
        assertFalse(activity.contains("primaryActionLabel = stringResource(R.string.nova_library_review_and_launch)"))
        assertTrue(stage.contains("onOpenDetail(selected)"))
    }

}
