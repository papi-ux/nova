package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisDesktopSteam
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSteamControlsComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openingSteamWaitsThroughStoppedUntilTheHostObservesRunning() {
        val client = mock(PolarisApiClient::class.java)
        val stopped = PolarisDesktopSteam("stopped", revision = "before")
        val running = PolarisDesktopSteam("running", revision = "after")
        `when`(client.getDesktopSteam()).thenReturn(stopped, stopped, running)
        `when`(client.controlDesktopSteam("open", "before", false)).thenReturn(stopped.copy(accepted = true))
        val panel = NovaPanelState().apply { open(NovaSteamPage(client, "Living Room PC")) }
        rule.setPanelContent {
            NovaPageStackHost(state = panel) { page ->
                if (page is NovaSteamPage) NovaSteamControls(page)
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Steam is stopped.").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText("Open Steam").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Steam is running.").fetchSemanticsNodes().isNotEmpty()
        }

        verify(client, times(1)).controlDesktopSteam("open", "before", false)
        verify(client, times(3)).getDesktopSteam()
    }
}
