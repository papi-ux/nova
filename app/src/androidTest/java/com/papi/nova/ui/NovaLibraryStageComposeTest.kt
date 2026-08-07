package com.papi.nova.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaLibraryStageComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retroidLandscapeStageContainsIdentityActionsAndPosterRail() {
        enterControllerInputMode()
        val games = games()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val observedDensity = AtomicReference<Density>()

        composeRule.setContent {
            NovaComposeTheme {
                val density = LocalDensity.current
                observedDensity.set(density)
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 2f),
                ) {
                    // ACTIVE_SESSION_RP6_FIXTURE
                    Box(
                        Modifier
                            .requiredSize(width = 833.dp, height = 390.dp)
                            .windowInsetsPadding(WindowInsets(0.dp, 4.dp, 0.dp, 4.dp))
                            .padding(NovaLibraryUiStateMapper.screenPaddingDp(isLandscape = true).dp)
                    ) {
                        NovaLibraryLandscapeStageShell(
                            modifier = Modifier.fillMaxSize(),
                            reserveControllerHintSpace = false,
                        ) {
                            NovaLibraryLandscapeToolbarContent(
                                hostLabel = "Polaris",
                                resultCount = games.size,
                                layoutLabel = "Stage",
                                polarisReady = true,
                                cinematic = true,
                                onOpenOptions = {},
                                onOpenSystemMenu = {},
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                    NovaLibraryStage(
                        games = games,
                        focusedGame = games[1],
                        restoreFocusGameId = games[1].id,
                        primaryActionLabel = "Review & Launch",
                        sessionActionLabel = "Resume Stream",
                        secondaryActionLabel = "End Session",
                        apiClient = apiClient,
                        showPosterTitles = true,
                        onPrimaryAction = {},
                        onSessionAction = {},
                        onSecondaryAction = {},
                        onGameFocused = {},
                        onOpenDetail = {},
                        artworkLoader = { _, _, _ -> },
                        posterLoader = { _, _ -> },
                                    )
                                }
                            }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("nova-stage-landscape-rail").assertIsDisplayed()
        val restoredPoster = composeRule.onNode(hasContentDescription("Bravo", substring = true))
        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                restoredPoster.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        val primaryAction = composeRule.onNodeWithTag("nova-stage-primary-action")
        primaryAction
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertContentDescriptionEquals("Review & Launch")
        composeRule.onAllNodesWithTag("nova-stage-artwork-action").assertCountEquals(0)
        composeRule.onAllNodesWithTag("nova-stage-cinematic-backdrop").assertCountEquals(0)
        composeRule.onNodeWithTag("nova-stage-session-action").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("nova-stage-secondary-action").assertIsDisplayed().assertHasClickAction()
        assertTrue(primaryAction.fetchSemanticsNode().boundsInRoot.height >= 42f)
        primaryAction.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                primaryAction.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        val toolbarBounds = composeRule.onNodeWithTag("nova-library-landscape-toolbar").fetchSemanticsNode().boundsInRoot
        val stageBounds = composeRule.onNodeWithTag("nova-library-stage").fetchSemanticsNode().boundsInRoot
        val pixelsPerDp = observedDensity.get().density
        val railBounds = composeRule.onNodeWithTag("nova-stage-landscape-rail").fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithTag("nova-stage-poster-${games.first().id}").fetchSemanticsNode().boundsInRoot
        val actionBounds = composeRule.onNodeWithTag("nova-stage-primary-action").fetchSemanticsNode().boundsInRoot
        val surfaceBounds = composeRule.onNodeWithTag("nova-stage-primary-action-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("toolbarDp=${toolbarBounds.height / pixelsPerDp} px=${toolbarBounds.height} density=$pixelsPerDp", toolbarBounds.height / pixelsPerDp in 61f..63f)
        assertTrue(stageBounds.height / pixelsPerDp in 285f..287f)
        val railHeightDp = railBounds.height / pixelsPerDp
        assertTrue("railDp=$railHeightDp px=${railBounds.height} density=$pixelsPerDp", railHeightDp in 146f..156f)
        val cardHeightDp = cardBounds.height / pixelsPerDp
        assertTrue("cardDp=$cardHeightDp railDp=$railHeightDp", cardHeightDp <= railHeightDp)
        val posterArtBounds = composeRule.onNodeWithTag("nova-poster-art-${games[1].id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val posterCaptionBounds = composeRule.onNodeWithTag("nova-poster-caption-${games[1].id}", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "caption top ${posterCaptionBounds.top} overlaps artwork bottom ${posterArtBounds.bottom}",
            posterCaptionBounds.top >= posterArtBounds.bottom - 1f,
        )
        val actionHeightDp = actionBounds.height / pixelsPerDp
        val surfaceHeightDp = surfaceBounds.height / pixelsPerDp
        assertTrue("actionHeightDp=$actionHeightDp", actionHeightDp >= 41.5f)
        assertTrue("surfaceHeightDp=$surfaceHeightDp", surfaceHeightDp <= 35f)
        assertContained(actionBounds, surfaceBounds, "primary action surface in action row")
        assertContained(stageBounds, actionBounds, "primary action row in stage")
        listOf(
            "nova-stage-identity",
            "nova-stage-primary-action",
            "nova-stage-landscape-rail",
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertContained(stageBounds, bounds, tag)
        }
    }

    @Test
    fun normalTextLandscapeToolbarKeepsRightAlignedOrderedTouchTargets() {
        assertLandscapeToolbarLayout(fontScale = 1f)
    }

    @Test
    fun largeTextLandscapeToolbarKeepsRightAlignedOrderedTouchTargetsWithoutOverlap() {
        assertLandscapeToolbarLayout(fontScale = 2f)
    }

    @Test
    fun narrowPortraitToolbarKeepsMetadataBeforeRightAlignedActions() {
        assertPortraitToolbarLayout(fontScale = 1f, widthDp = 420, expectMetadata = true)
    }

    @Test
    fun narrowLargeTextPortraitToolbarCollapsesMetadataBeforeActionsOverlap() {
        assertPortraitToolbarLayout(fontScale = 2f, widthDp = 360, expectMetadata = false)
    }

    private fun assertLandscapeToolbarLayout(fontScale: Float) {
        val observedDensity = AtomicReference<Density>()
        val heightDp = if (fontScale >= 1.5f) 74 else 60
        composeRule.setContent {
            NovaComposeTheme {
                val density = LocalDensity.current
                observedDensity.set(density)
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = fontScale),
                ) {
                    Box(Modifier.requiredSize(width = 833.dp, height = heightDp.dp)) {
                        NovaLibraryLandscapeToolbarContent(
                            hostLabel = "A very long Polaris living-room host",
                            resultCount = 12,
                            layoutLabel = "Stage",
                            polarisReady = true,
                            onOpenOptions = {},
                            onOpenSystemMenu = {},
                        )
                    }
                }
            }
        }

        val pixelsPerDp = observedDensity.get().density
        val toolbar = bounds("nova-library-landscape-toolbar")
        val identity = bounds("nova-library-toolbar-identity")
        val metadata = bounds("nova-library-toolbar-meta")
        val options = bounds("nova-library-toolbar-options")
        val systemMenu = bounds("nova-library-toolbar-system-menu")
        assertOrdered(identity, metadata, "landscape identity", "landscape metadata")
        assertOrdered(metadata, options, "landscape metadata", "landscape Options")
        assertOrdered(options, systemMenu, "landscape Options", "landscape System")
        assertTrue("System must own the landscape toolbar right edge: $systemMenu in $toolbar", kotlin.math.abs(toolbar.right - systemMenu.right) / pixelsPerDp <= 12f)
        assertTouchTargetsAndContainment(toolbar, options, systemMenu, pixelsPerDp, "landscape")
        assertContained(toolbar, identity, "landscape identity")
        assertContained(toolbar, metadata, "landscape metadata")
    }

    private fun assertPortraitToolbarLayout(
        fontScale: Float,
        widthDp: Int,
        expectMetadata: Boolean,
    ) {
        val observedDensity = AtomicReference<Density>()
        val heightDp = if (fontScale >= 1.5f) 84 else 72
        composeRule.setContent {
            NovaComposeTheme {
                val density = LocalDensity.current
                observedDensity.set(density)
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = fontScale),
                ) {
                    Box(Modifier.requiredSize(width = widthDp.dp, height = heightDp.dp)) {
                        NovaLibraryPortraitToolbarContent(
                            hostLabel = "A very long Polaris portrait host",
                            resultCount = 12,
                            layoutLabel = "Stage",
                            polarisReady = true,
                            onOpenOptions = {},
                            onOpenSystemMenu = {},
                        )
                    }
                }
            }
        }

        val pixelsPerDp = observedDensity.get().density
        val toolbar = bounds("nova-library-portrait-toolbar")
        val identity = bounds("nova-library-toolbar-identity")
        val options = bounds("nova-library-toolbar-options")
        val systemMenu = bounds("nova-library-toolbar-system-menu")
        val metadataNodes = composeRule.onAllNodesWithTag("nova-library-toolbar-meta").fetchSemanticsNodes()
        assertEquals(if (expectMetadata) 1 else 0, metadataNodes.size)
        if (metadataNodes.isNotEmpty()) {
            val metadata = metadataNodes.single().boundsInRoot
            assertOrdered(identity, metadata, "portrait identity", "portrait metadata")
            assertOrdered(metadata, options, "portrait metadata", "portrait Options")
            assertContained(toolbar, metadata, "portrait metadata")
        } else {
            assertOrdered(identity, options, "portrait identity", "portrait Options")
        }
        assertOrdered(options, systemMenu, "portrait Options", "portrait System")
        assertTrue("System must own the portrait toolbar right edge: $systemMenu in $toolbar", kotlin.math.abs(toolbar.right - systemMenu.right) / pixelsPerDp <= 12f)
        assertTouchTargetsAndContainment(toolbar, options, systemMenu, pixelsPerDp, "portrait")
        assertContained(toolbar, identity, "portrait identity")
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun assertOrdered(left: Rect, right: Rect, leftLabel: String, rightLabel: String) {
        assertTrue("$leftLabel overlaps $rightLabel: $left then $right", left.right <= right.left)
    }

    private fun assertTouchTargetsAndContainment(
        toolbar: Rect,
        options: Rect,
        systemMenu: Rect,
        pixelsPerDp: Float,
        layout: String,
    ) {
        assertTrue("$layout Options target is ${options.height / pixelsPerDp}dp", options.height / pixelsPerDp >= 47.5f)
        assertTrue("$layout System target is ${systemMenu.height / pixelsPerDp}dp", systemMenu.height / pixelsPerDp >= 47.5f)
        assertContained(toolbar, options, "$layout Options")
        assertContained(toolbar, systemMenu, "$layout System")
    }

    @Test
    fun stagePosterTitleChoiceUsesOnlyTheSharedBelowArtworkCaptionAndNoVisualBadges() {
        enterControllerInputMode()
        val showTitles = mutableStateOf(false)
        val badgeGame = game("alpha", "Alpha", "steam").copy(
            hdrSupported = true,
            lastLaunched = 1L,
            category = "fast_action",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.requiredSize(width = 800.dp, height = 220.dp)) {
                    NovaLibraryStageRow(
                        games = listOf(badgeGame, game("bravo", "Bravo", "epic")),
                        apiClient = apiClient,
                        isLandscape = true,
                        posterColumns = 5,
                        restoreFocusGameId = badgeGame.id,
                        showPosterTitles = showTitles.value,
                        onGameFocused = {},
                        onOpenDetail = {},
                        coverLoader = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nova-poster-${badgeGame.id}")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onAllNodesWithTag("nova-poster-caption-${badgeGame.id}").assertCountEquals(0)
        listOf("HDR", "Recent", "Details", "Steam", "Fast action").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true, useUnmergedTree = true)
                .assertCountEquals(0)
        }

        composeRule.runOnIdle { showTitles.value = true }
        val artBounds = composeRule.onNodeWithTag("nova-poster-art-${badgeGame.id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val captionBounds = composeRule.onNodeWithTag("nova-poster-caption-${badgeGame.id}", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "caption top ${captionBounds.top} overlaps artwork bottom ${artBounds.bottom}",
            captionBounds.top >= artBounds.bottom - 1f,
        )
        listOf("HDR", "Recent", "Details", "Steam", "Fast action").forEach { forbidden ->
            composeRule.onAllNodesWithText(forbidden, substring = true, useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    @Test
    fun stagePosterFocusScalesArtworkWithoutReflowingItsStableCellOrRail() {
        enterControllerInputMode()
        val games = games()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.requiredSize(width = 800.dp, height = 220.dp)) {
                    NovaLibraryStageRow(
                        games = games,
                        apiClient = apiClient,
                        isLandscape = true,
                        posterColumns = 5,
                        restoreFocusGameId = games.first().id,
                        showPosterTitles = false,
                        onGameFocused = {},
                        onOpenDetail = {},
                        coverLoader = { _, _ -> },
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                composeRule.onNodeWithTag("nova-poster-${games.first().id}").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.onAllNodesWithTag("nova-poster-caption-${games.first().id}").assertCountEquals(0)
        val targetPoster = composeRule.onNodeWithTag("nova-poster-${games[1].id}")
        val artBefore = composeRule.onNodeWithTag("nova-poster-art-${games[1].id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val cellBefore = composeRule.onNodeWithTag("nova-stage-poster-${games[1].id}")
            .fetchSemanticsNode().boundsInRoot
        val railBefore = composeRule.onNodeWithTag("nova-stage-landscape-rail")
            .fetchSemanticsNode().boundsInRoot

        composeRule.mainClock.autoAdvance = false
        try {
            targetPoster.performSemanticsAction(SemanticsActions.RequestFocus)
            composeRule.mainClock.advanceTimeBy(250L)
            composeRule.waitForIdle()

            val artAfter = composeRule.onNodeWithTag("nova-poster-art-${games[1].id}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val cellAfter = composeRule.onNodeWithTag("nova-stage-poster-${games[1].id}")
                .fetchSemanticsNode().boundsInRoot
            val railAfter = composeRule.onNodeWithTag("nova-stage-landscape-rail")
                .fetchSemanticsNode().boundsInRoot
            assertTrue("focused artwork width did not grow: $artBefore -> $artAfter", artAfter.width > artBefore.width + 1f)
            assertTrue("focused artwork height did not grow: $artBefore -> $artAfter", artAfter.height > artBefore.height + 1f)
            assertRectStable(cellBefore, cellAfter, "Stage poster cell")
            assertRectStable(railBefore, railAfter, "Stage poster rail")
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun unmatchedActiveSessionRendersSessionOnlyHeroAndControls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val resumed = AtomicInteger(0)
        val ended = AtomicInteger(0)
        val unrelatedGames = games()
        val launched = AtomicInteger(0)

        val detailed = AtomicInteger(0)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.requiredSize(width = 817.dp, height = 274.dp)) {
                    NovaLibraryStage(
                        games = unrelatedGames,
                        focusedGame = null,
                        restoreFocusGameId = null,
                        primaryActionLabel = "Launch",
                        sessionTitle = "Desktop on Pixel",
                        sessionSupportingLine = "Watch • Pixel • 2 viewers",
                        sessionActionLabel = "Watch Stream",
                        secondaryActionLabel = "End Session",
                        apiClient = apiClient,
                        showPosterTitles = true,
                        onPrimaryAction = { launched.incrementAndGet() },
                        onSessionAction = { resumed.incrementAndGet() },
                        onSecondaryAction = { ended.incrementAndGet() },

                        onGameFocused = {},
                        onOpenDetail = { detailed.incrementAndGet() },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nova-stage-session-only-hero").assertIsDisplayed()
        composeRule.onAllNodesWithTag("nova-stage-primary-action").assertCountEquals(0)
        composeRule.onAllNodesWithTag("nova-stage-artwork-action").assertCountEquals(0)
        composeRule.onNodeWithTag("nova-stage-landscape-rail").assertIsDisplayed()
        composeRule.onNodeWithTag("nova-stage-session-action").performClick()
        composeRule.onNodeWithTag("nova-stage-secondary-action").performClick()
        assertEquals(1, resumed.get())
        assertEquals(1, ended.get())
        assertEquals(0, launched.get())

        assertEquals(0, detailed.get())
    }

    @Test
    fun pixelPortraitStageReflowsToAContainedVerticalPosterGrid() {
        enterControllerInputMode()
        val games = games()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.requiredSize(width = 430.dp, height = 932.dp)) {
                    NovaLibraryStage(
                        games = games,
                        focusedGame = games[1],
                        restoreFocusGameId = games[1].id,
                        primaryActionLabel = "Launch",
                        apiClient = apiClient,
                        showPosterTitles = true,
                        onPrimaryAction = {},
                        onGameFocused = {},
                        onOpenDetail = {},
                        artworkLoader = { _, _, _ -> },
                        posterLoader = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nova-stage-portrait-grid").assertIsDisplayed()
        val stageBounds = composeRule.onNodeWithTag("nova-library-stage").fetchSemanticsNode().boundsInRoot
        val identityBounds = composeRule.onNodeWithTag("nova-stage-identity").fetchSemanticsNode().boundsInRoot
        val gridBounds = composeRule.onNodeWithTag("nova-stage-portrait-grid").fetchSemanticsNode().boundsInRoot
        assertContained(stageBounds, identityBounds, "portrait identity")
        assertContained(stageBounds, gridBounds, "portrait grid")
        assertTrue("portrait grid should settle below identity", gridBounds.top >= identityBounds.bottom)
        val restored = composeRule.onNode(hasContentDescription("Bravo", substring = true))
        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                restored.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("nova-poster-${games[1].id}")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag("nova-stage-poster-${games[1].id}").assertIsDisplayed()
        val portraitArt = composeRule.onNodeWithTag("nova-poster-art-${games[1].id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val portraitCaption = composeRule.onNodeWithTag("nova-poster-caption-${games[1].id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(portraitCaption.top >= portraitArt.bottom - 1f)
    }

    @Test
    fun plainArtworkStageUsesOneNovaTitleAndTheIconWithoutLogoWordmark() {
        enterControllerInputMode()
        val title = "Control Ultimate Edition"
        val game = game("control", title, "steam").copy(
            artwork = PolarisGame.ArtworkManifest(
                revision = "identity-v1",
                assets = PolarisGame.ArtworkAssets(
                    logo = PolarisGame.ArtworkAsset(url = "/artwork/control/logo", cached = true),
                    icon = PolarisGame.ArtworkAsset(url = "/artwork/control/icon", cached = true),
                ),
            ),
        )
        val iconLoads = AtomicInteger(0)
        val logoLoads = AtomicInteger(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.fillMaxSize()) {
                    NovaLibraryStage(
                        games = listOf(game),
                        focusedGame = game,
                        restoreFocusGameId = game.id,
                        primaryActionLabel = "Launch",
                        apiClient = apiClient,
                        showPosterTitles = false,
                        onPrimaryAction = {},
                        onGameFocused = {},
                        onOpenDetail = {},
                        artworkLoader = { _, _, kind ->
                            when (kind) {
                                PolarisGame.ARTWORK_KIND_ICON -> iconLoads.incrementAndGet()
                                PolarisGame.ARTWORK_KIND_LOGO -> logoLoads.incrementAndGet()
                            }
                        },
                        posterLoader = { _, _ -> },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("nova-stage-icon").assertIsDisplayed()
        composeRule.onAllNodesWithTag("nova-stage-logo").assertCountEquals(0)
        composeRule.onNodeWithTag("nova-stage-title").assertIsDisplayed()
        composeRule.onAllNodesWithText(title, useUnmergedTree = true).assertCountEquals(1)
        composeRule.runOnIdle {
            assertEquals(1, iconLoads.get())
            assertEquals(0, logoLoads.get())
        }
    }

    @Test
    fun focusedPosterDeterministicallyDrivesDetailAndPrimaryActionsWithoutLaunchingAGame() {
        enterControllerInputMode()
        val games = games()
        val focusedGameId = AtomicReference<String?>(null)

        val detailGameId = AtomicReference<String?>(null)
        val primaryActions = AtomicInteger(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryStage(
                    games = games,
                    focusedGame = games[1],
                    restoreFocusGameId = games[1].id,
                    primaryActionLabel = "Launch",
                    apiClient = apiClient,
                    showPosterTitles = true,
                    onPrimaryAction = { primaryActions.incrementAndGet() },

                    onGameFocused = { focusedGameId.set(it.id) },
                    onOpenDetail = { detailGameId.set(it.id) },
                    artworkLoader = { _, _, _ -> },
                    posterLoader = { _, _ -> },
                )
            }
        }

        val bravo = composeRule.onNode(hasContentDescription("Bravo", substring = true))
        bravo.assertIsDisplayed().assertHasClickAction()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                bravo.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        bravo.performClick()

        composeRule.runOnIdle {
            assertEquals("bravo", focusedGameId.get())
            assertEquals("bravo", detailGameId.get())
            assertEquals(0, primaryActions.get())
        }

        composeRule.onNodeWithTag("nova-stage-primary-action").performClick()

        composeRule.runOnIdle {
            assertEquals("bravo", focusedGameId.get())
            assertEquals("bravo", detailGameId.get())

            assertEquals(1, primaryActions.get())
        }
    }

    @Test
    fun stageArtworkLoadersDoNotRepeatAfterUnrelatedRecomposition() {
        val games = games()
        val actionLabel = mutableStateOf("Launch")
        val posterLoads = AtomicInteger(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryStage(
                    games = games,
                    focusedGame = games[1],
                    restoreFocusGameId = games[1].id,
                    primaryActionLabel = actionLabel.value,
                    apiClient = apiClient,
                    showPosterTitles = true,
                    onPrimaryAction = {},
                    onGameFocused = {},
                    onOpenDetail = {},
                    artworkLoader = { _, _, _ -> },
                    posterLoader = { view, _ ->
                        view.tag = Any()
                        posterLoads.incrementAndGet()
                    },
                )
            }
        }

        composeRule.waitForIdle()
        val initialLoads = posterLoads.get()
        assertTrue(initialLoads > 0)
        composeRule.runOnIdle { actionLabel.value = "Play" }
        composeRule.waitForIdle()
        assertEquals(initialLoads, posterLoads.get())
    }

    @Test
    fun stageOmitsLogoAndIconLoadersWhenAssetsAreUnavailable() {
        val games = games()
        val markLoads = AtomicInteger(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryStage(
                    games = games,
                    focusedGame = games[1],
                    restoreFocusGameId = games[1].id,
                    primaryActionLabel = "Launch",
                    apiClient = apiClient,
                    showPosterTitles = true,
                    onPrimaryAction = {},
                    onGameFocused = {},
                    onOpenDetail = {},
                    artworkLoader = { _, _, _ -> markLoads.incrementAndGet() },
                    posterLoader = { _, _ -> },
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, markLoads.get())
    }

    @Test
    fun stageLoadsIconButDoesNotRequestLogoWhenBothUrlsExist() {
        val games = games().toMutableList()
        games[1] = games[1].copy(
            artwork = PolarisGame.ArtworkManifest(
                assets = PolarisGame.ArtworkAssets(
                    logo = PolarisGame.ArtworkAsset(url = "https://example.invalid/logo.png", cached = false),
                    icon = PolarisGame.ArtworkAsset(url = "https://example.invalid/icon.png", cached = false),
                ),
            ),
        )
        val logoLoads = AtomicInteger(0)
        val iconLoads = AtomicInteger(0)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryStage(
                    games = games,
                    focusedGame = games[1],
                    restoreFocusGameId = games[1].id,
                    primaryActionLabel = "Launch",
                    apiClient = apiClient,
                    showPosterTitles = true,
                    onPrimaryAction = {},
                    onGameFocused = {},
                    onOpenDetail = {},
                    artworkLoader = { _, _, kind ->
                        when (kind) {
                            PolarisGame.ARTWORK_KIND_LOGO -> logoLoads.incrementAndGet()
                            PolarisGame.ARTWORK_KIND_ICON -> iconLoads.incrementAndGet()
                        }
                    },
                    posterLoader = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(0, logoLoads.get())
            assertEquals(1, iconLoads.get())
        }
    }

    @Test
    fun rp6LargeTextCinematicHintsStayRightAlignedAndClearOfStageRail() {
        enterControllerInputMode()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val allHints = listOf(
            NovaControllerHint("A", "Select"),
            NovaControllerHint("B", "Back"),
            NovaControllerHint("X", "Library"),
            NovaControllerHint("Y", "Layout"),
            NovaControllerHint("Menu", "System"),
            NovaControllerHint("LB/RB", "Library / System"),
        )
        val visibleHints = allHints.filterIndexed { index, _ -> index in setOf(0, 1, 3) }
        val semanticsDescription = allHints.joinToString(separator = " · ") { hint ->
            "${hint.key} ${hint.label}"
        }
        val observedDensity = AtomicReference<Density>()

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryCinematicControllerHintsRp6Fixture(
                    games = games(),
                    apiClient = apiClient,
                    visibleHints = visibleHints,
                    semanticsDescription = semanticsDescription,
                    observedDensity = observedDensity,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nova-library-cinematic-controller-hints")
            .assertIsDisplayed()
            .assertContentDescriptionEquals(semanticsDescription)
        val rootBounds = bounds("nova-library-cinematic-controller-hints")
        val rowBounds = bounds("nova-library-cinematic-controller-hints-row")
        val railBounds = bounds("nova-stage-landscape-rail")
        val density = observedDensity.get()
        val trailingGapPx = rootBounds.right - rowBounds.right

        assertTrue(trailingGapPx >= -0.5f)
        assertTrue(trailingGapPx <= with(density) { 12.dp.toPx() } + 0.5f)
        assertTrue(rowBounds.width < rootBounds.width)
        assertTrue(rootBounds.height + 0.5f >= with(density) { 44.dp.toPx() })
        assertTrue(
            "stage rail overlaps cinematic hints: rail=$railBounds hints=$rootBounds density=${density.density}",
            railBounds.bottom <= rootBounds.top + 0.5f,
        )
        allHints.forEach { hint ->
            assertTrue(semanticsDescription.contains("${hint.key} ${hint.label}"))
        }
    }

    @Composable
    private fun NovaLibraryCinematicControllerHintsRp6Fixture(
        games: List<PolarisGame>,
        apiClient: PolarisApiClient,
        visibleHints: List<NovaControllerHint>,
        semanticsDescription: String,
        observedDensity: AtomicReference<Density>,
    ) {
        val density = LocalDensity.current
        val rp6LargeTextDensity = Density(density.density, fontScale = 2f)
        observedDensity.set(rp6LargeTextDensity)
        CompositionLocalProvider(LocalDensity provides rp6LargeTextDensity) {
            Box(
                modifier = Modifier
                    .requiredSize(833.dp, 390.dp)
                    .testTag("nova-library-cinematic-controller-hints-rp6-fixture"),
            ) {
                NovaLibraryLandscapeStageShell(
                    modifier = Modifier.fillMaxSize(),
                    reserveControllerHintSpace = true,
                ) {
                    NovaLibraryStage(
                        games = games,
                        focusedGame = games.first(),
                        restoreFocusGameId = null,
                        primaryActionLabel = "Play",
                        apiClient = apiClient,
                        showPosterTitles = true,
                        onPrimaryAction = {},
                        onGameFocused = {},
                        onOpenDetail = {},
                        artworkLoader = { _, _, _ -> },
                        posterLoader = { _, _ -> },
                    )
                }
                NovaLibraryCinematicControllerHints(
                    hints = visibleHints,
                    semanticsDescription = semanticsDescription,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }

    private fun enterControllerInputMode() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    private fun assertContained(container: Rect, child: Rect, label: String) {
        assertTrue("$label left ${child.left} < ${container.left}", child.left >= container.left)
        assertTrue("$label top ${child.top} < ${container.top}", child.top >= container.top)
        assertTrue("$label right ${child.right} > ${container.right}", child.right <= container.right)
        assertTrue("$label bottom ${child.bottom} > ${container.bottom}", child.bottom <= container.bottom)
    }

    private fun assertRectStable(before: Rect, after: Rect, label: String) {
        assertTrue("$label left reflowed: $before -> $after", kotlin.math.abs(before.left - after.left) <= 0.5f)
        assertTrue("$label top reflowed: $before -> $after", kotlin.math.abs(before.top - after.top) <= 0.5f)
        assertTrue("$label right reflowed: $before -> $after", kotlin.math.abs(before.right - after.right) <= 0.5f)
        assertTrue("$label bottom reflowed: $before -> $after", kotlin.math.abs(before.bottom - after.bottom) <= 0.5f)
    }

    @Test
    fun stageRailHoldsStillUntilFocusApproachesAnEdge() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val many = (0 until 14).map { index ->
            game("game-$index", "Game $index", "steam")
        }

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.requiredSize(width = 833.dp, height = 390.dp)) {
                    NovaLibraryStage(
                        games = many,
                        focusedGame = many.first(),
                        restoreFocusGameId = many.first().id,
                        primaryActionLabel = "Review & Launch",
                        apiClient = apiClient,
                        showPosterTitles = false,
                        onPrimaryAction = {},
                        onGameFocused = {},
                        onOpenDetail = {},
                        artworkLoader = { _, _, _ -> },
                        posterLoader = { _, _ -> },
                    )
                }
            }
        }

        fun railOriginOf(id: String): Float =
            composeRule.onNodeWithTag("nova-stage-poster-$id").fetchSemanticsNode().boundsInRoot.left

        // Anchor on a poster that stays composed throughout so its screen position is a
        // direct readout of how far the rail has scrolled.
        composeRule.onNodeWithTag("nova-poster-${many[2].id}")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val anchorAfterNearFocus = railOriginOf(many[2].id)

        // Moving the selection one step, well inside the rail, must not drag the library
        // sideways: the focus travels across stationary posters.
        composeRule.onNodeWithTag("nova-poster-${many[3].id}")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val anchorAfterInteriorStep = railOriginOf(many[2].id)
        assertEquals(
            "interior focus step must not scroll the rail",
            anchorAfterNearFocus,
            anchorAfterInteriorStep,
            0.5f,
        )

        // Stepping on until the selection reaches the trailing edge must scroll, otherwise
        // the tail of the library would be unreachable. Lazy items only exist once visible,
        // so walk the selection the way a D-pad would rather than jumping to the end.
        for (index in 4..7) {
            composeRule.onNodeWithTag("nova-poster-${many[index].id}")
                .performSemanticsAction(SemanticsActions.RequestFocus)
            composeRule.waitForIdle()
        }
        val anchorAfterEdgeFocus = railOriginOf(many[2].id)
        assertTrue(
            "focus reaching the trailing edge must scroll the rail: " +
                "$anchorAfterInteriorStep -> $anchorAfterEdgeFocus",
            anchorAfterInteriorStep - anchorAfterEdgeFocus > 1f,
        )
    }

    private fun games(): List<PolarisGame> = listOf(
        game("alpha", "Alpha", "steam"),
        game("bravo", "Bravo", "epic"),
        game("charlie", "Charlie", "gog"),
        game("delta", "Delta", "steam"),
    )

    private fun game(id: String, name: String, source: String): PolarisGame = PolarisGame(
        id = id,
        name = name,
        source = source,
        launcherSource = source,
        category = "fast_action",
        genres = listOf("Action"),
    )
}
