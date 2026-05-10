package com.papi.nova.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.PolarisProfileSync
import com.papi.nova.manager.PolarisSettingsSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.UiHelper
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
    private var currentSettings: PolarisClientSettings? = null
    private var busy = false
    private var settingsUnavailable = false
    private var lastAutoSyncAt = 0L

    private lateinit var statusText: TextView
    private lateinit var desiredModeText: TextView
    private lateinit var effectiveModeText: TextView
    private lateinit var novaProfileText: TextView
    private lateinit var polarisProfileText: TextView
    private lateinit var profileStateText: TextView
    private lateinit var adaptiveSwitch: SwitchMaterial
    private lateinit var aiSwitch: SwitchMaterial
    private lateinit var autoSyncSwitch: SwitchMaterial
    private lateinit var matchNovaButton: MaterialButton
    private lateinit var sendNovaButton: MaterialButton
    private lateinit var usePolarisButton: MaterialButton
    private lateinit var clearProfileButton: MaterialButton
    private lateinit var modeButtons: Map<String, MaterialButton>

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.nova_polaris_sync_sheet, container, false)
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
        val client = apiClient ?: return

        view.findViewById<TextView>(R.id.polaris_sync_host).text = serverName
        statusText = view.findViewById(R.id.polaris_sync_status)
        desiredModeText = view.findViewById(R.id.polaris_sync_desired_mode)
        effectiveModeText = view.findViewById(R.id.polaris_sync_effective_mode)
        novaProfileText = view.findViewById(R.id.polaris_sync_nova_profile)
        polarisProfileText = view.findViewById(R.id.polaris_sync_polaris_profile)
        profileStateText = view.findViewById(R.id.polaris_sync_profile_state)
        adaptiveSwitch = view.findViewById(R.id.polaris_sync_adaptive)
        aiSwitch = view.findViewById(R.id.polaris_sync_ai)
        autoSyncSwitch = view.findViewById(R.id.polaris_sync_auto)
        matchNovaButton = view.findViewById(R.id.polaris_sync_match_nova)
        sendNovaButton = view.findViewById(R.id.polaris_sync_send_nova)
        usePolarisButton = view.findViewById(R.id.polaris_sync_use_polaris)
        clearProfileButton = view.findViewById(R.id.polaris_sync_clear_profile)
        modeButtons = mapOf(
            PolarisClientSettings.MODE_HEADLESS_STREAM to view.findViewById(R.id.polaris_sync_headless),
            PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY to view.findViewById(R.id.polaris_sync_virtual),
            PolarisClientSettings.MODE_DESKTOP_DISPLAY to view.findViewById(R.id.polaris_sync_desktop),
            PolarisClientSettings.MODE_GPU_NATIVE_TEST to view.findViewById(R.id.polaris_sync_gpu)
        )

        if (UiHelper.isTvDevice(requireContext())) {
            (modeButtons.values + listOf(
                matchNovaButton,
                sendNovaButton,
                usePolarisButton,
                clearProfileButton
            )).forEach { UiHelper.applyTvFocusStyle(it) }
        }

        modeButtons.forEach { (mode, button) ->
            button.setOnClickListener { updatePolarisSettings(streamDisplayMode = mode) }
        }
        matchNovaButton.setOnClickListener { pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova) }
        sendNovaButton.setOnClickListener { sendNovaProfile() }
        usePolarisButton.setOnClickListener { usePolarisProfile() }
        clearProfileButton.setOnClickListener {
            updatePolarisSettings(clearDisplayMode = true, clearTargetBitrate = true, successMessage = R.string.nova_polaris_sync_cleared)
        }

        adaptiveSwitch.setOnCheckedChangeListener(toggleListener { checked ->
            updatePolarisSettings(adaptiveBitrateEnabled = checked)
        })
        aiSwitch.setOnCheckedChangeListener(toggleListener { checked ->
            updatePolarisSettings(aiOptimizerEnabled = checked)
        })
        currentSettings = initialSettings
        render(initialSettings)
        requestInitialTvFocus(view)

        settingsSync = PolarisSettingsSyncManager(client) { settings ->
            if (settings != null) {
                settingsUnavailable = false
                currentSettings = settings
                onSettingsChanged?.invoke(settings)
            } else if (currentSettings == null) {
                settingsUnavailable = true
            }
            val renderedSettings = settings ?: currentSettings
            render(renderedSettings)
            settings?.let { maybeAutoSync(it) }
        }.also { it.start(immediate = true) }
    }

    private fun requestInitialTvFocus(root: View) {
        if (!UiHelper.isTvDevice(requireContext())) return

        root.post {
            val modeButton = modeButtons.values.firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }
            val focusTarget = modeButton ?: sendNovaButton.takeIf { it.isEnabled && it.visibility == View.VISIBLE }
            focusTarget?.requestFocus()
        }
    }

    override fun onDestroyView() {
        settingsSync?.close()
        settingsSync = null
        super.onDestroyView()
    }

    private fun render(settings: PolarisClientSettings?) {
        renderProfiles(settings)
        renderModes(settings)
        renderToggles(settings)
        renderAutoSync()
        if (!busy) {
            updateStatus(
                when {
                    settings != null -> R.string.nova_polaris_sync_synced
                    settingsUnavailable -> R.string.nova_polaris_sync_unavailable
                    else -> R.string.nova_polaris_sync_loading
                },
                if (settings == null) ChipTone.MUTED else ChipTone.ACTIVE
            )
        }
    }

    private fun renderProfiles(settings: PolarisClientSettings?) {
        val novaPrefs = PreferenceConfiguration.readPreferences(requireContext())
        val novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(novaPrefs.width, novaPrefs.height, novaPrefs.fps)
        novaProfileText.text = getString(
            R.string.nova_polaris_sync_profile_format,
            getString(R.string.nova_polaris_sync_nova_profile) + ": " + novaDisplayMode,
            novaPrefs.bitrate / 1000
        )

        val polarisProfile = settings?.let { PolarisProfileSync.polarisOverrideProfile(it) }
        polarisProfileText.text = if (polarisProfile == null) {
            getString(R.string.nova_polaris_sync_polaris_profile) + ": " + getString(R.string.nova_polaris_sync_unset)
        } else if (polarisProfile.bitrateKbps > 0) {
            getString(
                R.string.nova_polaris_sync_profile_format,
                getString(R.string.nova_polaris_sync_polaris_profile) + ": " + polarisProfile.displayMode.ifBlank {
                    getString(R.string.nova_polaris_sync_unset)
                },
                polarisProfile.bitrateKbps / 1000
            )
        } else {
            getString(
                R.string.nova_polaris_sync_profile_no_bitrate,
                getString(R.string.nova_polaris_sync_polaris_profile) + ": " + polarisProfile.displayMode
            )
        }

        val profileState = PolarisProfileSync.compare(novaDisplayMode, novaPrefs.bitrate, settings)
        profileStateText.setText(profileStateLabel(profileState))
        profileStateText.setTextColor(profileStateColor(profileState))
        val canUsePolaris = polarisProfile != null
        matchNovaButton.visibility = if (settings != null && profileState != PolarisProfileSync.ProfileState.MATCHED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        matchNovaButton.isEnabled = settings != null && profileState != PolarisProfileSync.ProfileState.MATCHED && !busy
        usePolarisButton.isEnabled = canUsePolaris && !busy
        clearProfileButton.isEnabled = canUsePolaris && !busy
        sendNovaButton.isEnabled = settings != null && !busy
    }

    private fun renderModes(settings: PolarisClientSettings?) {
        val emptyStateLabel = getString(
            if (settingsUnavailable) {
                R.string.nova_polaris_sync_unavailable
            } else {
                R.string.nova_polaris_sync_loading
            }
        )
        val desiredLabel = settings?.desiredModeLabel?.ifBlank { getString(R.string.nova_polaris_sync_unset) }
            ?: emptyStateLabel
        val effectiveLabel = settings?.effectiveModeLabel?.ifBlank { getString(R.string.nova_polaris_sync_unset) }
            ?: emptyStateLabel
        desiredModeText.text = getString(R.string.nova_polaris_sync_desired_format, desiredLabel)
        effectiveModeText.text = getString(R.string.nova_polaris_sync_effective_format, effectiveLabel)

        val selectedMode = settings?.desired?.streamDisplayMode?.takeIf { it.isNotBlank() }
            ?: settings?.effective?.streamDisplayMode.orEmpty()
        val availableModes = settings?.capabilities?.modes
            ?.takeIf { it.isNotEmpty() }
            ?.associateBy { it.value }

        modeButtons.forEach { (mode, button) ->
            val available = availableModes?.get(mode)?.available ?: true
            styleModeButton(button, selectedMode == mode, settings != null && available && !busy)
        }
    }

    private fun renderToggles(settings: PolarisClientSettings?) {
        val adaptiveAvailable = settings?.capabilities?.adaptiveBitrateControl == true
        val aiAvailable = settings?.capabilities?.aiOptimizerControl == true
        setSwitchState(adaptiveSwitch, settings?.effective?.adaptiveBitrateEnabled == true, adaptiveAvailable && !busy) {
            updatePolarisSettings(adaptiveBitrateEnabled = it)
        }
        setSwitchState(aiSwitch, settings?.effective?.aiOptimizerEnabled == true, aiAvailable && !busy) {
            updatePolarisSettings(aiOptimizerEnabled = it)
        }
    }

    private fun renderAutoSync() {
        val enabled = PolarisProfileSync.isAutoSyncEnabled(requireContext(), serverUuid)
        val available = !serverUuid.isNullOrBlank() && currentSettings != null && !busy
        setSwitchState(autoSyncSwitch, enabled, available) { checked ->
            PolarisProfileSync.setAutoSyncEnabled(requireContext(), serverUuid, checked)
            if (checked) {
                currentSettings?.let { maybeAutoSync(it) }
            }
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
            !PreferenceConfiguration.applyPolarisStreamingProfile(requireContext(), polarisProfile.displayMode, polarisProfile.bitrateKbps)) {
            Toast.makeText(requireContext(), R.string.nova_polaris_sync_failed, Toast.LENGTH_SHORT).show()
            return
        }
        renderProfiles(settings)
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
        successMessage: Int = R.string.nova_polaris_sync_saved_to_polaris,
        showToast: Boolean = true
    ) {
        val client = apiClient ?: return
        setBusy(true)
        lifecycleScope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                client.updateClientSettings(
                    streamDisplayMode = streamDisplayMode,
                    displayMode = displayMode,
                    clearDisplayMode = clearDisplayMode,
                    targetBitrateKbps = targetBitrateKbps,
                    clearTargetBitrate = clearTargetBitrate,
                    adaptiveBitrateEnabled = adaptiveBitrateEnabled,
                    aiOptimizerEnabled = aiOptimizerEnabled
                )
            }
            setBusy(false)
            if (confirmed == null) {
                Toast.makeText(requireContext(), R.string.nova_polaris_sync_failed, Toast.LENGTH_SHORT).show()
                render(currentSettings)
                return@launch
            }

            currentSettings = confirmed
            onSettingsChanged?.invoke(confirmed)
            render(confirmed)
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
            profileState != PolarisProfileSync.ProfileState.POLARIS_UNSET) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoSyncAt < PolarisProfileSync.AUTO_SYNC_MIN_INTERVAL_MS) {
            return
        }
        lastAutoSyncAt = now
        pushNovaProfile(R.string.nova_polaris_sync_matched_to_nova, showToast = false)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateStatus(
            if (value) R.string.nova_polaris_sync_syncing else R.string.nova_polaris_sync_synced,
            if (value) ChipTone.WARNING else ChipTone.ACTIVE
        )
        render(currentSettings)
    }

    private fun setSwitchState(
        switch: SwitchMaterial,
        checked: Boolean,
        enabled: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.isEnabled = enabled
        switch.alpha = if (enabled) 1f else 0.45f
        switch.setOnCheckedChangeListener(toggleListener(onChange))
    }

    private fun profileStateLabel(state: PolarisProfileSync.ProfileState): Int {
        return when (state) {
            PolarisProfileSync.ProfileState.UNAVAILABLE -> R.string.nova_polaris_sync_profile_unavailable
            PolarisProfileSync.ProfileState.POLARIS_UNSET -> R.string.nova_polaris_sync_profile_unset
            PolarisProfileSync.ProfileState.MATCHED -> R.string.nova_polaris_sync_profile_matched
            PolarisProfileSync.ProfileState.DIFFERENT -> R.string.nova_polaris_sync_profile_different
        }
    }

    private fun profileStateColor(state: PolarisProfileSync.ProfileState): Int {
        return ContextCompat.getColor(
            requireContext(),
            when (state) {
                PolarisProfileSync.ProfileState.MATCHED -> R.color.nova_success
                PolarisProfileSync.ProfileState.DIFFERENT,
                PolarisProfileSync.ProfileState.POLARIS_UNSET -> R.color.nova_warning
                PolarisProfileSync.ProfileState.UNAVAILABLE -> R.color.nova_text_muted
            }
        )
    }

    private fun toggleListener(onChange: (Boolean) -> Unit): CompoundButton.OnCheckedChangeListener {
        return CompoundButton.OnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun styleModeButton(button: MaterialButton, selected: Boolean, enabled: Boolean) {
        val background = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.nova_accent else R.color.nova_badge_bg
        )
        val text = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.nova_ice else R.color.nova_text_primary
        )
        val stroke = ContextCompat.getColor(requireContext(), R.color.nova_divider)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.setTextColor(text)
        button.strokeColor = ColorStateList.valueOf(stroke)
        button.strokeWidth = if (selected) 0 else 2
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateStatus(labelRes: Int, tone: ChipTone) {
        statusText.text = getString(labelRes)
        val (textColor, bgColor) = when (tone) {
            ChipTone.ACTIVE -> ContextCompat.getColor(requireContext(), R.color.nova_ice) to ContextCompat.getColor(requireContext(), R.color.nova_accent)
            ChipTone.MUTED -> ContextCompat.getColor(requireContext(), R.color.nova_text_muted) to ContextCompat.getColor(requireContext(), R.color.nova_divider)
            ChipTone.WARNING -> ContextCompat.getColor(requireContext(), R.color.nova_warning) to ContextCompat.getColor(requireContext(), R.color.nova_divider)
        }
        statusText.setTextColor(textColor)
        statusText.backgroundTintList = ColorStateList.valueOf(bgColor)
    }

    private fun expandBottomSheet(bottomSheetDialog: BottomSheetDialog?) {
        val sheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val contentView = view ?: return
        sheet.setBackgroundResource(
            if (NovaThemeManager.isOled(requireContext())) {
                R.drawable.nova_sheet_bg_oled
            } else {
                R.drawable.nova_sheet_bg
            }
        )
        contentView.post {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val maxHeightRatio = if (isLandscape) 0.96f else 0.90f
            val maxHeight = (resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            val contentHeight = contentView.measuredHeight.takeIf { it > 0 } ?: return@post
            val desiredHeight = contentHeight.coerceAtMost(maxHeight)
            val displayWidth = resources.displayMetrics.widthPixels
            val density = resources.displayMetrics.density
            val desiredWidth = if (isLandscape) {
                val minWidth = (700 * density).toInt()
                val maxWidth = (980 * density).toInt()
                (displayWidth * 0.62f).toInt().coerceIn(minWidth, maxWidth)
            } else {
                displayWidth
            }
            val horizontalMargin = if (isLandscape) {
                ((displayWidth - desiredWidth) / 2).coerceAtLeast((18 * density).toInt())
            } else {
                0
            }

            sheet.layoutParams = sheet.layoutParams.apply {
                width = if (isLandscape) desiredWidth else ViewGroup.LayoutParams.MATCH_PARENT
                height = desiredHeight
            }
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = horizontalMargin
                lp.marginEnd = horizontalMargin
                sheet.layoutParams = lp
            }
            sheet.setPadding(0, 0, 0, 0)
            sheet.requestLayout()
            BottomSheetBehavior.from(sheet).apply {
                peekHeight = desiredHeight
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    private enum class ChipTone {
        ACTIVE,
        MUTED,
        WARNING
    }
}
