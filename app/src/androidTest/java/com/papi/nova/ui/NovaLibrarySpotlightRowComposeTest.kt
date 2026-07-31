package com.papi.nova.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NovaLibrarySpotlightRowComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun spotlightRestoresFocusNavigatesAndActivatesTheCenteredGame() {
        val games = listOf(
            game("alpha", "Alpha", "steam"),
            game("bravo", "Bravo", "epic"),
            game("charlie", "Charlie", "gog")
        )
        val openedGameId = AtomicReference<String?>(null)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.fillMaxSize()) {
                    NovaLibrarySpotlightRow(
                        games = games,
                        apiClient = apiClient,
                        isLandscape = true,
                        restoreFocusGameId = "bravo",
                        showPosterTitles = true,
                        onGameFocused = {},
                        onOpenDetail = { openedGameId.set(it.id) }
                    )
                }
            }
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.waitForIdleSync()
        val bravo = composeRule.onNode(hasContentDescription("Bravo", substring = true))
        bravo.assertIsDisplayed().assertHasClickAction()
        waitForFocus(bravo)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.waitForIdleSync()
        val charlie = composeRule.onNode(hasContentDescription("Charlie", substring = true))
        charlie.assertIsDisplayed().assertHasClickAction()
        waitForFocus(charlie)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()
        assertEquals("charlie", openedGameId.get())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        instrumentation.waitForIdleSync()
        waitForFocus(bravo)
    }

    @Test
    fun changingTheRestoreTargetMovesFocusWithoutChangingTheGameList() {
        val games = listOf(
            game("alpha", "Alpha", "steam"),
            game("bravo", "Bravo", "epic"),
            game("charlie", "Charlie", "gog")
        )
        var restoreFocusGameId by mutableStateOf("alpha")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibrarySpotlightRow(
                    games = games,
                    apiClient = apiClient,
                    isLandscape = true,
                    restoreFocusGameId = restoreFocusGameId,
                    showPosterTitles = true,
                    onGameFocused = {},
                    onOpenDetail = {}
                )
            }
        }

        enterSpotlightRow()
        val alpha = composeRule.onNode(hasContentDescription("Alpha", substring = true))
        waitForFocus(alpha)

        composeRule.runOnIdle { restoreFocusGameId = "charlie" }

        waitForFocus(composeRule.onNode(hasContentDescription("Charlie", substring = true)))
    }

    @Test
    fun touchSwipeReportsTheCardNearestTheViewportCenter() {
        val games = listOf(
            game("alpha", "Alpha", "steam"),
            game("bravo", "Bravo", "epic"),
            game("charlie", "Charlie", "gog"),
            game("delta", "Delta", "steam")
        )
        val focusedGameId = AtomicReference<String?>(null)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                Box(Modifier.fillMaxSize()) {
                    NovaLibrarySpotlightRow(
                        games = games,
                        apiClient = apiClient,
                        isLandscape = true,
                        restoreFocusGameId = "bravo",
                        showPosterTitles = true,
                        onGameFocused = { focusedGameId.set(it.id) },
                        onOpenDetail = {}
                    )
                }
            }
        }

        val bravo = composeRule.onNode(hasContentDescription("Bravo", substring = true))
        bravo.assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.runOnIdle { focusedGameId.set(null) }

        bravo.performTouchInput { swipeLeft(durationMillis = 800) }

        composeRule.waitUntil(timeoutMillis = 5_000) { focusedGameId.get() != null }
        val viewportCenterX = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        val centeredGameId = games.mapNotNull { game ->
            runCatching {
                composeRule.onNode(
                    hasContentDescription(game.name, substring = true)
                ).fetchSemanticsNode().boundsInRoot
            }.getOrNull()?.let { bounds ->
                game.id to abs(bounds.center.x - viewportCenterX)
            }
        }.minByOrNull { (_, distance) -> distance }?.first

        assertEquals(centeredGameId, focusedGameId.get())
        assertEquals("charlie", centeredGameId)
    }

    @Test
    fun accessibilityActivationSelectsTheGameBeforeOpeningDetails() {
        val games = listOf(
            game("alpha", "Alpha", "steam"),
            game("bravo", "Bravo", "epic")
        )
        val focusedGameId = AtomicReference<String?>(null)
        val openedGameId = AtomicReference<String?>(null)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibrarySpotlightRow(
                    games = games,
                    apiClient = apiClient,
                    isLandscape = true,
                    restoreFocusGameId = "alpha",
                    showPosterTitles = true,
                    onGameFocused = { focusedGameId.set(it.id) },
                    onOpenDetail = { openedGameId.set(it.id) }
                )
            }
        }

        composeRule.onNode(hasContentDescription("Bravo", substring = true)).performClick()

        composeRule.runOnIdle {
            assertEquals("bravo", focusedGameId.get())
            assertEquals("bravo", openedGameId.get())
        }
    }

    @Test
    fun twoXFontScaleKeepsTheFocusedTitleScaledAndUnclipped() {
        val title = "Control Ultimate Edition"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity.density, fontScale = 2f)
            ) {
                NovaComposeTheme {
                    NovaLibrarySpotlightRow(
                        games = listOf(
                            game("control", title, "epic").copy(
                                hdrSupported = true,
                                lastLaunched = 1L
                            )
                        ),
                        apiClient = apiClient,
                        isLandscape = true,
                        restoreFocusGameId = "control",
                        showPosterTitles = true,
                        onGameFocused = {},
                        onOpenDetail = {},
                        coverLoader = { _, _ -> }
                    )
                }
            }
        }

        enterSpotlightRow()
        waitForFocus(composeRule.onNode(hasContentDescription(title, substring = true)))
        val textNode = composeRule.onNodeWithText("Control Ultimate", substring = true, useUnmergedTree = true)
        val layoutResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layoutResults)
        }

        assertEquals(21f, layoutResults.single().layoutInput.style.fontSize.value, 0.01f)
        assertFalse(layoutResults.single().hasVisualOverflow)

        val metadataResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val metadata = composeRule.onNodeWithText("Epic · Fast action", useUnmergedTree = true)
        metadata.assertIsDisplayed()
        metadata.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(metadataResults) }
        val metadataResult = metadataResults.single()
        assertFalse(
            "metadata overflow lines=${metadataResult.lineCount} size=${metadataResult.size} width=${metadataResult.multiParagraph.width} height=${metadataResult.multiParagraph.height} ellipsized=${(0 until metadataResult.lineCount).any(metadataResult::isLineEllipsized)}",
            metadataResult.hasVisualOverflow
        )
        val hdr = composeRule.onNodeWithText("HDR", useUnmergedTree = true)
        val recent = composeRule.onNodeWithText("Recent", useUnmergedTree = true)
        hdr.assertIsDisplayed()
        recent.assertIsDisplayed()
        val titleTop = textNode.fetchSemanticsNode().boundsInRoot.top
        val hdrBottom = hdr.fetchSemanticsNode().boundsInRoot.bottom
        val recentBottom = recent.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(hdrBottom <= titleTop)
        assertTrue(recentBottom <= titleTop)
    }

    @Test
    fun missingArtworkStillExposesReadableFallbackAndLaunchPath() {
        val openedGameId = AtomicReference<String?>(null)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val apiClient = PolarisApiClient(context, "127.0.0.1", 47984)

        composeRule.setContent {
            NovaComposeTheme {
                NovaLibrarySpotlightRow(
                    games = listOf(game("offline", "", "offline")),
                    apiClient = apiClient,
                    isLandscape = true,
                    restoreFocusGameId = "offline",
                    showPosterTitles = true,
                    onGameFocused = {},
                    onOpenDetail = { openedGameId.set(it.id) },
                    coverLoader = { _, _ -> }
                )
            }
        }

        val fallback = composeRule.onNode(hasContentDescription("Unknown game", substring = true))
        fallback.assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals("offline", openedGameId.get()) }
    }

    private fun enterSpotlightRow() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForFocus(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        composeRule.waitUntil(timeoutMillis = 2_000) {
            runCatching {
                node.assertIsFocused()
                true
            }.getOrDefault(false)
        }
        node.assertIsFocused()
    }

    private fun game(id: String, name: String, source: String): PolarisGame = PolarisGame(
        id = id,
        name = name,
        source = source,
        launcherSource = source,
        category = "fast_action",
        genres = listOf("Action")
    )
}
