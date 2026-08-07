package com.papi.nova.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.PolarisSettingsSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The host-settings engine behind Polaris Sync, factored out of the sheet so a second
 * surface can drive the same host without a second copy of the update discipline.
 *
 * Two surfaces own one of these each — the Polaris Sync sheet and Play Setup's
 * Every Game scope — and both get the same behaviour: the optimistic desired-mode
 * write with revert on failure, the busy gate, the auto-sync throttle, and the
 * polling refresh. What they choose per surface is only where messages land, which
 * is why feedback goes through [onMessage] instead of a Toast raised in here.
 */
internal class NovaPolarisSyncEngine(
    private val context: Context,
    private val apiClient: PolarisApiClient?,
    private val serverUuid: String?,
    private val scope: CoroutineScope,
    private val onSettingsChanged: (PolarisClientSettings) -> Unit = {},
    private val onMessage: (messageRes: Int, isError: Boolean) -> Unit = { _, _ -> },
) {
    var currentSettings by mutableStateOf<PolarisClientSettings?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var settingsUnavailable by mutableStateOf(false)
        private set
    var autoSyncEnabled by mutableStateOf(false)
        private set

    /** Bumped when Nova's own streaming profile changes, so prefs reads recompose. */
    var profileRevision by mutableIntStateOf(0)
        private set

    private var lastAutoSyncAt = 0L
    private var settingsSync: PolarisSettingsSyncManager? = null

    fun start(initialSettings: PolarisClientSettings?) {
        if (settingsSync != null) {
            return
        }
        currentSettings = initialSettings
        autoSyncEnabled = PolarisProfileSync.isAutoSyncEnabled(context, serverUuid)
        val client = apiClient ?: run {
            settingsUnavailable = true
            return
        }
        settingsSync = PolarisSettingsSyncManager(client) { settings ->
            if (settings != null) {
                settingsUnavailable = false
                currentSettings = settings
                onSettingsChanged(settings)
            } else if (currentSettings == null) {
                settingsUnavailable = true
            }
            settings?.let { maybeAutoSync(it) }
        }.also { it.start(immediate = true) }
    }

    fun close() {
        settingsSync?.close()
        settingsSync = null
    }

    fun refresh() {
        settingsSync?.refresh()
    }

    fun setStreamDisplayMode(mode: String) {
        updatePolarisSettings(streamDisplayMode = mode)
    }

    fun setAiAutoQuality(enabled: Boolean) {
        updatePolarisSettings(aiAutoQualityEnabled = enabled)
    }

    fun matchNova() {
        pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova)
    }

    fun sendNova() {
        pushNovaProfile(R.string.nova_polaris_sync_saved_to_polaris)
    }

    fun clearProfile() {
        updatePolarisSettings(
            clearDisplayMode = true,
            clearTargetBitrate = true,
            successMessage = R.string.nova_polaris_sync_cleared
        )
    }

    fun usePolarisProfile() {
        val settings = currentSettings ?: return
        val polarisProfile = PolarisProfileSync.polarisOverrideProfile(settings)
        if (polarisProfile == null ||
            !PreferenceConfiguration.applyPolarisStreamingProfile(
                context,
                polarisProfile.displayMode,
                polarisProfile.bitrateKbps
            )
        ) {
            onMessage(R.string.nova_polaris_sync_failed, true)
            return
        }
        profileRevision++
        onMessage(R.string.nova_polaris_sync_saved_to_nova, false)
    }

    fun setAutoSync(checked: Boolean) {
        PolarisProfileSync.setAutoSyncEnabled(context, serverUuid, checked)
        autoSyncEnabled = PolarisProfileSync.isAutoSyncEnabled(context, serverUuid)
        if (checked) {
            currentSettings?.let { maybeAutoSync(it) }
        }
    }

    private fun pushNovaProfile(successMessage: Int, showMessage: Boolean = true) {
        val prefs = PreferenceConfiguration.readPreferences(context)
        updatePolarisSettings(
            displayMode = PreferenceConfiguration.formatStreamingDisplayMode(prefs.width, prefs.height, prefs.fps),
            targetBitrateKbps = prefs.bitrate,
            successMessage = successMessage,
            showMessage = showMessage
        )
    }

    private fun updatePolarisSettings(
        streamDisplayMode: String? = null,
        displayMode: String? = null,
        clearDisplayMode: Boolean = false,
        targetBitrateKbps: Int? = null,
        clearTargetBitrate: Boolean = false,
        adaptiveBitrateEnabled: Boolean? = null,
        aiOptimizerEnabled: Boolean? = null,
        aiAutoQualityEnabled: Boolean? = null,
        successMessage: Int = R.string.nova_polaris_sync_saved_to_polaris,
        showMessage: Boolean = true
    ) {
        val client = apiClient ?: return
        val previousSettings = currentSettings
        val requestedMode = PolarisStreamDisplayMode.normalize(streamDisplayMode)
        if (requestedMode.isNotBlank()) {
            currentSettings = previousSettings?.copy(
                desired = previousSettings.desired.copy(
                    streamDisplayMode = requestedMode,
                    streamDisplayModeLabel = PolarisStreamDisplayMode.labelForMode(requestedMode)
                ),
                relaunchRequired = previousSettings.effective.streamDisplayMode.isNotBlank() &&
                    PolarisStreamDisplayMode.normalize(previousSettings.effective.streamDisplayMode) != requestedMode
            )
        }
        busy = true
        scope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                try {
                    client.updateClientSettings(
                        streamDisplayMode = streamDisplayMode,
                        displayMode = displayMode,
                        clearDisplayMode = clearDisplayMode,
                        targetBitrateKbps = targetBitrateKbps,
                        clearTargetBitrate = clearTargetBitrate,
                        adaptiveBitrateEnabled = adaptiveBitrateEnabled,
                        aiOptimizerEnabled = aiOptimizerEnabled,
                        aiAutoQualityEnabled = aiAutoQualityEnabled
                    )
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Polaris sync update failed: ${e.message}")
                    null
                }
            }
            busy = false
            if (confirmed == null) {
                currentSettings = previousSettings
                onMessage(R.string.nova_polaris_sync_failed, true)
                return@launch
            }

            currentSettings = confirmed
            onSettingsChanged(confirmed)
            settingsSync?.refresh()
            if (showMessage) {
                onMessage(successMessage, false)
            }
        }
    }

    private fun maybeAutoSync(settings: PolarisClientSettings) {
        if (busy || !PolarisProfileSync.isAutoSyncEnabled(context, serverUuid)) {
            return
        }
        val prefs = PreferenceConfiguration.readPreferences(context)
        val novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(prefs.width, prefs.height, prefs.fps)
        val profileState = PolarisProfileSync.compare(novaDisplayMode, prefs.bitrate, settings)
        if (profileState != PolarisProfileSync.ProfileState.DIFFERENT &&
            profileState != PolarisProfileSync.ProfileState.POLARIS_UNSET
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoSyncAt < PolarisProfileSync.AUTO_SYNC_MIN_INTERVAL_MS) {
            return
        }
        lastAutoSyncAt = now
        pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova, showMessage = false)
    }
}
