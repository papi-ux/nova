package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaRadius

@Composable
fun NovaPolarisSyncContent(
    serverName: String,
    uiState: NovaPolarisSyncUiState,
    novaProfileText: String,
    polarisProfileText: String,
    onModeSelected: (String) -> Unit,
    onMatchNova: () -> Unit,
    onSendNova: () -> Unit,
    onUsePolaris: () -> Unit,
    onClearProfile: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onAiChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp)
                .height(4.dp)
                .fillMaxWidth(0.12f)
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(colors.divider.copy(alpha = 0.75f))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nova_polaris_sync_title),
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = serverName,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            NovaSyncStatusChip(uiState.status)
        }

        NovaSyncSection(title = stringResource(R.string.nova_polaris_sync_mode_title)) {
            Text(
                text = stringResource(R.string.nova_polaris_sync_desired_format, uiState.desiredModeLabel),
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Text(
                text = stringResource(R.string.nova_polaris_sync_effective_format, uiState.effectiveModeLabel),
                color = colors.textMuted,
                fontSize = 12.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.modes.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { mode ->
                            NovaModeButton(
                                mode = mode,
                                onClick = { onModeSelected(mode.mode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        NovaSyncSection(title = stringResource(R.string.nova_polaris_sync_profile_title)) {
            Text(text = novaProfileText, color = colors.textSecondary, fontSize = 12.sp)
            Text(text = polarisProfileText, color = colors.textMuted, fontSize = 12.sp)
            Text(
                text = profileStateLabel(uiState.profileState),
                color = profileStateColor(uiState.profileState),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (uiState.matchNovaVisible) {
                NovaActionButton(
                    text = stringResource(R.string.nova_polaris_sync_match_nova),
                    onClick = onMatchNova,
                    enabled = uiState.matchNovaEnabled,
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 42.dp,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaActionButton(
                    text = stringResource(R.string.nova_polaris_sync_send_nova),
                    onClick = onSendNova,
                    enabled = uiState.sendNovaEnabled,
                    primary = true,
                    modifier = Modifier.weight(1f),
                    minHeight = 42.dp,
                    fontSize = 12.sp
                )
                NovaActionButton(
                    text = stringResource(R.string.nova_polaris_sync_use_polaris),
                    onClick = onUsePolaris,
                    enabled = uiState.usePolarisEnabled,
                    modifier = Modifier.weight(1f),
                    minHeight = 42.dp,
                    fontSize = 12.sp
                )
                NovaActionButton(
                    text = stringResource(R.string.nova_polaris_sync_clear_profile),
                    onClick = onClearProfile,
                    enabled = uiState.clearProfileEnabled,
                    modifier = Modifier.weight(0.82f),
                    minHeight = 42.dp,
                    fontSize = 12.sp
                )
            }
            NovaSwitchRow(
                label = stringResource(R.string.nova_polaris_sync_auto_match),
                checked = uiState.autoSyncChecked,
                enabled = uiState.autoSyncEnabled,
                onCheckedChange = onAutoSyncChange
            )
        }

        NovaSyncSection(title = stringResource(R.string.nova_polaris_sync_controls_title)) {
            NovaSwitchRow(
                label = stringResource(R.string.nova_polaris_sync_ai_auto_quality),
                checked = uiState.aiChecked,
                enabled = uiState.aiEnabled,
                onCheckedChange = onAiChange
            )
        }
    }
}

@Composable
private fun NovaSyncSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(surfaces.tile)
            .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.hero))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        content()
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
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun NovaModeButton(
    mode: NovaPolarisModeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NovaRadius.row)
    val selected = mode.selectedDesired
    val activeOnly = !mode.selectedDesired && mode.selectedEffective
    val description = listOf(mode.label, mode.statusLabel, mode.reason)
        .filter { it.isNotBlank() }
        .joinToString(". ")
    val statusColor = when {
        selected -> colors.onAccent
        activeOnly -> colors.accent
        !mode.available -> colors.warning
        else -> colors.textMuted
    }
    Box(
        modifier = modifier
            .heightIn(min = 62.dp)
            .clip(shape)
            .background(if (selected) colors.accent else surfaces.control)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) surfaces.focusRing else surfaces.tileBorder,
                shape
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(
                enabled = mode.enabled,
                role = Role.Button,
                onClick = onClick
            )
            // Without this the whole stream-display selector is touch-only: a d-pad walks
            // straight past all four modes to the buttons below, and the focus branch above
            // can never fire because onFocusChanged is never called.
            .focusable(enabled = mode.enabled)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mode.label,
                color = if (selected) colors.onAccent else colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mode.statusLabel,
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!mode.available && mode.reason.isNotBlank()) {
                Text(
                    text = mode.reason,
                    color = colors.warning,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NovaSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics {
                contentDescription = label
                role = Role.Switch
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (enabled) colors.textPrimary else colors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled
        )
    }
}

@Composable
private fun profileStateLabel(state: PolarisProfileSync.ProfileState): String {
    return when (state) {
        PolarisProfileSync.ProfileState.UNAVAILABLE -> stringResource(R.string.nova_polaris_sync_profile_unavailable)
        PolarisProfileSync.ProfileState.POLARIS_UNSET -> stringResource(R.string.nova_polaris_sync_profile_unset)
        PolarisProfileSync.ProfileState.MATCHED -> stringResource(R.string.nova_polaris_sync_profile_matched)
        PolarisProfileSync.ProfileState.DIFFERENT -> stringResource(R.string.nova_polaris_sync_profile_different)
    }
}

@Composable
private fun profileStateColor(state: PolarisProfileSync.ProfileState) = when (state) {
    PolarisProfileSync.ProfileState.MATCHED -> LocalNovaComposeColors.current.accent
    PolarisProfileSync.ProfileState.DIFFERENT,
    PolarisProfileSync.ProfileState.POLARIS_UNSET -> LocalNovaComposeColors.current.warning
    PolarisProfileSync.ProfileState.UNAVAILABLE -> LocalNovaComposeColors.current.textMuted
}
