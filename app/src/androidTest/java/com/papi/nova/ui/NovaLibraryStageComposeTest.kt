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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.input.key.Key
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

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaLibraryStageComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

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


    private fun stageFixture(
        many: List<PolarisGame> = games(),
        restore: String? = many.firstOrNull()?.id,
        fontScale: Float = 1f,
        onDetail: (PolarisGame) -> Unit = {},
        artworkLoader: (android.widget.ImageView, PolarisGame, String) -> Unit = { _, _, _ -> },
        showPosterTitles: Boolean = false,
    ) {
        enterControllerInputMode()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent {
            NovaComposeTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.requiredSize(833.dp, 354.dp)) {
                        NovaLibraryStage(many, many.firstOrNull { it.id == restore }, restore,
                            apiClient = PolarisApiClient(context, ""), showPosterTitles = showPosterTitles,
                            onGameFocused = {}, onOpenDetail = onDetail,
                            artworkLoader = artworkLoader, posterLoader = { view, game ->
                                view.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(45 + game.id.length * 4, 70, 90)))
                            })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun selectedCoverUsesApprovedHeightGeometryAndAOpensOnlyItsDetail() {
        val opened = AtomicReference<String>()
        stageFixture(onDetail = { opened.set(it.id) })
        val cover = composeRule.onNodeWithTag("nova-poster-art-alpha", true).getUnclippedBoundsInRoot()
        val next = composeRule.onNodeWithTag("nova-poster-art-bravo", true).getUnclippedBoundsInRoot()
        val after = composeRule.onNodeWithTag("nova-poster-art-charlie", true).getUnclippedBoundsInRoot()
        val stage = composeRule.onNodeWithTag("nova-library-stage").getUnclippedBoundsInRoot()
        assertEquals(10f, (cover.left - stage.left).value, .6f)
        assertEquals(224f, (cover.right - cover.left).value, 0.6f)
        assertEquals(336f, (cover.bottom - cover.top).value, 0.6f)
        assertEquals(120f, (next.right - next.left).value, 0.6f)
        assertEquals(180f, (next.bottom - next.top).value, 0.6f)
        assertEquals(12f, (after.left - next.right).value, 0.6f)
        composeRule.onNodeWithTag("nova-stage-selected-focus").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals("alpha", opened.get())
        composeRule.onAllNodesWithText("Review & Launch").assertCountEquals(0)
        capture("selected-geometry")
    }

    @Test fun wrappingThroughFiveHundredGamesKeepsOneFocusAndAReachableTail() {
        val many = (0 until 500).map { game("g-$it", "Game $it", "steam") }
        stageFixture(many, "g-499")
        val fixed = composeRule.onNodeWithTag("nova-poster-art-g-499", true).getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(fixed, composeRule.onNodeWithTag("nova-poster-art-g-0", true).getUnclippedBoundsInRoot())
        composeRule.onNodeWithTag("nova-stage-position", true).assertTextEquals("1 of 500 · Library Order")
        composeRule.onNodeWithTag("nova-stage-selected-focus").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nova-stage-position", true).assertTextEquals("500 of 500 · Library Order")
        composeRule.onNodeWithTag("nova-stage-selected-focus").assertIsFocused()
        composeRule.onNodeWithTag("nova-poster-g-250").assertDoesNotExist()
        capture("wrapping-tail")
    }

    @Test fun aRowTapSelectsBeforeASecondTapOpensDetails() {
        val opened = AtomicInteger()
        stageFixture(onDetail = { opened.incrementAndGet() })
        composeRule.onNodeWithTag("nova-poster-bravo").performClick()
        composeRule.waitForIdle()
        assertEquals(0, opened.get())
        val b = composeRule.onNodeWithTag("nova-poster-art-bravo", true).getUnclippedBoundsInRoot()
        assertEquals(224f, (b.right - b.left).value, .6f)
        composeRule.onNodeWithTag("nova-poster-bravo").performClick()
        assertEquals(1, opened.get())
    }

    @Test fun aCachedLogoReplacesTheTitleWithoutLoadingIconArt() {
        val artwork = PolarisGame.ArtworkManifest(assets = PolarisGame.ArtworkAssets(
            logo = PolarisGame.ArtworkAsset(url = "/logo", cached = true),
            icon = PolarisGame.ArtworkAsset(url = "/icon", cached = true)))
        val many = games().toMutableList().apply { this[0] = this[0].copy(artwork = artwork) }
        val logoLoads = AtomicInteger()
        stageFixture(many, artworkLoader = { _, _, kind ->
            assertEquals(PolarisGame.ARTWORK_KIND_LOGO, kind)
            logoLoads.incrementAndGet()
        })
        composeRule.onNodeWithTag("nova-stage-logo", true).assertIsDisplayed()
        composeRule.onNodeWithTag("nova-stage-title", true).assertDoesNotExist()
        assertEquals(1, logoLoads.get())
    }

    @Test fun largeTextIdentityRailAndCounterStayWithinTheStageContent() {
        stageFixture(fontScale = 2f)
        val stage = composeRule.onNodeWithTag("nova-library-stage").getUnclippedBoundsInRoot()
        val poster = composeRule.onNodeWithTag("nova-poster-art-alpha", true).getUnclippedBoundsInRoot()
        val identity = composeRule.onNodeWithTag("nova-stage-identity", true).getUnclippedBoundsInRoot()
        val rail = composeRule.onNodeWithTag("nova-stage-landscape-rail").getUnclippedBoundsInRoot()
        val counter = composeRule.onNodeWithTag("nova-stage-position", true).getUnclippedBoundsInRoot()
        assertTrue(poster.bottom <= counter.top + .6.dp)
        assertTrue(identity.bottom <= rail.top + .6.dp)
        assertTrue(rail.bottom <= counter.top + .6.dp)
        assertTrue(counter.bottom <= stage.bottom + .6.dp)
        composeRule.onNodeWithTag("nova-stage-selected-focus").assertIsFocused()
        capture("large-text")
    }

    @Test fun longTitleAndMetadataStayWithinTheirBudgetAtModeratelyLargeText() = assertModeratelyLargeIdentity(false)

    @Test fun posterCaptionsPreserveTheModeratelyLargeIdentityBudget() = assertModeratelyLargeIdentity(true)

    private fun assertModeratelyLargeIdentity(showPosterTitles: Boolean) {
        val many = games().toMutableList().apply {
            this[0] = this[0].copy(name = "A game with a longer title\nAnd a visible second line",
                category = "action", playTime = PolarisGame.PlayTime(seconds = 84 * 3600),
                lastLaunched = System.currentTimeMillis() / 1000 - 3600)
            this[1] = this[1].copy(name = "A long neighbour title\nWith a second visible line")
        }
        stageFixture(many, fontScale = 1.3f, showPosterTitles = showPosterTitles)
        // Capture even when a measured-text assertion fails; every native red needs a frame.
        capture("long-title-text-1_3-${if (showPosterTitles) "captions" else "plain"}")
        val title = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("nova-stage-title", true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(title) }
        if (!showPosterTitles) assertEquals("fixture exercises two title lines where they fit", 2, title.single().lineCount)
        assertTrue("a short caption-on pane keeps a readable bounded title", title.single().lineCount in 1..2)
        composeRule.onNodeWithTag("nova-stage-title", true).assertTextEquals(many[0].name)
        val metadata = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("nova-stage-metadata", true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(metadata) }
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertTrue("metadata fits its 14sp single-line budget",
            metadata.single().size.height / density <= kotlin.math.ceil(14f * 1.3f) + 1f)
        val identity = composeRule.onNodeWithTag("nova-stage-identity", true).getUnclippedBoundsInRoot()
        listOf("nova-stage-title", "nova-stage-metadata", "nova-stage-play-stats").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag, true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val laid = layouts.single()
            // Bounded titles may deliberately ellipsize extra lines; didOverflowHeight also
            // reports that. Every line we actually draw must still fit its measured height.
            assertTrue("$tag shows every drawn line", laid.size.height + 1f >= laid.getLineBottom(laid.lineCount - 1))
            val b = node.getUnclippedBoundsInRoot()
            assertTrue("$tag stays within its identity block", b.top >= identity.top - .6.dp && b.bottom <= identity.bottom + .6.dp)
            if (tag == "nova-stage-play-stats") {
                assertTrue("the complete stats are not vertically clipped", !laid.didOverflowHeight)
                node.assertTextEquals(layouts.single().layoutInput.text.text)
                assertTrue("populated playtime and last-played remain present", layouts.single().layoutInput.text.text.contains("84 h played") && layouts.single().layoutInput.text.text.contains("Last played"))
                assertTrue("full stats are not ellipsized", (0 until layouts.single().lineCount).none { layouts.single().isLineEllipsized(it) })
                assertTrue("full stats stay within the allowed lines", !layouts.single().multiParagraph.didExceedMaxLines)
            }
        }
        if (showPosterTitles) {
            val caption = composeRule.onNodeWithTag("nova-poster-caption-bravo", true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val laid = layouts.single()
            val art = composeRule.onNodeWithTag("nova-poster-art-bravo", true).getUnclippedBoundsInRoot()
            val bounds = caption.getUnclippedBoundsInRoot()
            val card = composeRule.onNodeWithTag("nova-poster-bravo").getUnclippedBoundsInRoot()
            val diagnostics = "text=${laid.layoutInput.text.text}, constraints=${laid.layoutInput.constraints}, " +
                "size=${laid.size}, paragraphHeight=${laid.multiParagraph.height}, " +
                "lineHeight=${laid.layoutInput.style.lineHeight}, density=${laid.layoutInput.density.density}, " +
                "fontScale=${laid.layoutInput.density.fontScale}, maxLines=${laid.layoutInput.maxLines}, " +
                "didExceedMaxLines=${laid.multiParagraph.didExceedMaxLines}, " +
                "lastLineBottom=${laid.getLineBottom(laid.lineCount - 1)}, " +
                "cardDp=$card, artDp=$art, captionDp=$bounds, " +
                "cardPx=${composeRule.onNodeWithTag("nova-poster-bravo").fetchSemanticsNode().size}, " +
                "artPx=${composeRule.onNodeWithTag("nova-poster-art-bravo", true).fetchSemanticsNode().size}, " +
                "captionPx=${caption.fetchSemanticsNode().size}"
            assertEquals("long neighbour exercises both caption lines: $diagnostics", 2, laid.lineCount)
            assertTrue("both visible caption lines fit vertically", laid.size.height + 1f >= laid.getLineBottom(laid.lineCount - 1))
            assertTrue("two caption lines fit their 34sp budget",
                layouts.single().size.height / density <= kotlin.math.ceil(34f * 1.3f) + 1f)
            assertTrue("caption remains inside its neighbour card", bounds.bottom <= card.bottom + .6.dp)
            assertTrue("ellipsized captions retain the complete semantic game name",
                composeRule.onNodeWithTag("nova-poster-bravo").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString().contains(many[1].name))
        }
    }

    @Test fun changingTheSelectedGameWhileAHeldCancelsThatPress() {
        val opened = mutableListOf<String>()
        stageFixture(onDetail = { opened += it.id })
        composeRule.onNodeWithTag("nova-stage-selected-focus").performKeyInput {
            keyDown(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { keyUp(Key.Enter) }
        assertEquals(emptyList<String>(), opened)
        composeRule.onNodeWithTag("nova-stage-selected-focus").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("bravo"), opened)
        capture("press-change-release")
    }

    @Test fun anUncachedLogoKeepsTheTitleWithoutRequestingItOnFocus() {
        val artwork = PolarisGame.ArtworkManifest(assets = PolarisGame.ArtworkAssets(
            logo = PolarisGame.ArtworkAsset(url = "/logo", cached = false)))
        val many = games().toMutableList().apply { this[0] = this[0].copy(artwork = artwork) }
        val loads = AtomicInteger()
        stageFixture(many, artworkLoader = { _, _, _ -> loads.incrementAndGet() })
        composeRule.onNodeWithTag("nova-stage-title", true).assertIsDisplayed().assertTextEquals("Alpha")
        composeRule.onNodeWithTag("nova-stage-logo", true).assertDoesNotExist()
        assertEquals(0, loads.get())
    }

    /** Synthetic cover fixtures prove layout/focus; they do not prove a live library or stream. */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val suffix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshotSuffix") ?: "stage"
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "stage-131").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(directory, "$name-$suffix.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun enterControllerInputMode() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }
    private fun assertContained(container: Rect, child: Rect, label: String) {
        assertTrue("$label left", child.left >= container.left - .6f)
        assertTrue("$label top", child.top >= container.top - .6f)
        assertTrue("$label right", child.right <= container.right + .6f)
        assertTrue("$label bottom", child.bottom <= container.bottom + .6f)
    }
    private fun games() = listOf(game("alpha", "Alpha", "steam"), game("bravo", "Bravo", "epic"),
        game("charlie", "Charlie", "gog"), game("delta", "Delta", "steam"))
    private fun game(id: String, name: String, source: String) = PolarisGame(id = id, name = name,
        source = source, launcherSource = source, category = "fast_action", genres = listOf("Action"))
}
