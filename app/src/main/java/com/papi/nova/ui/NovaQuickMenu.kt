package com.papi.nova.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
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

/**
 * Stream quick menu with grouped sections for tuning, overlays, controls, and session actions.
 */
class NovaQuickMenu(private val game: Game) : Game.GameMenuCallbacks {

    private enum class ChipTone {
        ACTIVE,
        INACTIVE,
        MUTED,
        WARNING
    }

    private var dialog: BottomSheetDialog? = null

    override fun showMenu(device: GameInputDevice?) {
        if (dialog?.isShowing == true) return

        val sheet = BottomSheetDialog(game, R.style.NovaBottomSheet)
        sheet.setContentView(R.layout.nova_quick_menu)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        fun keys(vararg vk: Int) = ShortArray(vk.size) { vk[it].toShort() }
        fun hapticClick(view: View, action: () -> Unit) {
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }

        // Quick keys
        sheet.findViewById<View>(R.id.qk_esc)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_ESCAPE))
        } }
        sheet.findViewById<View>(R.id.qk_alt_enter)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN))
        } }
        sheet.findViewById<View>(R.id.qk_alt_f4)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4))
        } }
        sheet.findViewById<View>(R.id.qk_f11)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_F11))
        } }
        sheet.findViewById<View>(R.id.qk_super)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_LWIN))
        } }
        sheet.findViewById<View>(R.id.qk_ctrl_v)?.let { hapticClick(it) {
            dismiss()
            sendKeysWithFocus(keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V))
        } }

        val adaptiveRow = sheet.findViewById<View>(R.id.toggle_adaptive_bitrate)
        val aiRow = sheet.findViewById<View>(R.id.toggle_ai_optimizer)
        val mangoRow = sheet.findViewById<View>(R.id.toggle_mangohud)
        val hudRow = sheet.findViewById<View>(R.id.toggle_hud)
        val perfRow = sheet.findViewById<View>(R.id.toggle_perf_stats)
        val mouseRow = sheet.findViewById<View>(R.id.toggle_mouse_mode)
        val oscRow = sheet.findViewById<View>(R.id.toggle_osc)
        val keyboardRow = sheet.findViewById<View>(R.id.toggle_keyboard)
        val pasteRow = sheet.findViewById<View>(R.id.action_paste_clipboard)
        val rotateRow = sheet.findViewById<View>(R.id.action_rotate_screen)
        val moreKeysRow = sheet.findViewById<View>(R.id.action_more_keys)
        val quitRow = sheet.findViewById<TextView>(R.id.action_quit)
        val disconnectRow = sheet.findViewById<View>(R.id.action_disconnect)

        val adaptiveCaption = sheet.findViewById<TextView>(R.id.adaptive_bitrate_caption)
        val adaptiveState = sheet.findViewById<TextView>(R.id.adaptive_bitrate_state)
        val aiCaption = sheet.findViewById<TextView>(R.id.ai_optimizer_caption)
        val aiState = sheet.findViewById<TextView>(R.id.ai_optimizer_state)
        val mangoCaption = sheet.findViewById<TextView>(R.id.mangohud_caption)
        val mangoState = sheet.findViewById<TextView>(R.id.mangohud_state)
        val quickMenuSubtitle = sheet.findViewById<TextView>(R.id.quick_menu_subtitle)
        val sessionModeState = sheet.findViewById<TextView>(R.id.quick_menu_session_mode)
        val hudState = sheet.findViewById<TextView>(R.id.hud_state)
        val perfState = sheet.findViewById<TextView>(R.id.perf_state)
        val oscState = sheet.findViewById<TextView>(R.id.osc_state)
        val keyboardState = sheet.findViewById<TextView>(R.id.keyboard_state)
        val mouseModeState = sheet.findViewById<TextView>(R.id.mouse_mode_label)

        val apiClient = game.novaApiClient ?: getServerAddress()?.let {
            PolarisApiClient(game.applicationContext, it, getHttpsPort())
        }

        var sessionStatus: PolarisSessionStatus? = null
        var capabilities: PolarisCapabilities? = null
        var canAdjustHostTuning = false
        var viewerSession = false
        var adaptiveSupported = false
        var aiSupported = false
        var adaptiveEnabled = false
        var aiEnabled = false
        var mangoHudEnabled = false
        var mangoToggleAllowed = false
        var mangoRiskMessageRes: Int? = null

        fun syncSessionDerivedState() {
            viewerSession = sessionStatus?.isViewer == true
            canAdjustHostTuning = sessionStatus?.canAdjustHostTuning == true
            adaptiveEnabled = sessionStatus?.tuning?.adaptiveBitrateEnabled == true ||
                sessionStatus?.adaptiveBitrateEnabled == true
            aiEnabled = sessionStatus?.tuning?.aiOptimizerEnabled == true ||
                sessionStatus?.aiOptimizerEnabled == true

            val currentGameUuid = sessionStatus?.gameUuid?.takeIf { it.isNotEmpty() } ?: getRunningGameUuid()
            mangoToggleAllowed = canAdjustHostTuning && !currentGameUuid.isNullOrEmpty()
            mangoHudEnabled = sessionStatus?.tuning?.mangohudConfigured == true ||
                sessionStatus?.mangohudConfigured == true
            mangoRiskMessageRes = when {
                sessionStatus?.game?.equals("Steam Big Picture", ignoreCase = true) == true ->
                    R.string.nova_mangohud_warning_big_picture
                else -> null
            }
        }

        fun baseSessionModeLabel(status: PolarisSessionStatus?): String {
            val mode = when {
                status == null -> game.getString(R.string.nova_quick_menu_mode_unknown)
                status.isHeadlessMode -> game.getString(R.string.nova_session_mode_headless)
                status.isVirtualDisplayMode -> game.getString(R.string.nova_session_mode_virtual_display)
                else -> game.getString(R.string.nova_session_mode_host_display)
            }
            val source = when (status?.displayMode?.requested) {
                "auto" -> "Auto"
                "headless", "virtual_display" -> "Explicit"
                else -> ""
            }
            return listOf(mode, source).filter { it.isNotBlank() }.joinToString(" · ")
        }

        fun resolveSessionModeLabel(status: PolarisSessionStatus?): String {
            val mode = baseSessionModeLabel(status)
            return when {
                status == null -> game.getString(R.string.nova_quick_menu_mode_unknown)
                status.isViewer -> game.getString(R.string.nova_session_mode_watch_format, mode)
                status.ownedByClient -> game.getString(R.string.nova_session_mode_owner_format, mode)
                else -> game.getString(R.string.nova_quick_menu_mode_format, mode)
            }
        }

        fun refreshSessionModeState() {
            val label = resolveSessionModeLabel(sessionStatus)
            val tone = when {
                sessionStatus == null -> ChipTone.MUTED
                sessionStatus?.isShuttingDown == true -> ChipTone.WARNING
                sessionStatus?.isViewer == true -> ChipTone.WARNING
                sessionStatus?.isHeadlessMode == true -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(sessionModeState, label, tone)
            quickMenuSubtitle?.text = when {
                sessionStatus?.isShuttingDown == true ->
                    "Host shutdown is in progress. Session actions are temporarily limited."
                sessionStatus?.isHeadlessMode == true ->
                    "Stream controls, tuning, and session actions for headless streaming"
                sessionStatus?.isVirtualDisplayMode == true ->
                    "Stream controls, tuning, and session actions for a virtual display session"
                else -> "Stream controls, tuning, and session actions"
            }
        }

        fun refreshOverlayStates() {
            updateStateChip(
                hudState,
                if (getNovaHud()?.isShowing == true) "On" else "Off",
                if (getNovaHud()?.isShowing == true) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                perfState,
                if (game.prefConfig.enablePerfOverlay) "On" else "Off",
                if (game.prefConfig.enablePerfOverlay) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                oscState,
                if (game.prefConfig.onscreenController) "On" else "Off",
                if (game.prefConfig.onscreenController) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                keyboardState,
                if (game.isKeyboardLayoutVisible() == true) "Shown" else "Hidden",
                if (game.isKeyboardLayoutVisible() == true) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(mouseModeState, game.currentMouseModeLabel, ChipTone.INACTIVE)
        }

        fun refreshTuningStates() {
            syncSessionDerivedState()
            val shutdownInProgress = sessionStatus?.isShuttingDown == true ||
                sessionStatus?.controls?.shutdownInProgress == true

            setRowEnabled(adaptiveRow, adaptiveSupported && canAdjustHostTuning)
            setRowEnabled(aiRow, aiSupported && canAdjustHostTuning)
            setRowEnabled(mangoRow, mangoToggleAllowed)

            val adaptiveTone = when {
                !adaptiveSupported -> ChipTone.MUTED
                adaptiveEnabled -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(
                adaptiveState,
                when {
                    !adaptiveSupported -> "N/A"
                    adaptiveEnabled -> "On"
                    else -> "Off"
                },
                adaptiveTone
            )

            val adaptiveLabel = when {
                !adaptiveSupported -> "server unavailable"
                shutdownInProgress -> "session ending"
                !canAdjustHostTuning && viewerSession -> "owner controls host tuning"
                !canAdjustHostTuning -> "host controls unavailable"
                sessionStatus?.tuning?.adaptiveTargetBitrateKbps ?: 0 > 0 && adaptiveEnabled ->
                    "live · ${(sessionStatus?.tuning?.adaptiveTargetBitrateKbps ?: 0) / 1000} Mbps target"
                else -> "live network response"
            }
            adaptiveCaption?.text = adaptiveLabel

            val aiTone = when {
                !aiSupported -> ChipTone.MUTED
                aiEnabled -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(
                aiState,
                when {
                    !aiSupported -> "N/A"
                    aiEnabled -> "On"
                    else -> "Off"
                },
                aiTone
            )

            aiCaption?.text = when {
                !aiSupported -> "server unavailable"
                shutdownInProgress -> "session ending"
                !canAdjustHostTuning && viewerSession -> "owner controls host tuning"
                !canAdjustHostTuning -> "host controls unavailable"
                else -> "next launch"
            }

            val mangoTone = when {
                !mangoToggleAllowed -> ChipTone.MUTED
                mangoHudEnabled -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(
                mangoState,
                when {
                    !mangoToggleAllowed -> "Locked"
                    mangoHudEnabled -> "Queued"
                    else -> "Off"
                },
                if (mangoRiskMessageRes != null && mangoToggleAllowed && !mangoHudEnabled) ChipTone.WARNING else mangoTone
            )

            mangoCaption?.setText(
                when {
                    shutdownInProgress -> R.string.nova_quick_menu_session_ending_caption
                    !mangoToggleAllowed && viewerSession -> R.string.nova_quick_menu_owner_only_caption
                    !mangoToggleAllowed -> R.string.nova_quick_menu_host_controls_unavailable_caption
                    mangoRiskMessageRes != null -> R.string.nova_mangohud_quick_menu_caption_risky
                    else -> R.string.nova_mangohud_quick_menu_caption_default
                }
            )
            mangoCaption?.setTextColor(
                game.getColor(
                    if (mangoRiskMessageRes != null && mangoToggleAllowed) R.color.nova_warning
                    else R.color.nova_text_muted
                )
            )
        }

        fun refreshInputAvailability() {
            val ownerInputAllowed = !viewerSession
            setRowEnabled(mouseRow, ownerInputAllowed && game.allowChangeMouseMode)
            setRowEnabled(oscRow, ownerInputAllowed)
            setRowEnabled(keyboardRow, ownerInputAllowed)
            setRowEnabled(pasteRow, ownerInputAllowed)
            setRowEnabled(moreKeysRow, ownerInputAllowed)
            setRowEnabled(rotateRow, true)
            setRowEnabled(quitRow, viewerSession || sessionStatus?.canQuit == true)

            quitRow?.text = when {
                viewerSession -> "Leave"
                sessionStatus?.isShuttingDown == true -> "Ending…"
                else -> "End"
            }
        }

        refreshOverlayStates()
        updateStateChip(adaptiveState, "Loading", ChipTone.MUTED)
        updateStateChip(aiState, "Loading", ChipTone.MUTED)
        updateStateChip(mangoState, "Loading", ChipTone.MUTED)
        adaptiveCaption?.text = "checking host state"
        aiCaption?.text = "checking host state"
        mangoCaption?.setText(R.string.nova_mangohud_quick_menu_caption_default)
        refreshSessionModeState()
        refreshInputAvailability()

        hudRow?.let { hapticClick(it) {
            val existingHud = getNovaHud()
            if (existingHud != null && existingHud.isShowing) {
                existingHud.dismiss()
                setNovaHud(null)
            } else {
                if (game.prefConfig.enablePerfOverlay) game.toggleHUD()
                val hud = NovaStreamHud(game)
                hud.setTargetFps(game.configuredHudTargetFps.toDouble())
                setNovaHud(hud)
                hud.show()
            }
            refreshOverlayStates()
        } }

        perfRow?.let { hapticClick(it) {
            val existingHud = getNovaHud()
            if (!game.prefConfig.enablePerfOverlay && existingHud?.isShowing == true) {
                existingHud.dismiss()
                setNovaHud(null)
            }
            game.toggleHUD()
            refreshOverlayStates()
        } }

        if (game.allowChangeMouseMode) {
            mouseRow?.let { hapticClick(it) {
                dismiss()
                game.selectMouseMode(game)
            } }
        }

        oscRow?.let { hapticClick(it) {
            dismiss()
            game.toggleVirtualController()
        } }

        keyboardRow?.let { hapticClick(it) {
            dismiss()
            game.toggleKeyboard()
        } }

        pasteRow?.setOnClickListener {
            dismiss()
            game.sendClipboard(true)
        }

        rotateRow?.apply {
            if (game.isOnExternalDisplay) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    dismiss()
                    game.rotateScreen()
                }
            }
        }

        moreKeysRow?.setOnClickListener {
            dismiss()
            val legacyMenu = com.papi.nova.GameMenu(game)
            legacyMenu.showMenu(device)
        }

        disconnectRow?.setOnClickListener {
            dismiss()
            game.disconnect()
        }

        quitRow?.setOnClickListener {
            if (!viewerSession && sessionStatus?.isShuttingDown == true) {
                Toast.makeText(game, "Host shutdown is already in progress", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!viewerSession && sessionStatus?.canQuit == false) {
                Toast.makeText(game, "Host session controls are unavailable", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismiss()
            if (viewerSession) {
                game.disconnect()
            } else {
                game.quit()
            }
        }

        adaptiveRow?.let { row ->
            hapticClick(row) {
                if (!adaptiveSupported || !canAdjustHostTuning || apiClient == null) return@hapticClick
                val next = !adaptiveEnabled
                adaptiveEnabled = next
                updateStateChip(adaptiveState, if (next) "On" else "Off", if (next) ChipTone.ACTIVE else ChipTone.INACTIVE)
                Thread {
                    val success = apiClient.setAdaptiveBitrateEnabled(next)
                    if (success) {
                        sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                        syncSessionDerivedState()
                    }
                    game.runOnUiThread {
                        if (!success) {
                            adaptiveEnabled = !next
                            refreshTuningStates()
                            Toast.makeText(game, "Adaptive Bitrate toggle failed", Toast.LENGTH_SHORT).show()
                        } else {
                            refreshTuningStates()
                        }
                    }
                }.start()
            }
        }

        aiRow?.let { row ->
            hapticClick(row) {
                if (!aiSupported || !canAdjustHostTuning || apiClient == null) return@hapticClick
                val next = !aiEnabled
                aiEnabled = next
                updateStateChip(aiState, if (next) "On" else "Off", if (next) ChipTone.ACTIVE else ChipTone.INACTIVE)
                Thread {
                    val success = apiClient.setAiOptimizerEnabled(next)
                    if (success) {
                        sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                        syncSessionDerivedState()
                    }
                    game.runOnUiThread {
                        if (!success) {
                            aiEnabled = !next
                            refreshTuningStates()
                            Toast.makeText(game, "AI Optimizer toggle failed", Toast.LENGTH_SHORT).show()
                        } else {
                            refreshTuningStates()
                        }
                    }
                }.start()
            }
        }

        mangoRow?.let { row ->
            hapticClick(row) {
                val currentGameUuid = sessionStatus?.gameUuid?.takeIf { it.isNotEmpty() } ?: getRunningGameUuid()
                if (!mangoToggleAllowed || apiClient == null || currentGameUuid.isNullOrEmpty()) return@hapticClick
                val next = !mangoHudEnabled
                mangoHudEnabled = next
                refreshTuningStates()
                if (next && mangoRiskMessageRes != null) {
                    Toast.makeText(game, mangoRiskMessageRes!!, Toast.LENGTH_LONG).show()
                }
                Thread {
                    val success = apiClient.setMangoHud(currentGameUuid, next)
                    game.runOnUiThread {
                        if (!success) {
                            mangoHudEnabled = !next
                            refreshTuningStates()
                            Toast.makeText(game, "MangoHud toggle failed", Toast.LENGTH_SHORT).show()
                        } else {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                            refreshTuningStates()
                        }
                    }
                }.start()
            }
        }

        if (apiClient != null) {
            Thread {
                try {
                    capabilities = apiClient.getCapabilities()
                    sessionStatus = apiClient.getSessionStatus()

                    adaptiveSupported = capabilities?.features?.adaptiveBitrateControl == true
                    aiSupported = capabilities?.features?.aiOptimizerControl == true
                    syncSessionDerivedState()

                    game.runOnUiThread {
                        refreshSessionModeState()
                        refreshTuningStates()
                        refreshInputAvailability()
                    }
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Quick menu state refresh failed: ${e.message}")
                    game.runOnUiThread {
                        refreshSessionModeState()
                        updateStateChip(adaptiveState, "Unavailable", ChipTone.MUTED)
                        updateStateChip(aiState, "Unavailable", ChipTone.MUTED)
                        updateStateChip(mangoState, "Unavailable", ChipTone.MUTED)
                        adaptiveCaption?.text = "host state unavailable"
                        aiCaption?.text = "host state unavailable"
                        mangoCaption?.text = "host state unavailable"
                    }
                }
            }.start()
        } else {
            refreshSessionModeState()
            updateStateChip(adaptiveState, "N/A", ChipTone.MUTED)
            updateStateChip(aiState, "N/A", ChipTone.MUTED)
            updateStateChip(mangoState, "N/A", ChipTone.MUTED)
            adaptiveCaption?.text = "not a Polaris session"
            aiCaption?.text = "not a Polaris session"
            mangoCaption?.text = "not a Polaris session"
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

    private fun setRowEnabled(view: View?, enabled: Boolean) {
        view?.isEnabled = enabled
        view?.isClickable = enabled
        view?.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateStateChip(chip: TextView?, label: String, tone: ChipTone) {
        chip ?: return
        chip.text = label

        val (textColor, bgColor) = when (tone) {
            ChipTone.ACTIVE -> game.getColor(R.color.nova_ice) to game.getColor(R.color.nova_accent)
            ChipTone.INACTIVE -> game.getColor(R.color.nova_text_secondary) to game.getColor(R.color.nova_badge_bg)
            ChipTone.MUTED -> game.getColor(R.color.nova_text_muted) to game.getColor(R.color.nova_divider)
            ChipTone.WARNING -> game.getColor(R.color.nova_warning) to game.getColor(R.color.nova_divider)
        }

        chip.setTextColor(textColor)
        chip.background?.mutate()?.setTint(bgColor)
    }

    private fun sendKeysWithFocus(keys: ShortArray) {
        game.window.decorView.postDelayed({
            if (!game.isFinishing) {
                game.sendKeys(keys)
            }
        }, KEY_UP_DELAY)
    }

    private fun getNovaHud(): NovaStreamHud? {
        return try {
            val field = game.javaClass.getDeclaredField("novaHud")
            field.isAccessible = true
            field.get(game) as? NovaStreamHud
        } catch (_: Exception) {
            null
        }
    }

    private fun setNovaHud(hud: NovaStreamHud?) {
        try {
            val field = game.javaClass.getDeclaredField("novaHud")
            field.isAccessible = true
            field.set(game, hud)
        } catch (_: Exception) {
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
