package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaRadius

/**
 * The one implementation of the Every Game host controls.
 *
 * Play Setup's Every Game scope and the System drawer's Polaris Sync sheet used to be
 * two renderings of the same engine — four rows here, three bespoke sections there —
 * and the sheet was the copy that drifted. Both now draw these composables, so the
 * subject reads identically wherever it is opened.
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
        NovaPlaySetupComparison(
            title = explained.stripTitle,
            options = explained.options,
            consequenceMaxLines = consequenceMaxLines,
            // 2x2 for the classic four; three per row once a six-mode catalog
            // would otherwise stack three rows.
            perRow = if (explained.row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY) {
                if (explained.options.size > 4) 3 else 2
            } else {
                Int.MAX_VALUE
            },
        )
    }
}

/** The HOST_PROFILE row's value, shared by the panel and the sheet. */
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

/**
 * The Polaris Sync sheet's whole body: the same four host rows Play Setup shows,
 * behind the sheet's own header chrome. The mode picker takes the body over here
 * exactly as it does in the wide panel.
 */
@Composable
internal fun NovaPolarisSyncSheetBody(
    serverName: String,
    status: NovaPolarisSyncStatus,
    rows: List<NovaPlaySetupRowState>,
    explainedRow: NovaPlaySetupRow,
    modePicker: NovaPlaySetupModePickerState?,
    onExplain: (NovaPlaySetupRow) -> Unit,
    onAdvance: (NovaPlaySetupRow) -> Unit,
    onPickMode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nova_polaris_sync_title),
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = serverName,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NovaSyncStatusChip(status)
            NovaActionButton(
                text = stringResource(R.string.nova_polaris_sync_close),
                onClick = onClose,
                contentDescription = stringResource(R.string.nova_polaris_sync_close_cd),
                minHeight = 36.dp,
                fontSize = 11.sp,
            )
        }

        if (modePicker != null) {
            NovaPlaySetupModePicker(
                state = modePicker,
                onPick = onPickMode,
                onPickHostDefault = null,
            )
        } else {
            NovaHostSetupRowList(rows = rows, onExplain = onExplain, onAdvance = onAdvance)
            NovaHostSetupComparison(rows = rows, explainedRow = explainedRow, consequenceMaxLines = 2)
        }
    }
}

@Composable
private fun NovaSyncStatusChip(status: NovaPolarisSyncStatus) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val label = when (status) {
        NovaPolarisSyncStatus.LOADING -> stringResource(R.string.nova_polaris_sync_loading)
        NovaPolarisSyncStatus.UNAVAILABLE -> stringResource(R.string.nova_polaris_sync_unavailable)
        NovaPolarisSyncStatus.SYNCED -> stringResource(R.string.nova_polaris_sync_synced)
        NovaPolarisSyncStatus.SYNCING -> stringResource(R.string.nova_polaris_sync_syncing)
    }
    val contentColor = when (status) {
        NovaPolarisSyncStatus.SYNCING -> colors.warning
        NovaPolarisSyncStatus.UNAVAILABLE -> colors.textMuted
        else -> colors.onAccent
    }
    val fill = when (status) {
        NovaPolarisSyncStatus.SYNCED -> colors.accent
        NovaPolarisSyncStatus.SYNCING -> surfaces.control
        else -> surfaces.control
    }

    Text(
        text = label,
        color = contentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(fill)
            .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.pill))
            .semantics { contentDescription = label }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}
