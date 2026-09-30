package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaHostCopyRecoveryComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun staleHostCopyHasAdjacentUseDeviceSettingOnTheActualPlaySetupRoot() {
        val panel = NovaPanelState().also { it.open(PlaySetupPage.Root("Play Setup")) }
        val summary = NovaLaunchProfileSummary("Launch", "", "1280×720 · 60 FPS", "Host's saved copy · 1280×720",
            "", "", "", NovaLaunchProfileNoticeTone.HEALTHY, "", "", emptyList(), false, "")
        rule.setPanelContent {
            NovaGameDetailContentUnderTest(
                uiState = NovaGameDetailUiState.from(PolarisGame(id="game",name="Control"),false,PolarisClientSettings(),"auto"),
                optimizationState = NovaGameDetailOptimizationState(profileSummary = summary),
                playSetupPanel = panel, destination = NovaGameDetailDestination.PLAY_SETUP,
            )
        }
        rule.frames(8)
        rule.onNodeWithText("Use device setting").assertExists()
    }
}
