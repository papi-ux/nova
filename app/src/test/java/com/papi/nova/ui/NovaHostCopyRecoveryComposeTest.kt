package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import com.papi.nova.manager.NovaStreamSourceLine
import com.papi.nova.manager.NovaStreamSource
import com.papi.nova.ui.panel.NovaTestKeys
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
        var writes = 0
        val recovery = novaHostCopyRecovery(NovaStreamSourceLine(NovaStreamSource.HOST_SAVED_COPY,summary.reasonLine),
            PolarisClientSettings(capabilities=PolarisClientSettings.Capabilities(displayModeOverride=true,targetBitrateOverride=true)),
            space=false,watch=false,metered=false,checking=false,busy=false,isCurrent={true},onUseDeviceSetting={writes++})
        val keys = rule.setPanelContent {
            NovaGameDetailContentUnderTest(
                uiState = NovaGameDetailUiState.from(PolarisGame(id="game",name="Control"),false,PolarisClientSettings(),"auto"),
                optimizationState = NovaGameDetailOptimizationState(profileSummary = summary),
                playSetupPanel = panel, destination = NovaGameDetailDestination.PLAY_SETUP, hostCopyRecovery = recovery,
            )
        }
        rule.frames(8)
        rule.onNodeWithText("Use device setting").assertExists()
        rule.onNodeWithText(summary.reasonLine).assertExists()
        rule.onNodeWithTag("nova-use-device-setting").requestFocus().assertIsFocused()
        keys.press(NovaTestKeys.CENTER); rule.frames(2)
        assertEquals("D-pad activates the real adjacent recovery callback",1,writes)
        rule.onNodeWithTag("nova-use-device-setting").performClick()
        assertEquals("touch reaches the same callback",2,writes)
    }
}
