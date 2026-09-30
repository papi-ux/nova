package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The new Stage keeps focus on the selected cover and marks the host's running game. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp-land")
class NovaLibraryStageHeroLookComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun runningBadgeTracksTheSessionRatherThanTheSelectedGame() {
        val games = listOf(PolarisGame(id = "control", name = "Control"), PolarisGame(id = "portal", name = "Portal"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        rule.setPanelContent {
            Box(Modifier.requiredSize(833.dp, 354.dp)) {
                NovaLibraryStage(games, games.first(), null,
                    apiClient = PolarisApiClient(context, ""), showPosterTitles = false,
                    onGameFocused = {}, onOpenDetail = {}, runningGameId = "portal",
                    artworkLoader = { _, _, _ -> }, posterLoader = { _, _ -> })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("nova-stage-selected-focus").assertIsFocused()
        rule.onNodeWithTag("nova-poster-running-portal", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("nova-poster-running-control", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("nova-stage-primary-action").assertDoesNotExist()
    }
}
