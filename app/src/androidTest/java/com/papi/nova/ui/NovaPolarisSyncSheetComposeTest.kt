package com.papi.nova.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.ui.compose.NovaComposeTheme
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

        composeRule.onNodeWithText("Polaris Sync").assertIsDisplayed()
        composeRule.onNodeWithText("Test Server").assertIsDisplayed()
        composeRule.onNodeWithText("Stream Display").assertIsDisplayed()
        composeRule.onNodeWithText("Headless").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Virtual Display").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Launch Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Push Nova").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Pull Polaris").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Clear Profile").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("Auto match this server").assertIsDisplayed()
        composeRule.onNodeWithText("AI Auto Quality").performScrollTo().assertIsDisplayed()
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
