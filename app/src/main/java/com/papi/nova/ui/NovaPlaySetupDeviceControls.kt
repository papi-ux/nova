package com.papi.nova.ui

import com.papi.nova.preferences.NovaSettingsUiState
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingValue

/** Shared device settings projection for Play Setup's Every game scope. */
internal fun buildNovaDevicePlaySetupRows(
    state: NovaSettingsUiState,
    onValue: (NovaSettingDefinition, NovaSettingValue) -> Unit,
): List<NovaPlaySetupRowState> = emptyList()
