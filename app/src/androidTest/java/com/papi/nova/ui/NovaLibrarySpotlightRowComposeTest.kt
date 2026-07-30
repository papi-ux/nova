package com.papi.nova.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NovaComposeTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
