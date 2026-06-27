package com.papi.nova.ui

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.PolarisSettingsSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.NovaComposeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host-aware Polaris settings surface for the currently selected server.
 */
class NovaPolarisSyncSheet : BottomSheetDialogFragment() {

    private var apiClient: PolarisApiClient? = null
    private var serverName: String = ""
    private var serverUuid: String? = null
    private var initialSettings: PolarisClientSettings? = null
    private var onSettingsChanged: ((PolarisClientSettings) -> Unit)? = null

    private var settingsSync: PolarisSettingsSyncManager? = null
    private var currentSettings by mutableStateOf<PolarisClientSettings?>(null)
    private var busy by mutableStateOf(false)
    private var settingsUnavailable by mutableStateOf(false)
    private var autoSyncEnabled by mutableStateOf(false)
    private var profileRevision by mutableIntStateOf(0)
    private var lastAutoSyncAt = 0L

    companion object {
        @JvmStatic
        fun newInstance(
            apiClient: PolarisApiClient,
            serverName: String,
            initialSettings: PolarisClientSettings?
        ): NovaPolarisSyncSheet {
            return newInstance(apiClient, serverName, null, initialSettings) { }
        }

        @JvmStatic
        fun newInstance(
            apiClient: PolarisApiClient,
            serverName: String,
            serverUuid: String?,
            initialSettings: PolarisClientSettings?
        ): NovaPolarisSyncSheet {
            return newInstance(apiClient, serverName, serverUuid, initialSettings) { }
        }

        fun newInstance(
            apiClient: PolarisApiClient,
            serverName: String,
            initialSettings: PolarisClientSettings?,
            onSettingsChanged: (PolarisClientSettings) -> Unit
        ): NovaPolarisSyncSheet {
            return newInstance(apiClient, serverName, null, initialSettings, onSettingsChanged)
        }

        fun newInstance(
            apiClient: PolarisApiClient,
            serverName: String,
            serverUuid: String?,
            initialSettings: PolarisClientSettings?,
            onSettingsChanged: (PolarisClientSettings) -> Unit
        ): NovaPolarisSyncSheet {
            return NovaPolarisSyncSheet().apply {
                this.apiClient = apiClient
                this.serverName = serverName
                this.serverUuid = serverUuid
                this.initialSettings = initialSettings
                this.onSettingsChanged = onSettingsChanged
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        currentSettings = initialSettings
        autoSyncEnabled = PolarisProfileSync.isAutoSyncEnabled(requireContext(), serverUuid)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NovaComposeTheme {
                    val profileVersion = profileRevision
                    val prefs = PreferenceConfiguration.readPreferences(requireContext())
                    val novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(
                        prefs.width,
                        prefs.height,
                        prefs.fps
                    )
                    val uiState = NovaPolarisSyncUiStateMapper.build(
                        settings = currentSettings,
                        busy = busy,
                        settingsUnavailable = settingsUnavailable,
                        autoSyncEnabled = autoSyncEnabled,
                        hasServerUuid = !serverUuid.isNullOrBlank(),
                        novaDisplayMode = novaDisplayMode,
                        novaBitrateKbps = prefs.bitrate,
                        loadingLabel = getString(R.string.nova_polaris_sync_loading),
                        unavailableLabel = getString(R.string.nova_polaris_sync_unavailable),
                        unsetLabel = getString(R.string.nova_polaris_sync_unset)
                    )
                    key(profileVersion) {
                        NovaPolarisSyncContent(
                            serverName = serverName,
                            uiState = uiState,
                            novaProfileText = novaProfileText(novaDisplayMode, prefs.bitrate),
                            polarisProfileText = polarisProfileText(currentSettings),
                            onModeSelected = { updatePolarisSettings(streamDisplayMode = it) },
                            onMatchNova = { pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova) },
                            onSendNova = { sendNovaProfile() },
                            onUsePolaris = { usePolarisProfile() },
                            onClearProfile = {
                                updatePolarisSettings(
                                    clearDisplayMode = true,
                                    clearTargetBitrate = true,
                                    successMessage = R.string.nova_polaris_sync_cleared
                                )
                            },
                            onAutoSyncChange = { checked ->
                                PolarisProfileSync.setAutoSyncEnabled(requireContext(), serverUuid, checked)
                                autoSyncEnabled = PolarisProfileSync.isAutoSyncEnabled(requireContext(), serverUuid)
                                if (checked) {
                                    currentSettings?.let { maybeAutoSync(it) }
                                }
                            },
                            onAiChange = { updatePolarisSettings(aiAutoQualityEnabled = it) }
                        )
                    }
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                expandBottomSheet(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        view?.post {
            expandBottomSheet(dialog as? BottomSheetDialog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val client = apiClient ?: run {
            settingsUnavailable = true
            return
        }

        settingsSync = PolarisSettingsSyncManager(client) { settings ->
            if (settings != null) {
                settingsUnavailable = false
                currentSettings = settings
                onSettingsChanged?.invoke(settings)
            } else if (currentSettings == null) {
                settingsUnavailable = true
            }
            settings?.let { maybeAutoSync(it) }
        }.also { it.start(immediate = true) }
    }

    override fun onDestroyView() {
        settingsSync?.close()
        settingsSync = null
        super.onDestroyView()
    }

    private fun novaProfileText(displayMode: String, bitrateKbps: Int): String {
        return getString(
            R.string.nova_polaris_sync_profile_format,
            getString(R.string.nova_polaris_sync_nova_profile) + ": " + displayMode,
            bitrateKbps / 1000
        )
    }

    private fun polarisProfileText(settings: PolarisClientSettings?): String {
        val polarisProfile = settings?.let { PolarisProfileSync.polarisOverrideProfile(it) }
        return if (polarisProfile == null) {
            getString(R.string.nova_polaris_sync_polaris_profile) + ": " +
                getString(R.string.nova_polaris_sync_unset)
        } else if (polarisProfile.bitrateKbps > 0) {
            getString(
                R.string.nova_polaris_sync_profile_format,
                getString(R.string.nova_polaris_sync_polaris_profile) + ": " +
                    polarisProfile.displayMode.ifBlank { getString(R.string.nova_polaris_sync_unset) },
                polarisProfile.bitrateKbps / 1000
            )
        } else {
            getString(
                R.string.nova_polaris_sync_profile_no_bitrate,
                getString(R.string.nova_polaris_sync_polaris_profile) + ": " + polarisProfile.displayMode
            )
        }
    }

    private fun sendNovaProfile() {
        pushNovaProfile(R.string.nova_polaris_sync_saved_to_polaris)
    }

    private fun pushNovaProfile(successMessage: Int, showToast: Boolean = true) {
        val prefs = PreferenceConfiguration.readPreferences(requireContext())
        updatePolarisSettings(
            displayMode = PreferenceConfiguration.formatStreamingDisplayMode(prefs.width, prefs.height, prefs.fps),
            targetBitrateKbps = prefs.bitrate,
            successMessage = successMessage,
            showToast = showToast
        )
    }

    private fun usePolarisProfile() {
        val settings = currentSettings ?: return
        val polarisProfile = PolarisProfileSync.polarisOverrideProfile(settings)
        if (polarisProfile == null ||
            !PreferenceConfiguration.applyPolarisStreamingProfile(
                requireContext(),
                polarisProfile.displayMode,
                polarisProfile.bitrateKbps
            )
        ) {
            Toast.makeText(requireContext(), R.string.nova_polaris_sync_failed, Toast.LENGTH_SHORT).show()
            return
        }
        profileRevision++
        Toast.makeText(requireContext(), R.string.nova_polaris_sync_saved_to_nova, Toast.LENGTH_SHORT).show()
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
        showToast: Boolean = true
    ) {
        val client = apiClient ?: return
        updateBusyState(true)
        lifecycleScope.launch {
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
            updateBusyState(false)
            if (confirmed == null) {
                Toast.makeText(requireContext(), R.string.nova_polaris_sync_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            currentSettings = confirmed
            onSettingsChanged?.invoke(confirmed)
            settingsSync?.refresh()
            if (showToast) {
                Toast.makeText(requireContext(), successMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun maybeAutoSync(settings: PolarisClientSettings) {
        if (busy || !PolarisProfileSync.isAutoSyncEnabled(requireContext(), serverUuid)) {
            return
        }
        val prefs = PreferenceConfiguration.readPreferences(requireContext())
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
        pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova, showToast = false)
    }

    private fun updateBusyState(value: Boolean) {
        busy = value
    }

    private fun expandBottomSheet(bottomSheetDialog: BottomSheetDialog?) {
        bottomSheetDialog ?: return
        val contentView = view ?: return
        NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog,
            contentView,
            widthFraction = 0.62f,
            minLandscapeWidthDp = 700,
            maxLandscapeWidthDp = 980
        )
    }
}
