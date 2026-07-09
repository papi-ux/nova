package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
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
        composeRule.onNodeWithText("Private Stream").assertIsDisplayed()
        composeRule.onNodeWithText("Host Virtual Display").assertIsDisplayed()
        composeRule.onNodeWithText("Desktop Display").assertIsDisplayed()
        composeRule.onNodeWithText("GPU-Native Test").assertIsDisplayed()
        composeRule.onNodeWithText(launchProfileTitle).assertIsDisplayed()
        composeRule.onNodeWithText(matchNovaAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(pushNovaAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(pullPolarisAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(clearProfileAction).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(autoMatchLabel).assertIsDisplayed()
        composeRule.onNodeWithText(aiAutoQualityLabel).performScrollTo().assertIsDisplayed()
    }

    private fun settings() = PolarisClientSettings(
        desired = PolarisClientSettings.Desired(
            streamDisplayMode = PolarisClientSettings.MODE_GPU_NATIVE_TEST,
            displayMode = "2560x1440@60",
            targetBitrateKbps = 45000,
            aiAutoQualityEnabled = true
        ),
        effective = PolarisClientSettings.Effective(
            streamDisplayMode = PolarisClientSettings.MODE_HEADLESS_STREAM,
            adaptiveBitrateEnabled = true
        ),
        capabilities = PolarisClientSettings.Capabilities(
            modes = PolarisStreamDisplayMode.ORDER.map {
                PolarisClientSettings.ModeOption(value = it, available = true)
            },
            aiAutoQualityControl = true
        ),
        relaunchRequired = true
    )
}
