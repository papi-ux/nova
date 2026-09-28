package com.papi.nova.ui

import androidx.compose.runtime.Composable
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
    onExplain: (NovaPlaySetupRow) -> Unit,
    onAdvance: (NovaPlaySetupRow) -> Unit,
) {
    rows.forEachIndexed { index, rowState ->
        NovaSteamChoiceRow(
            autoFocus = index == 0,
            label = rowState.label,
            caption = rowState.caption,
            enabled = rowState.enabled,
            value = rowState.value,
            selected = rowState.overridden,
            onClick = { onAdvance(rowState.row) },
            onFocused = { onExplain(rowState.row) },
            firstPressFocuses = true,
        )
    }
}

@Composable
internal fun NovaHostSetupComparison(
    rows: List<NovaPlaySetupRowState>,
    explainedRow: NovaPlaySetupRow,
    consequenceMaxLines: Int,
) {
    val explained = rows.firstOrNull { it.row == explainedRow } ?: rows.firstOrNull()
    if (explained != null && explained.options.size > 1) {
        // 2x2 for the classic four; three per row once a six-mode catalog
        // would otherwise stack three rows.
        val perRow = if (explained.row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY) {
            if (explained.options.size > 4) 3 else 2
        } else {
            Int.MAX_VALUE
        }
        NovaPlaySetupComparison(
            title = explained.stripTitle,
            options = explained.options,
            // A legend that stacks rows of cards is already tall, and it sits under the rows it
            // explains. One line each keeps those rows on the screen.
            consequenceMaxLines = if (explained.options.size > perRow) 1 else consequenceMaxLines,
            perRow = perRow,
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
            "${profile.displayMode.ifBlank { getString(R.string.nova_polaris_sync_unset) }} · ${profile.bitrateKbps / 1000} Mbps"
        else -> profile.displayMode
    }
}
