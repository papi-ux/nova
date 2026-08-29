package com.papi.nova.ui

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.NovaComposeTheme

/**
 * Host-aware Polaris settings surface for the currently selected server.
 *
 * The update discipline lives in [NovaPolarisSyncEngine], shared with Play Setup's
 * Every Game scope — and since the sheet stopped carrying its own rendering, the
 * rows themselves are shared too ([NovaPolarisSyncSheetBody] draws the same
 * buildNovaPlaySetupHostRows output the panel does, mode picker included). What
 * stays here is the sheet itself: chrome, sizing, and the Toast feedback a modal
 * sheet can anchor where a panel cannot.
 */
class NovaPolarisSyncSheet : BottomSheetDialogFragment() {

    private var apiClient: PolarisApiClient? = null
    private var serverName: String = ""
    private var serverUuid: String? = null
    private var initialSettings: PolarisClientSettings? = null
    private var onSettingsChanged: ((PolarisClientSettings) -> Unit)? = null

    private var engine: NovaPolarisSyncEngine? = null

    // Fragment-level so the dialog's B handler can close the picker before the sheet.
    private var pickerOpen by mutableStateOf(false)
    private var explainedRow by mutableStateOf(NovaPlaySetupRow.HOST_DEFAULT_DISPLAY)

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
        val engine = NovaPolarisSyncEngine(
            context = requireContext(),
            apiClient = apiClient,
            serverUuid = serverUuid,
            scope = lifecycleScope,
            onSettingsChanged = { onSettingsChanged?.invoke(it) },
            onMessage = { messageRes, _ ->
                Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
            },
            onTextMessage = { message, _ ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            },
        ).also { this.engine = it }
        engine.start(initialSettings)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NovaComposeTheme {
                    val profileVersion = engine.profileRevision
                    val prefs = PreferenceConfiguration.readPreferences(requireContext())
                    val novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(
                        prefs.width,
                        prefs.height,
                        prefs.fps
                    )
                    val uiState = NovaPolarisSyncUiStateMapper.build(
                        settings = engine.currentSettings,
                        busy = engine.busy,
                        settingsUnavailable = engine.settingsUnavailable,
                        autoSyncEnabled = engine.autoSyncEnabled,
                        hasServerUuid = !serverUuid.isNullOrBlank(),
                        novaDisplayMode = novaDisplayMode,
                        novaBitrateKbps = prefs.bitrate,
                        loadingLabel = getString(R.string.nova_polaris_sync_loading),
                        unavailableLabel = getString(R.string.nova_polaris_sync_unavailable),
                        unsetLabel = getString(R.string.nova_polaris_sync_unset),
                        savedAfterRelaunchLabel = getString(R.string.nova_polaris_sync_status_saved_relaunch),
                        selectedLabel = getString(R.string.nova_polaris_sync_status_selected),
                        activeNowLabel = getString(R.string.nova_polaris_sync_status_active_now),
                        availableLabel = getString(R.string.nova_polaris_sync_status_available)
                    )
                    val actions = NovaPlaySetupHostActions(
                        onSelectMode = { engine.setStreamDisplayMode(it) },
                        onMatchNova = { engine.matchNova() },
                        onSendNova = { engine.sendNova() },
                        onUsePolaris = { engine.usePolarisProfile() },
                        onClearProfile = { engine.clearProfile() },
                        onKeepInStep = { engine.setAutoSync(it) },
                    )
                    val rows = buildNovaPlaySetupHostRows(
                        sync = uiState,
                        polarisProfileValue = novaPlaySetupHostProfileValue(
                            sync = uiState,
                            settings = engine.currentSettings,
                            getString = { resId -> getString(resId) },
                        ),
                        getString = { resId -> getString(resId) },
                        actions = actions,
                    )
                    key(profileVersion) {
                        NovaPolarisSyncSheetBody(
                            serverName = serverName,
                            status = uiState.status,
                            rows = rows,
                            explainedRow = explainedRow,
                            modePicker = if (pickerOpen) {
                                buildHostModePickerState(
                                    modes = uiState.modes,
                                    title = getString(R.string.nova_play_setup_host_default_display),
                                )
                            } else {
                                null
                            },
                            onExplain = { explainedRow = it },
                            onAdvance = { row ->
                                if (row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY &&
                                    novaModePickerEligible(uiState.modes.size)
                                ) {
                                    explainedRow = row
                                    pickerOpen = true
                                } else {
                                    advanceNovaPlaySetupHostRow(
                                        row = row,
                                        rows = rows,
                                        sync = uiState,
                                        actions = actions,
                                    )
                                }
                            },
                            onPickMode = { mode ->
                                pickerOpen = false
                                engine.setStreamDisplayMode(mode)
                            },
                            onClose = { dismiss() },
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
            // Dialogs map BACK out of the box but not a pad's B, so a controller had
            // no way out of this sheet at all: not draggable, no close control, and B
            // swallowed. B now leaves, the same direction it means everywhere else —
            // and with the picker open it backs out of the picker first, exactly as
            // it does in the Play Setup panel.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) {
                    if (pickerOpen) {
                        pickerOpen = false
                    } else {
                        dismiss()
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        view?.post {
            expandBottomSheet(dialog as? BottomSheetDialog)
        }
    }

    override fun onDestroyView() {
        engine?.close()
        engine = null
        super.onDestroyView()
    }

    private fun expandBottomSheet(bottomSheetDialog: BottomSheetDialog?) {
        val sheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val contentView = view ?: return
        NovaSheetChrome.applyBottomSheetChrome(bottomSheetDialog, contentView)
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
                isDraggable = false
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }
}
