package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Rule
import org.junit.Test

/**
 * The sheet renders the same host rows Play Setup's Every Game scope does — one
 * implementation, two entry points — so what this suite pins is that the shared
 * body actually surfaces the four rows, the profile verbs, and the mode catalog
 * the mapper was fed, and that the picker takes the body over whole.
 */
class NovaPolarisSyncSheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sheetBodyShowsTheHostRowsAndTheModeCatalog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = uiState(context)

        composeRule.setContent {
            NovaComposeTheme {
                NovaPolarisSyncSheetBody(
                    serverName = "Test Server",
                    status = state.status,
                    rows = rows(context, state),
                    explainedRow = NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
                    modePicker = null,
                    onExplain = {},
                    onAdvance = {},
                    onPickMode = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.nova_polaris_sync_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Test Server").assertIsDisplayed()
        listOf(
            R.string.nova_play_setup_host_default_display,
            R.string.nova_play_setup_host_profile_row,
            R.string.nova_play_setup_host_auto_quality,
            R.string.nova_play_setup_host_keep_in_step,
        ).forEach { row ->
            composeRule.onAllNodesWithText(context.getString(row)).onFirst()
                .performScrollTo()
                .assertIsDisplayed()
        }
        // Asked of the same source the strip draws from, rather than copied out of
        // it — the drift this suite exists to catch is a hardcoded label going stale.
        PolarisStreamDisplayMode.ORDER.forEach { mode ->
            composeRule.onAllNodesWithText(PolarisStreamDisplayMode.labelForMode(mode)).onFirst()
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun profileVerbsAppearWhenTheProfileRowIsExplained() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = uiState(context)

        composeRule.setContent {
            NovaComposeTheme {
                NovaPolarisSyncSheetBody(
                    serverName = "Test Server",
                    status = state.status,
                    rows = rows(context, state),
                    explainedRow = NovaPlaySetupRow.HOST_PROFILE,
                    modePicker = null,
                    onExplain = {},
                    onAdvance = {},
                    onPickMode = {},
                    onClose = {},
                )
            }
        }

        listOf(
            R.string.nova_polaris_sync_match_nova,
            R.string.nova_polaris_sync_send_nova,
            R.string.nova_polaris_sync_use_polaris,
            R.string.nova_polaris_sync_clear_profile,
        ).forEach { verb ->
            composeRule.onAllNodesWithText(context.getString(verb)).onFirst()
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    @Test
    fun thePickerOwnsTheBodyWhenOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = uiState(context)

        composeRule.setContent {
            NovaComposeTheme {
                NovaPolarisSyncSheetBody(
                    serverName = "Test Server",
                    status = state.status,
                    rows = rows(context, state),
                    explainedRow = NovaPlaySetupRow.HOST_DEFAULT_DISPLAY,
                    modePicker = buildHostModePickerState(
                        modes = state.modes,
                        title = context.getString(R.string.nova_play_setup_host_default_display),
                    ),
                    onExplain = {},
                    onAdvance = {},
                    onPickMode = {},
                    onClose = {},
                )
            }
        }

        composeRule.onAllNodesWithText(
            PolarisStreamDisplayMode.labelForMode(PolarisClientSettings.MODE_HEADLESS_STREAM)
        ).onFirst().assertIsDisplayed()
        // The rows leave when the picker owns the body; a choice should not be made
        // beside a second copy of the thing it is changing.
        composeRule.onNodeWithText(context.getString(R.string.nova_play_setup_host_profile_row))
            .assertDoesNotExist()
    }

    private fun uiState(context: Context) = NovaPolarisSyncUiStateMapper.build(
        settings = settings(),
        busy = false,
        settingsUnavailable = false,
        autoSyncEnabled = true,
        hasServerUuid = true,
        novaDisplayMode = "1920x1080@60",
        novaBitrateKbps = 30000,
        loadingLabel = context.getString(R.string.nova_polaris_sync_loading),
        unavailableLabel = context.getString(R.string.nova_polaris_sync_unavailable),
        unsetLabel = context.getString(R.string.nova_polaris_sync_unset),
        savedAfterRelaunchLabel = context.getString(R.string.nova_polaris_sync_status_saved_relaunch),
        selectedLabel = context.getString(R.string.nova_polaris_sync_status_selected),
        activeNowLabel = context.getString(R.string.nova_polaris_sync_status_active_now),
        availableLabel = context.getString(R.string.nova_polaris_sync_status_available)
    )

    private fun rows(context: Context, state: NovaPolarisSyncUiState) = buildNovaPlaySetupHostRows(
        sync = state,
        polarisProfileValue = novaPlaySetupHostProfileValue(
            sync = state,
            settings = settings(),
            getString = { resId -> context.getString(resId) },
        ),
        getString = { resId -> context.getString(resId) },
        actions = NovaPlaySetupHostActions(
            onSelectMode = {},
            onMatchNova = {},
            onSendNova = {},
            onUsePolaris = {},
            onClearProfile = {},
            onAutoQuality = {},
            onKeepInStep = {},
        ),
    )

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
