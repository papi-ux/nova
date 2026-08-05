package com.papi.nova.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaLibraryPosterCardComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stageGridAndCompactRenderExactPosterRatioWithOptionalCaptionBelowArtwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val posterLoads = AtomicInteger(0)
        val fixtures = listOf(
            PosterFixture("stage", "Stage Hidden", NovaLibraryLayoutMode.STAGE, false),
            PosterFixture("grid", "Grid Caption", NovaLibraryLayoutMode.GRID, true),
            PosterFixture("compact", "Compact Caption", NovaLibraryLayoutMode.COMPACT, true),
        )

        composeRule.setContent {
            NovaComposeTheme {
                Row {
                    fixtures.forEach { fixture ->
                        NovaLibraryPosterCard(
                            game = game(fixture.id, fixture.title),
                            layoutMode = fixture.layoutMode,
                            apiClient = apiClient,
                            showPosterTitle = fixture.showTitle,
                            onOpenDetail = {},
                            modifier = Modifier.width(120.dp),
                            posterLoader = { view, _ ->
                                view.setImageDrawable(ColorDrawable(Color.MAGENTA))
                                posterLoads.incrementAndGet()
                            },
                        )
                    }
                }
            }
        }

        fixtures.forEach { fixture ->
            val artwork = composeRule
                .onNodeWithTag("nova-poster-art-${fixture.id}", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val measuredRatio = artwork.width / artwork.height
            assertTrue("${fixture.layoutMode} ratio=$measuredRatio bounds=$artwork", abs(measuredRatio - (2f / 3f)) < 0.01f)
            assertEquals(NovaLibraryUiStateMapper.posterAspectRatio(), measuredRatio, 0.01f)

            if (fixture.showTitle) {
                val caption = composeRule
                    .onNodeWithTag("nova-poster-caption-${fixture.id}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "${fixture.layoutMode} caption should be below artwork: artwork=$artwork caption=$caption",
                    caption.top >= artwork.bottom - 1f,
                )
                composeRule.onAllNodesWithText(fixture.title, useUnmergedTree = true).assertCountEquals(1)
            } else {
                composeRule.onAllNodesWithText(fixture.title, useUnmergedTree = true).assertCountEquals(0)
            }
        }
        composeRule.runOnIdle { assertEquals(3, posterLoads.get()) }
    }

    @Test
    fun semanticOwnerUsesLocalizedMetadataAndActivationOpensDetailOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val detailActions = AtomicInteger(0)
        val game = game("detail", "Detail Poster").copy(
            hdrSupported = true,
            lastLaunched = 1L,
        )

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryPosterCard(
                    game = game,
                    layoutMode = NovaLibraryLayoutMode.STAGE,
                    apiClient = apiClient,
                    showPosterTitle = false,
                    onOpenDetail = { detailActions.incrementAndGet() },
                    modifier = Modifier.width(120.dp),
                    posterLoader = { view, _ ->
                        view.setImageDrawable(ColorDrawable(Color.CYAN))
                    },
                )
            }
        }

        val metadata = listOf(game.sourceLabel, game.categoryLabel)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" · ")
        val semanticDescription = listOf(
            game.name,
            metadata,
            context.getString(R.string.badge_hdr),
            context.getString(R.string.nova_library_filter_recent),
            context.getString(R.string.nova_library_card_action_details),
        ).filter(String::isNotBlank).joinToString(". ")
        composeRule.onNode(hasContentDescription(semanticDescription))
            .assertIsDisplayed()
            .assertHasClickAction()
        listOf("Selected", "Details", "HDR", "Recent").forEach { forbiddenBadge ->
            composeRule.onAllNodesWithText(forbiddenBadge, useUnmergedTree = true).assertCountEquals(0)
        }

        composeRule.onNodeWithTag("nova-poster-detail").performClick()
        composeRule.runOnIdle { assertEquals(1, detailActions.get()) }
    }

    @Test
    fun focusRequesterOwnsSingleDpadTargetAndAnimatesArtworkInsideStableCellFor180Millis() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val focusRequester = FocusRequester()
        val focusAcquisitions = AtomicInteger(0)
        val focusedCallbacks = AtomicInteger(0)
        val navigation = mutableListOf<Int>()
        val game = game("focus", "Focus Poster")
        val presentationSpec = NovaLibraryUiStateMapper.posterPresentationSpec(NovaLibraryLayoutMode.GRID)
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryPosterCard(
                    game = game,
                    layoutMode = NovaLibraryLayoutMode.GRID,
                    apiClient = apiClient,
                    showPosterTitle = false,
                    onOpenDetail = {},
                    modifier = Modifier.width(120.dp),
                    focusRequester = focusRequester,
                    onFocusChanged = { isFocused ->
                        if (isFocused) focusAcquisitions.incrementAndGet()
                    },
                    onFocused = { focusedCallbacks.incrementAndGet() },
                    onNavigate = { direction ->
                        navigation += direction
                        true
                    },
                    posterLoader = { view, _ ->
                        view.setImageDrawable(ColorDrawable(Color.GREEN))
                    },
                )
            }
        }

        val cardNode = composeRule.onNodeWithTag("nova-poster-focus")
        val artworkNode = composeRule.onNodeWithTag("nova-poster-art-focus", useUnmergedTree = true)
        val stableCellBounds = cardNode.fetchSemanticsNode().boundsInRoot
        val restingArtworkBounds = artworkNode.fetchSemanticsNode().boundsInRoot

        cardNode.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitUntil(5_000) {
            runCatching { cardNode.assertIsFocused() }.isSuccess
        }
        cardNode.performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.runOnIdle {
            assertEquals(1, focusAcquisitions.get())
            assertEquals(1, focusedCallbacks.get())
            assertEquals(listOf(1), navigation)
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(90L)
        val midpointArtworkBounds = artworkNode.fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.advanceTimeBy(90L)
        val focusedArtworkBounds = artworkNode.fetchSemanticsNode().boundsInRoot
        val focusedCellBounds = cardNode.fetchSemanticsNode().boundsInRoot

        assertEquals("focus animation must not resize the lazy/grid cell", stableCellBounds, focusedCellBounds)
        assertTrue(midpointArtworkBounds.width > restingArtworkBounds.width)
        assertTrue(focusedArtworkBounds.width > midpointArtworkBounds.width)
        assertEquals(
            restingArtworkBounds.width * presentationSpec.focusedScale,
            focusedArtworkBounds.width,
            1.5f,
        )
        val expectedFocusedTop = restingArtworkBounds.top -
            ((restingArtworkBounds.height * presentationSpec.focusedScale - restingArtworkBounds.height) / 2f) -
            (NovaPosterFocusedLift.value * context.resources.displayMetrics.density)
        assertEquals(expectedFocusedTop, focusedArtworkBounds.top, 2f)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun posterRevisionChangeReloadsArtworkForTheSameGame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val posterLoads = AtomicInteger(0)
        val currentGame = mutableStateOf(gameWithPosterRevision("revision", "Revision Poster", "revision-1"))
        val loader: (ImageView, PolarisGame) -> Unit = { view, _ ->
            view.setImageDrawable(ColorDrawable(Color.BLUE))
            posterLoads.incrementAndGet()
        }

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryPosterCard(
                    game = currentGame.value,
                    layoutMode = NovaLibraryLayoutMode.GRID,
                    apiClient = apiClient,
                    showPosterTitle = false,
                    onOpenDetail = {},
                    modifier = Modifier.width(120.dp),
                    posterLoader = loader,
                )
            }
        }

        composeRule.runOnIdle { assertEquals(1, posterLoads.get()) }
        composeRule.runOnIdle {
            currentGame.value = gameWithPosterRevision("revision", "Revision Poster", "revision-2")
        }
        composeRule.runOnIdle { assertEquals(2, posterLoads.get()) }
    }

    @Test
    fun replacingStableInjectedLoaderReloadsWithoutUnrelatedRecompositionChurn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)
        val firstLoads = AtomicInteger(0)
        val replacementLoads = AtomicInteger(0)
        val showTitle = mutableStateOf(false)
        val firstLoader: (ImageView, PolarisGame) -> Unit = { view, _ ->
            view.setImageDrawable(ColorDrawable(Color.RED))
            firstLoads.incrementAndGet()
        }
        val replacementLoader: (ImageView, PolarisGame) -> Unit = { view, _ ->
            view.setImageDrawable(ColorDrawable(Color.YELLOW))
            replacementLoads.incrementAndGet()
        }
        val currentLoader = mutableStateOf(firstLoader)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibraryPosterCard(
                    game = game("loader", "Loader Poster"),
                    layoutMode = NovaLibraryLayoutMode.COMPACT,
                    apiClient = apiClient,
                    showPosterTitle = showTitle.value,
                    onOpenDetail = {},
                    modifier = Modifier.width(120.dp),
                    posterLoader = currentLoader.value,
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(1, firstLoads.get())
            assertEquals(0, replacementLoads.get())
            showTitle.value = true
        }
        composeRule.runOnIdle {
            assertEquals("stable loader must not restart for unrelated recomposition", 1, firstLoads.get())
            currentLoader.value = replacementLoader
        }
        composeRule.runOnIdle {
            assertEquals(1, firstLoads.get())
            assertEquals(1, replacementLoads.get())
        }
    }

    private data class PosterFixture(
        val id: String,
        val title: String,
        val layoutMode: NovaLibraryLayoutMode,
        val showTitle: Boolean,
    )

    private fun game(id: String, title: String): PolarisGame = PolarisGame(
        id = id,
        name = title,
        source = "steam",
        launcherSource = "steam",
        category = "fast_action",
        genres = listOf("Action"),
    )

    private fun gameWithPosterRevision(id: String, title: String, revision: String): PolarisGame =
        game(id, title).copy(
            artwork = PolarisGame.ArtworkManifest(
                revision = revision,
                assets = PolarisGame.ArtworkAssets(
                    poster = PolarisGame.ArtworkAsset(
                        url = "/polaris/v1/games/$id/artwork/poster",
                        cached = true,
                    ),
                ),
            ),
        )
}
