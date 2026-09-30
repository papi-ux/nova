package com.papi.nova.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.PolarisProfileSync

/**
 * The one implementation of the Every Game host controls.
 *
 * Play Setup's Every Game scope and the library's Polaris Sync used to be two renderings
 * of the same engine, four rows here and three bespoke sections there, and the second
 * was the copy that drifted. Both now draw these composables, so the subject reads
 * identically wherever it is opened.
 */
@Composable
internal fun NovaHostSetupRowList(
    rows: List<NovaPlaySetupRowState>,
    onAdvance: (NovaPlaySetupRow) -> Unit,
    /**
     * Focus marks for each row, from the page that draws them: whether it is the row the page
     * opens on (the first), and where focus returns after a page above it pops.
     */
    rowModifier: (row: NovaPlaySetupRow, first: Boolean) -> Modifier = { _, _ -> Modifier },
) {
    rows.forEachIndexed { index, rowState ->
        NovaPlaySetupSettingRow(
            state = rowState,
            onAdvance = onAdvance,
            modifier = rowModifier(rowState.row, index == 0),
        )
    }
}

/** The HOST_PROFILE row's value, shared by Play Setup and Polaris Sync. */
internal fun novaPlaySetupHostProfileValue(
    sync: NovaPolarisSyncUiState,
    settings: PolarisClientSettings?,
    getString: (Int) -> String,
): String {
    if (sync.profileState == PolarisProfileSync.ProfileState.MATCHED) {
        return getString(R.string.nova_play_setup_host_profile_matched)
    }
    val profile = settings?.let { PolarisProfileSync.polarisOverrideProfile(it) }
    return when {
        profile == null -> getString(R.string.nova_polaris_sync_unset)
        profile.bitrateKbps > 0 ->
            "${profile.displayMode.takeIf { it.isNotBlank() }?.let(::novaDisplayModeLabel) ?: getString(R.string.nova_polaris_sync_unset)} · ${profile.bitrateKbps / 1000}\u00a0Mbps"
        else -> novaDisplayModeLabel(profile.displayMode)
    }
}
