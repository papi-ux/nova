package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovaPolarisSyncSheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun syncSheetContentShowsModesActionsAndToggles() {
        val state = NovaPolarisSyncUiStateMapper.build(
            settings = settings(),
            busy = false,
            settingsUnavailable = false,
            autoSyncEnabled = true,
            hasServerUuid = true,
            novaDisplayMode = "1920x1080@60",
            novaBitrateKbps = 30000
        )

        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.setContent {
            NovaComposeTheme {
                NovaPolarisSyncContent(
                    serverName = "Test Server",
                    uiState = state,
                    novaProfileText = "Nova: 1920x1080@60 · 30 Mbps",
                    polarisProfileText = "Polaris: 2560x1440@60 · 45 Mbps",
                    onModeSelected = {},
                    onMatchNova = {},
                    onSendNova = {},
                    onUsePolaris = {},
                    onClearProfile = {},
                    onAutoSyncChange = {},
                    onAiChange = {}
                )
            }
        }

        val privateStreamLabel = context.getString(R.string.nova_library_launch_headless)
        val virtualDisplayLabel = context.getString(R.string.nova_library_launch_virtual_display)
        val title = context.getString(R.string.nova_polaris_sync_title)
        val streamDisplayTitle = context.getString(R.string.nova_polaris_sync_mode_title)
        val launchProfileTitle = context.getString(R.string.nova_polaris_sync_profile_title)
        val matchNovaAction = context.getString(R.string.nova_polaris_sync_match_nova)
        val pushNovaAction = context.getString(R.string.nova_polaris_sync_send_nova)
        val pullPolarisAction = context.getString(R.string.nova_polaris_sync_use_polaris)
        val clearProfileAction = context.getString(R.string.nova_polaris_sync_clear_profile)
        val autoMatchLabel = context.getString(R.string.nova_polaris_sync_auto_match)
        val aiAutoQualityLabel = context.getString(R.string.nova_polaris_sync_ai_auto_quality)

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("Test Server").assertIsDisplayed()
        composeRule.onNodeWithText(streamDisplayTitle).assertIsDisplayed()
        assertAnyModeButtonDisplayed(
            composeRule,
            listOf(privateStreamLabel, "Headless")
        )
        assertAnyModeButtonDisplayed(
            composeRule,
            listOf(virtualDisplayLabel, "Virtual display", "Virtual")
        )
        composeRule.onNodeWithText(launchProfileTitle).assertIsDisplayed()
        composeRule.onNodeWithText(matchNovaAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(pushNovaAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(pullPolarisAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(clearProfileAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(autoMatchLabel).assertIsDisplayed()
        composeRule.onNodeWithText(aiAutoQualityLabel).performScrollTo().assertIsDisplayed()
    }

    private fun assertAnyModeButtonDisplayed(composeRule: androidx.compose.ui.test.junit4.ComposeContentTestRule, labels: List<String>) {
        val found = labels.any { label ->
            try {
                composeRule.onNode(hasContentDescription(label)).assertIsDisplayed().assertHasClickAction()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        assertTrue("Expected one of mode labels to be displayed: $labels", found)
    }

    private fun settings() = PolarisClientSettings(
        desired = PolarisClientSettings.Desired(
            streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM,
            displayMode = "2560x1440@60",
            targetBitrateKbps = 45000,
            aiAutoQualityEnabled = true
        ),
        effective = PolarisClientSettings.Effective(
            streamDisplayMode = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
            adaptiveBitrateEnabled = true
        ),
        capabilities = PolarisClientSettings.Capabilities(
            modes = listOf(
                PolarisClientSettings.ModeOption(
                    value = PolarisClientSettings.MODE_HEADLESS_STREAM,
                    available = true
                ),
                PolarisClientSettings.ModeOption(
                    value = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
                    available = true
                )
            ),
            aiAutoQualityControl = true
        )
    )
}
