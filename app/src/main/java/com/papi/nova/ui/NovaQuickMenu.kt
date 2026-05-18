package com.papi.nova.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.Game
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.DeviceUtils

/**
 * Stream quick menu with grouped sections for tuning, overlays, controls, and session actions.
 */
class NovaQuickMenu(private val game: Game) : Game.GameMenuCallbacks {
    private var dialog: BottomSheetDialog? = null

    override fun showMenu(device: GameInputDevice?) {
        if (dialog?.isShowing == true) return

        val sheet = BottomSheetDialog(game, R.style.NovaBottomSheet)
        val composeView = ComposeView(game)
        sheet.setContentView(composeView)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener { dialog = null }

        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        fun keys(vararg vk: Int) = ShortArray(vk.size) { vk[it].toShort() }
        fun haptic(action: () -> Unit) {
            game.window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        val apiClient = game.novaApiClient ?: getServerAddress()?.let {
            PolarisApiClient(game.applicationContext, it, getHttpsPort())
        }

        var sessionStatus: PolarisSessionStatus? = null
        var capabilities: PolarisCapabilities? = null
        var adaptiveSupported = false
        var aiSupported = false
        var adaptiveEnabled = false
        var aiEnabled = false
        var mangoHudEnabled = false
        var stabilityApplied = false
        var advancedTuningVisible = false
        var profileClearInProgress = false
        var hostStateUnavailable = false

        fun syncSessionDerivedState() {
            adaptiveEnabled = sessionStatus?.tuning?.adaptiveBitrateEnabled == true ||
                sessionStatus?.adaptiveBitrateEnabled == true
            aiEnabled = sessionStatus?.autoQuality?.enabled == true ||
                sessionStatus?.tuning?.aiAutoQualityEnabled == true ||
                sessionStatus?.aiAutoQualityEnabled == true ||
                sessionStatus?.tuning?.aiOptimizerEnabled == true ||
                sessionStatus?.aiOptimizerEnabled == true ||
                adaptiveEnabled
            mangoHudEnabled = sessionStatus?.tuning?.mangohudConfigured == true ||
                sessionStatus?.mangohudConfigured == true
        }

        fun currentProfileGameName(): String? {
            return sessionStatus?.game
                ?.takeIf { it.isNotBlank() }
                ?: getRunningGameName()
        }

        fun currentGameUuid(): String? {
            return sessionStatus?.gameUuid
                ?.takeIf { it.isNotBlank() }
                ?: getRunningGameUuid()
        }

        fun currentProfilePreference(gameName: String?): String {
            val statusPreference = sessionStatus?.profileState?.preference
                ?.takeIf { it.isNotBlank() && it != "auto" }
            return if (gameName.isNullOrBlank()) {
                AutoQualityProfilePreferences.normalize(statusPreference)
            } else if (AutoQualityProfilePreferences.hasSaved(game, gameName)) {
                AutoQualityProfilePreferences.load(game, gameName)
            } else {
                AutoQualityProfilePreferences.normalize(statusPreference)
            }
        }

        fun buildState(): NovaQuickMenuUiState {
            val gameName = currentProfileGameName()
            return NovaQuickMenuUiState.from(
                context = game,
                status = sessionStatus,
                apiAvailable = apiClient != null,
                hostStateUnavailable = hostStateUnavailable,
                adaptiveSupported = adaptiveSupported,
                aiSupported = aiSupported,
                adaptiveEnabled = adaptiveEnabled,
                aiEnabled = aiEnabled,
                mangoHudEnabled = mangoHudEnabled,
                stabilityApplied = stabilityApplied,
                advancedExpanded = advancedTuningVisible,
                profileClearInProgress = profileClearInProgress,
                currentGameName = gameName,
                currentGameUuid = currentGameUuid(),
                profilePreference = currentProfilePreference(gameName),
                hudShowing = game.isNovaHudShowing(),
                perfOverlayEnabled = game.prefConfig.enablePerfOverlay,
                onscreenControllerEnabled = game.prefConfig.onscreenController,
                keyboardVisible = game.isKeyboardLayoutVisible,
                mouseModeLabel = game.currentMouseModeLabel ?: "",
                allowChangeMouseMode = game.allowChangeMouseMode,
                isOnExternalDisplay = game.isOnExternalDisplay,
                fallbackBitrateKbps = game.prefConfig.bitrate,
                fallbackTargetFps = game.configuredHudTargetFps.toDouble()
            )
        }

        var uiState by mutableStateOf(buildState())
        fun refreshState() {
            uiState = buildState()
        }

        fun sendQuickKey(actionId: NovaQuickMenuActionId) {
            val quickKeys = when (actionId) {
                NovaQuickMenuActionId.QUICK_ESC -> keys(KeyboardTranslator.VK_ESCAPE)
                NovaQuickMenuActionId.QUICK_ALT_ENTER -> keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN)
                NovaQuickMenuActionId.QUICK_ALT_F4 -> keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4)
                NovaQuickMenuActionId.QUICK_F11 -> keys(KeyboardTranslator.VK_F11)
                NovaQuickMenuActionId.QUICK_META -> keys(KeyboardTranslator.VK_LWIN)
                NovaQuickMenuActionId.QUICK_CTRL_V -> keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V)
                else -> return
            }
            dismiss()
            sendKeysWithFocus(quickKeys)
        }

        val callbacks = NovaQuickMenuCallbacks(
            onDisconnect = {
                haptic {
                    dismiss()
                    game.disconnect()
                }
            },
            onEndStream = {
                haptic {
                    if (sessionStatus?.isViewer != true && sessionStatus?.isShuttingDown == true) {
                        Toast.makeText(game, R.string.nova_quick_menu_shutdown_already_running, Toast.LENGTH_SHORT).show()
                        return@haptic
                    }
                    if (sessionStatus?.isViewer != true && sessionStatus?.canQuit == false) {
                        Toast.makeText(game, R.string.nova_quick_menu_host_session_unavailable, Toast.LENGTH_SHORT).show()
                        return@haptic
                    }
                    dismiss()
                    if (sessionStatus?.isViewer == true) {
                        game.disconnect()
                    } else {
                        game.quit()
                    }
                }
            },
            onStability = {
                haptic {
                    val status = sessionStatus
                    if (status == null || apiClient == null || !status.canAdjustHostTuning) {
                        return@haptic
                    }

                    val policy = StreamPolicyUiState.from(
                        status,
                        game.prefConfig.bitrate,
                        game.configuredHudTargetFps.toDouble()
                    )
                    val safeBitrate = status.health.safeBitrateKbps
                    val liveBitrate = policy.effectiveBitrateKbps
                    val qualityBlocked = status.autoQuality.isBlocked || status.isHostRenderLimited
                    val shouldLowerBitrate = !qualityBlocked && safeBitrate > 0 && liveBitrate > 0 && safeBitrate < liveBitrate
                    val shouldEnableAutoQuality = !qualityBlocked && !aiEnabled

                    if (!shouldLowerBitrate && !shouldEnableAutoQuality) {
                        if (!qualityBlocked && (status.autoQuality.relaunchRequired || status.health.relaunchRecommended)) {
                            dismiss()
                            Toast.makeText(game, R.string.nova_quick_menu_relaunching_auto_quality, Toast.LENGTH_SHORT).show()
                            game.relaunchStream()
                        }
                        return@haptic
                    }

                    game.launchRuntimeIo("NovaQuickMenuStability") {
                        var success = true
                        if (shouldEnableAutoQuality) {
                            success = apiClient.setAiAutoQualityEnabled(true) && success
                        }
                        if (shouldLowerBitrate) {
                            success = apiClient.setBitrate(safeBitrate) && success
                        }
                        if (success) {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                        }
                        game.runOnMainIfRuntimeActive {
                            if (!success) {
                                stabilityApplied = false
                                Toast.makeText(game, R.string.nova_quick_menu_stability_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                stabilityApplied = true
                                Toast.makeText(
                                    game,
                                    if (status.health.relaunchRecommended) {
                                        game.getString(R.string.nova_quick_menu_live_fallback_applied)
                                    } else {
                                        game.getString(R.string.nova_quick_menu_stability_applied)
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            refreshState()
                        }
                    }
                }
            },
            onSyncStatus = {
                haptic {
                    if (apiClient == null) return@haptic
                    if (sessionStatus?.syncStatus?.needsRelaunch == true) {
                        dismiss()
                        Toast.makeText(game, R.string.nova_quick_menu_relaunching_sync, Toast.LENGTH_SHORT).show()
                        game.relaunchStream()
                        return@haptic
                    }
                    game.launchRuntimeIo("NovaQuickMenuSyncStatus") {
                        sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                        game.runOnMainIfRuntimeActive {
                            syncSessionDerivedState()
                            hostStateUnavailable = false
                            refreshState()
                        }
                    }
                }
            },
            onToggleAdvanced = {
                haptic {
                    advancedTuningVisible = !advancedTuningVisible
                    refreshState()
                }
            },
            onAiAutoQuality = {
                haptic {
                    val status = sessionStatus
                    if (status == null || apiClient == null || (!aiSupported && !adaptiveSupported) || !status.canAdjustHostTuning) {
                        return@haptic
                    }
                    val next = !aiEnabled
                    aiEnabled = next
                    refreshState()
                    game.launchRuntimeIo("NovaQuickMenuAiAutoQuality") {
                        val success = apiClient.setAiAutoQualityEnabled(next)
                        if (success) {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                        }
                        game.runOnMainIfRuntimeActive {
                            if (!success) {
                                aiEnabled = !next
                                Toast.makeText(game, R.string.nova_quick_menu_ai_toggle_failed, Toast.LENGTH_SHORT).show()
                            }
                            refreshState()
                        }
                    }
                }
            },
            onClearGameProfile = {
                haptic {
                    val gameName = currentProfileGameName()
                    val status = sessionStatus
                    if (apiClient == null || gameName.isNullOrBlank() || status?.canAdjustHostTuning != true || profileClearInProgress) {
                        return@haptic
                    }
                    profileClearInProgress = true
                    refreshState()
                    game.launchRuntimeIo("NovaQuickMenuClearProfile") {
                        val cleared = apiClient.clearOptimizerProfile(DeviceUtils.getModel(), gameName)
                        if (cleared == true) {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                        }
                        game.runOnMainIfRuntimeActive {
                            profileClearInProgress = false
                            val message = when (cleared) {
                                true -> R.string.nova_library_reset_game_profile_cleared
                                false -> R.string.nova_library_reset_game_profile_empty
                                null -> R.string.nova_library_reset_game_profile_failed
                            }
                            Toast.makeText(game, message, Toast.LENGTH_SHORT).show()
                            refreshState()
                        }
                    }
                }
            },
            onMangoHud = {
                haptic {
                    val gameUuid = currentGameUuid()
                    val status = sessionStatus
                    if (apiClient == null || status?.canAdjustHostTuning != true || gameUuid.isNullOrEmpty()) {
                        return@haptic
                    }
                    val next = !mangoHudEnabled
                    mangoHudEnabled = next
                    refreshState()
                    if (next && status.game.equals("Steam Big Picture", ignoreCase = true)) {
                        Toast.makeText(game, R.string.nova_mangohud_warning_big_picture, Toast.LENGTH_LONG).show()
                    }
                    game.launchRuntimeIo("NovaQuickMenuMangoHud") {
                        val success = apiClient.setMangoHud(gameUuid, next)
                        if (success) {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                        }
                        game.runOnMainIfRuntimeActive {
                            if (!success) {
                                mangoHudEnabled = !next
                                Toast.makeText(game, R.string.nova_quick_menu_mangohud_failed, Toast.LENGTH_SHORT).show()
                            }
                            refreshState()
                        }
                    }
                }
            },
            onProfilePreference = { preference ->
                haptic {
                    val gameName = currentProfileGameName() ?: return@haptic
                    AutoQualityProfilePreferences.save(game, gameName, preference)
                    Toast.makeText(
                        game,
                        R.string.nova_quick_menu_profile_preference_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshState()
                }
            },
            onQuickKey = { actionId ->
                haptic { sendQuickKey(actionId) }
            },
            onOverlayAction = { actionId ->
                haptic {
                    when (actionId) {
                        NovaQuickMenuActionId.NOVA_HUD -> {
                            game.toggleNovaHud()
                        }
                        NovaQuickMenuActionId.PERF_STATS -> {
                            if (!game.prefConfig.enablePerfOverlay && game.isNovaHudShowing()) {
                                game.dismissNovaHud()
                            }
                            game.toggleHUD()
                        }
                        else -> Unit
                    }
                    refreshState()
                }
            },
            onControlAction = { actionId ->
                haptic {
                    when (actionId) {
                        NovaQuickMenuActionId.MOUSE_MODE -> {
                            if (game.allowChangeMouseMode) {
                                dismiss()
                                game.selectMouseMode(game)
                            }
                        }
                        NovaQuickMenuActionId.CONTROLLER -> {
                            dismiss()
                            game.toggleVirtualController()
                        }
                        NovaQuickMenuActionId.KEYBOARD -> {
                            dismiss()
                            game.toggleFullKeyboard()
                        }
                        else -> Unit
                    }
                }
            },
            onSessionAction = { actionId ->
                haptic {
                    when (actionId) {
                        NovaQuickMenuActionId.PASTE_CLIPBOARD -> {
                            dismiss()
                            game.sendClipboard(true)
                        }
                        NovaQuickMenuActionId.ROTATE_SCREEN -> {
                            dismiss()
                            game.rotateScreen()
                        }
                        NovaQuickMenuActionId.MORE_KEYS -> {
                            dismiss()
                            val legacyMenu = com.papi.nova.GameMenu(game)
                            legacyMenu.showMenu(device)
                        }
                        else -> Unit
                    }
                }
            }
        )

        composeView.setContent {
            NovaComposeTheme {
                NovaQuickMenuContent(
                    state = uiState,
                    callbacks = callbacks
                )
            }
        }

        if (apiClient != null) {
            game.launchRuntimeIo("NovaQuickMenuStateRefresh") {
                try {
                    capabilities = apiClient.getCapabilities()
                    sessionStatus = apiClient.getSessionStatus()

                    val polarisSessionApiAvailable = sessionStatus != null
                    adaptiveSupported = capabilities?.features?.adaptiveBitrateControl == true || polarisSessionApiAvailable
                    aiSupported = capabilities?.features?.aiAutoQualityControl == true ||
                        capabilities?.features?.aiOptimizerControl == true ||
                        polarisSessionApiAvailable
                    hostStateUnavailable = false
                    syncSessionDerivedState()

                    game.runOnMainIfRuntimeActive { refreshState() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Quick menu state refresh failed: ${e.message}")
                    hostStateUnavailable = true
                    game.runOnMainIfRuntimeActive { refreshState() }
                }
            }
        } else {
            refreshState()
        }

        dialog = sheet
        sheet.show()
    }

    override fun hideMenu() {
        dismiss()
    }

    override fun isMenuOpen(): Boolean {
        return dialog?.isShowing == true
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun sendKeysWithFocus(keys: ShortArray) {
        game.window.decorView.postDelayed({
            if (!game.isFinishing) {
                game.sendKeys(keys)
            }
        }, KEY_UP_DELAY)
    }

    private fun getRunningGameName(): String? {
        return try {
            game.intent?.getStringExtra(Game.EXTRA_APP_NAME)
                ?.takeIf { it.isNotBlank() && !it.equals("app", ignoreCase = true) }
                ?: game.intent?.getStringExtra("AppName")
                    ?.takeIf { it.isNotBlank() && !it.equals("app", ignoreCase = true) }
                ?: game.intent?.getStringExtra("appname")
                    ?.takeIf { it.isNotBlank() && !it.equals("app", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun getRunningGameUuid(): String? {
        return try {
            game.intent?.getStringExtra("AppUUID")
                ?: game.intent?.getStringExtra("appuuid")
        } catch (_: Exception) {
            null
        }
    }

    private fun getServerAddress(): String? {
        return try {
            game.intent?.getStringExtra("Host")
                ?: game.intent?.getStringExtra("host")
        } catch (_: Exception) {
            null
        }
    }

    private fun getHttpsPort(): Int {
        return game.intent?.getIntExtra("HttpsPort", 47984) ?: 47984
    }

    companion object {
        private const val KEY_UP_DELAY = 25L
    }
}
