package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.manager.PolarisProfileSync

enum class NovaPolarisSyncStatus {
    LOADING,
    UNAVAILABLE,
    SYNCED,
    SYNCING
}

data class NovaPolarisModeUiState(
    val mode: String,
    val label: String,
    val selected: Boolean,
    val selectedDesired: Boolean,
    val selectedEffective: Boolean,
    val enabled: Boolean,
    val available: Boolean,
    val reason: String,
    val restartRequired: Boolean,
    val statusLabel: String
)

data class NovaPolarisSyncUiState(
    val status: NovaPolarisSyncStatus,
    val desiredModeLabel: String,
    val effectiveModeLabel: String,
    val modes: List<NovaPolarisModeUiState>,
    val profileState: PolarisProfileSync.ProfileState,
    val matchNovaVisible: Boolean,
    val matchNovaEnabled: Boolean,
    val sendNovaEnabled: Boolean,
    val usePolarisEnabled: Boolean,
    val clearProfileEnabled: Boolean,
    val aiChecked: Boolean,
    val aiEnabled: Boolean,
    val autoSyncChecked: Boolean,
    val autoSyncEnabled: Boolean,
    val relaunchRequired: Boolean,
    val modeSummary: String
)

object NovaPolarisSyncUiStateMapper {
    // Every label arrives from the caller, none defaults to English here: a default
    // is a string that ships in every locale unnoticed, which is exactly how the
    // status labels below lived in Kotlin for a year.
    fun build(
        settings: PolarisClientSettings?,
        busy: Boolean,
        settingsUnavailable: Boolean,
        autoSyncEnabled: Boolean,
        hasServerUuid: Boolean,
        novaDisplayMode: String,
        novaBitrateKbps: Int,
        loadingLabel: String,
        unavailableLabel: String,
        unsetLabel: String,
        savedAfterRelaunchLabel: String,
        selectedLabel: String,
        activeNowLabel: String,
        availableLabel: String
    ): NovaPolarisSyncUiState {
        val fallback = if (settingsUnavailable) unavailableLabel else loadingLabel
        val desiredMode = PolarisStreamDisplayMode.normalize(settings?.desired?.streamDisplayMode)
        val effectiveMode = PolarisStreamDisplayMode.normalize(settings?.effective?.streamDisplayMode)
        val selectedMode = desiredMode.ifBlank { effectiveMode }
        val availableModes = settings?.capabilities?.modes
            ?.takeIf { it.isNotEmpty() }
            ?.groupBy { PolarisStreamDisplayMode.normalize(it.value) }
            ?.mapValues { (_, modes) -> modes.firstOrNull { it.available } ?: modes.first() }
        val profileState = PolarisProfileSync.compare(novaDisplayMode, novaBitrateKbps, settings)
        val hasPolarisProfile = settings?.let { PolarisProfileSync.polarisOverrideProfile(it) } != null
        val aiAvailable = settings?.capabilities?.aiAutoQualityControl == true ||
            settings?.capabilities?.aiOptimizerControl == true ||
            settings?.capabilities?.adaptiveBitrateControl == true
        val aiChecked = settings?.effective?.aiAutoQualityEnabled == true ||
            settings?.effective?.aiOptimizerEnabled == true ||
            settings?.effective?.adaptiveBitrateEnabled == true ||
            settings?.desired?.aiAutoQualityEnabled == true ||
            settings?.desired?.aiOptimizerEnabled == true ||
            settings?.desired?.adaptiveBitrateEnabled == true
        val relaunchRequired = settings?.relaunchRequired == true
        val desiredModeLabel = if (settings == null) {
            fallback
        } else {
            settings.desired.streamDisplayModeLabel
                .takeIf { it.isNotBlank() }
                ?: PolarisStreamDisplayMode.labelForMode(desiredMode).ifBlank { unsetLabel }
        }
        val effectiveModeLabel = if (settings == null) {
            fallback
        } else {
            settings.effective.streamDisplayModeLabel
                .takeIf { it.isNotBlank() }
                ?: PolarisStreamDisplayMode.labelForMode(effectiveMode).ifBlank { unsetLabel }
        }

        return NovaPolarisSyncUiState(
            status = when {
                busy -> NovaPolarisSyncStatus.SYNCING
                settings != null -> NovaPolarisSyncStatus.SYNCED
                settingsUnavailable -> NovaPolarisSyncStatus.UNAVAILABLE
                else -> NovaPolarisSyncStatus.LOADING
            },
            desiredModeLabel = desiredModeLabel,
            effectiveModeLabel = effectiveModeLabel,
            modes = PolarisStreamDisplayMode.ORDER.map { mode ->
                val option = availableModes?.get(mode)
                val available = option?.available ?: true
                val desiredSelected = selectedMode == mode
                val effectiveSelected = effectiveMode == mode
                NovaPolarisModeUiState(
                    mode = mode,
                    label = PolarisStreamDisplayMode.labelForMode(mode),
                    selected = desiredSelected,
                    selectedDesired = desiredSelected,
                    selectedEffective = effectiveSelected,
                    enabled = settings != null && available && !busy,
                    available = available,
                    reason = option?.reason.orEmpty(),
                    restartRequired = option?.restartRequired ?: true,
                    statusLabel = when {
                        desiredSelected && relaunchRequired && !effectiveSelected -> savedAfterRelaunchLabel
                        desiredSelected -> selectedLabel
                        effectiveSelected -> activeNowLabel
                        available -> availableLabel
                        else -> unavailableLabel
                    }
                )
            },
            profileState = profileState,
            matchNovaVisible = settings != null && profileState != PolarisProfileSync.ProfileState.MATCHED,
            matchNovaEnabled = settings != null && profileState != PolarisProfileSync.ProfileState.MATCHED && !busy,
            sendNovaEnabled = settings != null && !busy,
            usePolarisEnabled = hasPolarisProfile && !busy,
            clearProfileEnabled = hasPolarisProfile && !busy,
            aiChecked = aiChecked,
            aiEnabled = aiAvailable && !busy,
            autoSyncChecked = autoSyncEnabled,
            autoSyncEnabled = hasServerUuid && settings != null && !busy,
            relaunchRequired = relaunchRequired,
            modeSummary = if (settings == null) {
                fallback
            } else if (desiredModeLabel == effectiveModeLabel) {
                desiredModeLabel
            } else {
                "$desiredModeLabel → $effectiveModeLabel"
            }
        )
    }
}
