package com.papi.nova.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
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
import com.papi.nova.utils.DeviceUtils

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

        val stabilityRow = sheet.findViewById<View>(R.id.action_stability_mode)
        val bidirectionalSyncRow = sheet.findViewById<View>(R.id.action_bidirectional_sync)
        val advancedTuningRow = sheet.findViewById<View>(R.id.action_advanced_tuning)
        val advancedTuningGroup = sheet.findViewById<View>(R.id.advanced_tuning_group)
        val aiRow = sheet.findViewById<View>(R.id.toggle_ai_optimizer)
        val clearGameProfileRow = sheet.findViewById<View>(R.id.action_clear_game_profile)
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

        val stabilityCaption = sheet.findViewById<TextView>(R.id.stability_mode_caption)
        val stabilityState = sheet.findViewById<TextView>(R.id.stability_mode_state)
        val autoQualityTargetSummary = sheet.findViewById<TextView>(R.id.auto_quality_target_summary)
        val profilePreferenceCaption = sheet.findViewById<TextView>(R.id.profile_preference_caption)
        val profilePreferenceButtons = mapOf(
            "auto" to sheet.findViewById<MaterialButton>(R.id.profile_pref_auto),
            "quality" to sheet.findViewById<MaterialButton>(R.id.profile_pref_quality),
            "high_fps" to sheet.findViewById<MaterialButton>(R.id.profile_pref_high_fps),
            "stability" to sheet.findViewById<MaterialButton>(R.id.profile_pref_stability)
        )
        val bidirectionalSyncCaption = sheet.findViewById<TextView>(R.id.bidirectional_sync_caption)
        val bidirectionalSyncState = sheet.findViewById<TextView>(R.id.bidirectional_sync_state)
        val advancedTuningState = sheet.findViewById<TextView>(R.id.advanced_tuning_state)
        val aiCaption = sheet.findViewById<TextView>(R.id.ai_optimizer_caption)
        val aiState = sheet.findViewById<TextView>(R.id.ai_optimizer_state)
        val clearProfileCaption = sheet.findViewById<TextView>(R.id.clear_game_profile_caption)
        val clearProfileState = sheet.findViewById<TextView>(R.id.clear_game_profile_state)
        val mangoCaption = sheet.findViewById<TextView>(R.id.mangohud_caption)
        val mangoState = sheet.findViewById<TextView>(R.id.mangohud_state)
        val quickMenuSubtitle = sheet.findViewById<TextView>(R.id.quick_menu_subtitle)
        val sessionModeState = sheet.findViewById<TextView>(R.id.quick_menu_session_mode)
        val healthSummary = sheet.findViewById<TextView>(R.id.quick_menu_health_summary)
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
        var stabilityApplied = false
        var advancedTuningVisible = false
        var profileClearInProgress = false

        fun syncSessionDerivedState() {
            viewerSession = sessionStatus?.isViewer == true
            canAdjustHostTuning = sessionStatus?.canAdjustHostTuning == true
            adaptiveEnabled = sessionStatus?.tuning?.adaptiveBitrateEnabled == true ||
                sessionStatus?.adaptiveBitrateEnabled == true
            aiEnabled = sessionStatus?.autoQuality?.enabled == true ||
                sessionStatus?.tuning?.aiAutoQualityEnabled == true ||
                sessionStatus?.aiAutoQualityEnabled == true ||
                sessionStatus?.tuning?.aiOptimizerEnabled == true ||
                sessionStatus?.aiOptimizerEnabled == true ||
                adaptiveEnabled

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

        fun optimizationRuntimeCaption(status: PolarisSessionStatus?): String? {
            val source = status?.optimizationSourceLabel?.takeIf { it.isNotBlank() } ?: return null
            val confidence = status.optimizationConfidenceLabel
                .takeIf { it.isNotBlank() && !status.encoder.optimizationSource.equals("device_db", ignoreCase = true) }
                ?.lowercase()
            val normalization = status.optimizationNormalizedLabel.takeIf { it.isNotBlank() }
            val freshness = when (status.encoder.optimizationCacheStatus.lowercase()) {
                "hit" -> game.getString(R.string.nova_optimization_cached)
                "invalidated" -> game.getString(R.string.nova_optimization_recovery)
                "miss" -> game.getString(R.string.nova_optimization_fresh)
                else -> ""
            }
            return listOfNotNull(
                source,
                confidence,
                freshness.takeIf {
                    it.isNotBlank() &&
                        !status.encoder.optimizationSource.equals("device_db", ignoreCase = true) &&
                        it != normalization
                },
                normalization
            ).joinToString(" · ")
        }

        fun streamPolicy(status: PolarisSessionStatus?): StreamPolicyUiState {
            return StreamPolicyUiState.from(
                status,
                game.prefConfig.bitrate,
                game.configuredHudTargetFps.toDouble()
            )
        }

        fun currentSessionBitrate(status: PolarisSessionStatus?): Int {
            return streamPolicy(status).effectiveBitrateKbps
        }

        fun currentProfileGameName(): String? {
            return sessionStatus?.game
                ?.takeIf { it.isNotBlank() }
                ?: getRunningGameName()
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

        fun compactGameName(gameName: String): String {
            return if (gameName.length <= 28) {
                gameName
            } else {
                gameName.take(25).trimEnd() + "..."
            }
        }

        fun healthSummaryText(status: PolarisSessionStatus?): String {
            return when {
                status == null -> game.getString(R.string.nova_quick_menu_health_checking)
                status.health.summary.isNotBlank() -> status.health.summary
                status.isHostRenderLimited -> game.getString(R.string.nova_quick_menu_health_host_render)
                status.hasHealthConcerns -> game.getString(R.string.nova_quick_menu_health_attention)
                else -> game.getString(R.string.nova_quick_menu_health_steady)
            }
        }

        fun autoQualityTone(tone: AutoQualityUiState.Tone): ChipTone {
            return when (tone) {
                AutoQualityUiState.Tone.STABLE -> ChipTone.ACTIVE
                AutoQualityUiState.Tone.INFO -> ChipTone.ACTIVE
                AutoQualityUiState.Tone.WARNING -> ChipTone.WARNING
                AutoQualityUiState.Tone.DANGER -> ChipTone.WARNING
                AutoQualityUiState.Tone.MUTED -> ChipTone.MUTED
            }
        }

        fun refreshAdvancedTuningState() {
            advancedTuningGroup?.visibility = if (advancedTuningVisible) View.VISIBLE else View.GONE
            updateStateChip(
                advancedTuningState,
                if (advancedTuningVisible) game.getString(R.string.nova_quick_menu_hide) else game.getString(R.string.nova_quick_menu_show),
                if (advancedTuningVisible) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
        }

        fun refreshProfilePreferenceState() {
            val gameName = currentProfileGameName()
            val preference = currentProfilePreference(gameName)
            val buttonsEnabled = !gameName.isNullOrBlank()

            profilePreferenceCaption?.text = when {
                gameName.isNullOrBlank() -> game.getString(R.string.nova_quick_menu_profile_preference_checking)
                else -> game.getString(
                    R.string.nova_quick_menu_profile_preference_next_launch,
                    compactGameName(gameName)
                )
            }

            profilePreferenceButtons.forEach { (buttonPreference, button) ->
                updatePreferenceButton(button, buttonPreference == preference, buttonsEnabled)
            }
        }

        fun refreshClearProfileState() {
            val gameName = currentProfileGameName()
            val shutdownInProgress = sessionStatus?.isShuttingDown == true ||
                sessionStatus?.controls?.shutdownInProgress == true
            val enabled = apiClient != null &&
                !gameName.isNullOrBlank() &&
                canAdjustHostTuning &&
                !shutdownInProgress &&
                !profileClearInProgress

            setRowEnabled(clearGameProfileRow, enabled)
            clearProfileCaption?.text = when {
                gameName.isNullOrBlank() -> game.getString(R.string.nova_quick_menu_clear_game_profile_unavailable)
                !canAdjustHostTuning && viewerSession -> game.getString(R.string.nova_quick_menu_owner_only_caption)
                !canAdjustHostTuning -> game.getString(R.string.nova_quick_menu_host_controls_unavailable_caption)
                else -> game.getString(
                    R.string.nova_quick_menu_clear_game_profile_for_game,
                    compactGameName(gameName)
                )
            }
            updateStateChip(
                clearProfileState,
                when {
                    profileClearInProgress -> game.getString(R.string.nova_quick_menu_working)
                    !enabled -> game.getString(R.string.nova_quick_menu_locked)
                    else -> game.getString(R.string.nova_quick_menu_clear)
                },
                when {
                    profileClearInProgress -> ChipTone.WARNING
                    enabled -> ChipTone.INACTIVE
                    else -> ChipTone.MUTED
                }
            )
        }

        fun refreshHealthSummary() {
            val tone = when {
                sessionStatus?.health?.grade.equals("degraded", ignoreCase = true) -> R.color.nova_warning
                sessionStatus?.health?.grade.equals("watch", ignoreCase = true) -> R.color.nova_accent
                else -> R.color.nova_text_muted
            }
            healthSummary?.text = healthSummaryText(sessionStatus)
            healthSummary?.setTextColor(ContextCompat.getColor(game, tone))
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
                    game.getString(R.string.nova_quick_menu_shutdown_subtitle)
                sessionStatus?.isHeadlessMode == true ->
                    game.getString(R.string.nova_quick_menu_headless_subtitle)
                sessionStatus?.isVirtualDisplayMode == true ->
                    game.getString(R.string.nova_quick_menu_virtual_subtitle)
                else -> game.getString(R.string.nova_quick_menu_command_center_subtitle)
            }
        }

        fun refreshStabilityState() {
            val status = sessionStatus
            val safeBitrate = status?.health?.safeBitrateKbps ?: 0
            val policy = streamPolicy(status)
            val liveBitrate = currentSessionBitrate(status)
            val autoQuality = AutoQualityUiState.from(status)
            val autoPolicy = status?.autoQuality
            val qualityBlocked = autoPolicy?.isBlocked == true || status?.isHostRenderLimited == true
            val upgradeAvailable = autoPolicy?.let { it.isUpgradeAvailable && it.relaunchRequired } == true
            val canLowerBitrate = !qualityBlocked && safeBitrate > 0 && liveBitrate > 0 && safeBitrate < liveBitrate
            val canEnableAdaptive = !qualityBlocked && status?.canAdjustHostTuning == true && !aiEnabled
            val relaunchOnly = !qualityBlocked &&
                (upgradeAvailable || status?.health?.relaunchRecommended == true) &&
                !canLowerBitrate &&
                !canEnableAdaptive
            val rowEnabled = status?.canAdjustHostTuning == true && (canLowerBitrate || canEnableAdaptive || relaunchOnly)

            stabilityRow?.isEnabled = rowEnabled
            stabilityRow?.isClickable = rowEnabled
            stabilityRow?.alpha = 1f
            stabilityCaption?.text = when {
                status == null -> game.getString(R.string.nova_quick_menu_health_checking)
                else -> autoQuality.detail
            }
            autoQualityTargetSummary?.text = policy.targetSummary
                .takeIf { it.isNotBlank() }
                ?: game.getString(R.string.nova_quick_menu_target_checking)

            when {
                status == null -> updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_loading), ChipTone.MUTED)
                !rowEnabled && viewerSession -> updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_owner), ChipTone.MUTED)
                !rowEnabled -> updateStateChip(stabilityState, autoQuality.label, autoQualityTone(autoQuality.tone))
                stabilityApplied -> updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_done), ChipTone.ACTIVE)
                relaunchOnly -> updateStateChip(stabilityState, "Relaunch", ChipTone.WARNING)
                else -> updateStateChip(
                    stabilityState,
                    autoQuality.label,
                    autoQualityTone(autoQuality.tone)
                )
            }
        }

        fun refreshBidirectionalSyncState() {
            val status = sessionStatus
            val presentationStatus = status?.clientPresentation?.status.orEmpty().lowercase()
            val syncStatus = status?.syncStatus
            val syncState = syncStatus?.state.orEmpty().lowercase()
            val policy = streamPolicy(status)
            val syncLabel = when {
                status == null -> "Checking"
                syncStatus?.isManualOverride == true -> "Manual"
                syncStatus?.needsRelaunch == true -> "Relaunch"
                syncStatus?.isFailed == true -> "Attention"
                syncStatus?.isApplying == true -> "Applying"
                presentationStatus == "blocked" -> "Blocked"
                presentationStatus == "pending" -> "Pending"
                policy.adaptiveTargetBitrateKbps > 0 -> "Auto Quality"
                syncStatus?.isSynced == true -> "Synced"
                status.isClientPresentationSynced -> "Synced"
                status.isStreaming -> "Live"
                else -> "Ready"
            }
            val tone = when (syncLabel) {
                "Synced", "Auto Quality", "Live" -> ChipTone.ACTIVE
                "Pending", "Blocked", "Relaunch", "Attention", "Applying", "Manual" -> ChipTone.WARNING
                "Ready" -> ChipTone.INACTIVE
                else -> ChipTone.MUTED
            }

            updateStateChip(bidirectionalSyncState, syncLabel, tone)
            bidirectionalSyncCaption?.text = when {
                status == null -> "checking host and client settings"
                policy.adaptiveTargetBitrateKbps > 0 -> policy.statusCaption
                syncStatus?.message?.isNotBlank() == true -> syncStatus.message
                syncState == "manual_override" -> "manual client tuning is active"
                syncState == "needs_relaunch" -> "saved settings apply on next launch"
                syncState == "applying" -> "Nova is reporting applied stream settings"
                presentationStatus == "blocked" -> "client could not apply the requested display sync"
                presentationStatus == "pending" -> "waiting for Nova to report display sync"
                status.isClientPresentationSynced && status.clientPresentation.appliedRefreshRateHz > 0.0 ->
                    "Retroid display ${status.clientPresentation.appliedRefreshRateHz.toInt()} Hz matches stream"
                else -> "host and client settings"
            }
        }

        fun refreshOverlayStates() {
            updateStateChip(
                hudState,
                if (getNovaHud()?.isShowing == true) game.getString(R.string.nova_quick_menu_on) else game.getString(R.string.nova_quick_menu_off),
                if (getNovaHud()?.isShowing == true) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                perfState,
                if (game.prefConfig.enablePerfOverlay) game.getString(R.string.nova_quick_menu_on) else game.getString(R.string.nova_quick_menu_off),
                if (game.prefConfig.enablePerfOverlay) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                oscState,
                if (game.prefConfig.onscreenController) game.getString(R.string.nova_quick_menu_on) else game.getString(R.string.nova_quick_menu_off),
                if (game.prefConfig.onscreenController) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(
                keyboardState,
                if (game.isKeyboardLayoutVisible() == true) "Shown" else game.getString(R.string.nova_quick_menu_hidden),
                if (game.isKeyboardLayoutVisible() == true) ChipTone.ACTIVE else ChipTone.INACTIVE
            )
            updateStateChip(mouseModeState, game.currentMouseModeLabel, ChipTone.INACTIVE)
        }

        fun refreshTuningStates() {
            syncSessionDerivedState()
            val shutdownInProgress = sessionStatus?.isShuttingDown == true ||
                sessionStatus?.controls?.shutdownInProgress == true

            setRowEnabled(aiRow, (aiSupported || adaptiveSupported) && canAdjustHostTuning)
            setRowEnabled(mangoRow, mangoToggleAllowed)
            refreshProfilePreferenceState()
            refreshClearProfileState()

            val aiTone = when {
                !aiSupported && !adaptiveSupported -> ChipTone.MUTED
                aiEnabled -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(
                aiState,
                when {
                    !aiSupported && !adaptiveSupported -> game.getString(R.string.nova_quick_menu_not_available)
                    aiEnabled -> game.getString(R.string.nova_quick_menu_on)
                    else -> game.getString(R.string.nova_quick_menu_off)
                },
                aiTone
            )

            val policy = streamPolicy(sessionStatus)
            val autoQuality = AutoQualityUiState.from(sessionStatus)
            aiCaption?.text = when {
                !aiSupported && !adaptiveSupported -> "server unavailable"
                shutdownInProgress -> game.getString(R.string.nova_quick_menu_session_ending_caption)
                !canAdjustHostTuning && viewerSession -> game.getString(R.string.nova_quick_menu_owner_only_caption)
                !canAdjustHostTuning -> game.getString(R.string.nova_quick_menu_host_controls_unavailable_caption)
                aiEnabled && policy.adaptiveTargetBitrateKbps > 0 ->
                    "${autoQuality.detail} · ${policy.adaptiveTargetLabel} live bitrate"
                aiEnabled -> autoQuality.detail.ifBlank {
                    optimizationRuntimeCaption(sessionStatus) ?: game.getString(R.string.nova_quick_menu_ai_caption_default)
                }
                else -> "manual stream tuning"
            }

            val mangoTone = when {
                !mangoToggleAllowed -> ChipTone.MUTED
                mangoHudEnabled -> ChipTone.ACTIVE
                else -> ChipTone.INACTIVE
            }
            updateStateChip(
                mangoState,
                when {
                    !mangoToggleAllowed -> game.getString(R.string.nova_quick_menu_locked)
                    mangoHudEnabled -> game.getString(R.string.nova_quick_menu_queued)
                    else -> game.getString(R.string.nova_quick_menu_off)
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
                ContextCompat.getColor(
                    game,
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
                viewerSession -> game.getString(R.string.nova_quick_menu_leave)
                sessionStatus?.isShuttingDown == true -> game.getString(R.string.nova_quick_menu_ending)
                else -> game.getString(R.string.nova_quick_menu_end_stream)
            }
        }

        refreshOverlayStates()
        refreshHealthSummary()
        updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_loading), ChipTone.MUTED)
        updateStateChip(bidirectionalSyncState, game.getString(R.string.nova_quick_menu_checking), ChipTone.MUTED)
        updateStateChip(aiState, game.getString(R.string.nova_quick_menu_loading), ChipTone.MUTED)
        updateStateChip(mangoState, game.getString(R.string.nova_quick_menu_loading), ChipTone.MUTED)
        updateStateChip(clearProfileState, game.getString(R.string.nova_quick_menu_loading), ChipTone.MUTED)
        refreshAdvancedTuningState()
        refreshProfilePreferenceState()
        refreshClearProfileState()
        stabilityCaption?.text = game.getString(R.string.nova_quick_menu_health_checking)
        autoQualityTargetSummary?.text = game.getString(R.string.nova_quick_menu_target_checking)
        bidirectionalSyncCaption?.text = "checking host and client settings"
        aiCaption?.text = game.getString(R.string.nova_quick_menu_health_checking)
        mangoCaption?.setText(R.string.nova_mangohud_quick_menu_caption_default)
        refreshSessionModeState()
        refreshStabilityState()
        refreshBidirectionalSyncState()
        refreshInputAvailability()

        advancedTuningRow?.let { row ->
            hapticClick(row) {
                advancedTuningVisible = !advancedTuningVisible
                refreshAdvancedTuningState()
            }
        }

        profilePreferenceButtons.forEach { (preference, button) ->
            button?.let {
                hapticClick(it) {
                    val gameName = currentProfileGameName() ?: return@hapticClick
                    AutoQualityProfilePreferences.save(game, gameName, preference)
                    refreshProfilePreferenceState()
                    Toast.makeText(
                        game,
                        R.string.nova_quick_menu_profile_preference_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        hudRow?.let { hapticClick(it) {
            val existingHud = getNovaHud()
            if (existingHud != null && existingHud.isShowing) {
                existingHud.dismiss()
                setNovaHud(null)
            } else {
                if (game.prefConfig.enablePerfOverlay) game.toggleHUD()
                val hud = NovaStreamHud(game)
                hud.setTargetFps(game.configuredHudTargetFps.toDouble())
                sessionStatus?.let { hud.applySessionStatus(it) }
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
                Toast.makeText(game, R.string.nova_quick_menu_shutdown_already_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!viewerSession && sessionStatus?.canQuit == false) {
                Toast.makeText(game, R.string.nova_quick_menu_host_session_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismiss()
            if (viewerSession) {
                game.disconnect()
            } else {
                game.quit()
            }
        }

        stabilityRow?.let { row ->
            hapticClick(row) {
                val status = sessionStatus
                if (status == null || apiClient == null || !status.canAdjustHostTuning) {
                    return@hapticClick
                }

                val safeBitrate = status.health.safeBitrateKbps
                val liveBitrate = currentSessionBitrate(status)
                val qualityBlocked = status.autoQuality.isBlocked || status.isHostRenderLimited
                val shouldLowerBitrate = !qualityBlocked && safeBitrate > 0 && liveBitrate > 0 && safeBitrate < liveBitrate
                val shouldEnableAutoQuality = !qualityBlocked && !aiEnabled

                if (!shouldLowerBitrate && !shouldEnableAutoQuality) {
                    if (!qualityBlocked && (status.autoQuality.relaunchRequired || status.health.relaunchRecommended)) {
                        dismiss()
                        Toast.makeText(game, R.string.nova_quick_menu_relaunching_auto_quality, Toast.LENGTH_SHORT).show()
                        game.relaunchStream()
                    }
                    return@hapticClick
                }

                updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_working), ChipTone.WARNING)

                Thread {
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
                    game.runOnUiThread {
                        if (!success) {
                            stabilityApplied = false
                            refreshStabilityState()
                            Toast.makeText(game, R.string.nova_quick_menu_stability_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            stabilityApplied = true
                            refreshHealthSummary()
                            refreshTuningStates()
                            refreshStabilityState()
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
                    }
                }.start()
            }
        }

        bidirectionalSyncRow?.let { row ->
            hapticClick(row) {
                if (apiClient == null) return@hapticClick
                if (sessionStatus?.syncStatus?.needsRelaunch == true) {
                    dismiss()
                    Toast.makeText(game, R.string.nova_quick_menu_relaunching_sync, Toast.LENGTH_SHORT).show()
                    game.relaunchStream()
                    return@hapticClick
                }
                updateStateChip(bidirectionalSyncState, game.getString(R.string.nova_quick_menu_checking), ChipTone.MUTED)
                Thread {
                    sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                    game.runOnUiThread {
                        refreshSessionModeState()
                        refreshHealthSummary()
                        refreshBidirectionalSyncState()
                        refreshTuningStates()
                        refreshStabilityState()
                    }
                }.start()
            }
        }

        aiRow?.let { row ->
            hapticClick(row) {
                if ((!aiSupported && !adaptiveSupported) || !canAdjustHostTuning || apiClient == null) return@hapticClick
                val next = !aiEnabled
                aiEnabled = next
                updateStateChip(
                    aiState,
                    if (next) game.getString(R.string.nova_quick_menu_on) else game.getString(R.string.nova_quick_menu_off),
                    if (next) ChipTone.ACTIVE else ChipTone.INACTIVE
                )
                Thread {
                    val success = apiClient.setAiAutoQualityEnabled(next)
                    if (success) {
                        sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                        syncSessionDerivedState()
                    }
                    game.runOnUiThread {
                        if (!success) {
                            aiEnabled = !next
                            refreshTuningStates()
                            refreshStabilityState()
                            Toast.makeText(game, R.string.nova_quick_menu_ai_toggle_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            refreshTuningStates()
                            refreshHealthSummary()
                            refreshStabilityState()
                        }
                    }
                }.start()
            }
        }

        clearGameProfileRow?.let { row ->
            hapticClick(row) {
                val gameName = currentProfileGameName()
                if (apiClient == null || gameName.isNullOrBlank() || !canAdjustHostTuning || profileClearInProgress) {
                    return@hapticClick
                }
                profileClearInProgress = true
                refreshClearProfileState()
                Thread {
                    val cleared = apiClient.clearOptimizerProfile(DeviceUtils.getModel(), gameName)
                    if (cleared == true) {
                        sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                        syncSessionDerivedState()
                    }
                    game.runOnUiThread {
                        profileClearInProgress = false
                        refreshSessionModeState()
                        refreshHealthSummary()
                        refreshBidirectionalSyncState()
                        refreshTuningStates()
                        refreshStabilityState()
                        val message = when (cleared) {
                            true -> R.string.nova_library_reset_game_profile_cleared
                            false -> R.string.nova_library_reset_game_profile_empty
                            null -> R.string.nova_library_reset_game_profile_failed
                        }
                        updateStateChip(
                            clearProfileState,
                            if (cleared == true) {
                                game.getString(R.string.nova_quick_menu_done)
                            } else {
                                game.getString(R.string.nova_quick_menu_clear)
                            },
                            if (cleared == true) ChipTone.ACTIVE else ChipTone.WARNING
                        )
                        Toast.makeText(game, message, Toast.LENGTH_SHORT).show()
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
                            refreshStabilityState()
                            Toast.makeText(game, R.string.nova_quick_menu_mangohud_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            sessionStatus = apiClient.getSessionStatus() ?: sessionStatus
                            syncSessionDerivedState()
                            refreshTuningStates()
                            refreshHealthSummary()
                            refreshStabilityState()
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

                    val polarisSessionApiAvailable = sessionStatus != null
                    adaptiveSupported = capabilities?.features?.adaptiveBitrateControl == true || polarisSessionApiAvailable
                    aiSupported = capabilities?.features?.aiAutoQualityControl == true ||
                        capabilities?.features?.aiOptimizerControl == true ||
                        polarisSessionApiAvailable
                    syncSessionDerivedState()

                    game.runOnUiThread {
                        refreshSessionModeState()
                        refreshHealthSummary()
                        refreshBidirectionalSyncState()
                        refreshTuningStates()
                        refreshStabilityState()
                        refreshInputAvailability()
                    }
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Quick menu state refresh failed: ${e.message}")
                    game.runOnUiThread {
                        refreshSessionModeState()
                        refreshHealthSummary()
                        refreshBidirectionalSyncState()
                        updateStateChip(aiState, game.getString(R.string.nova_quick_menu_unavailable), ChipTone.MUTED)
                        updateStateChip(mangoState, game.getString(R.string.nova_quick_menu_unavailable), ChipTone.MUTED)
                        updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_unavailable), ChipTone.MUTED)
                        updateStateChip(bidirectionalSyncState, game.getString(R.string.nova_quick_menu_unavailable), ChipTone.MUTED)
                        updateStateChip(clearProfileState, game.getString(R.string.nova_quick_menu_unavailable), ChipTone.MUTED)
                        aiCaption?.text = game.getString(R.string.nova_quick_menu_host_state_unavailable)
                        mangoCaption?.text = game.getString(R.string.nova_quick_menu_host_state_unavailable)
                        stabilityCaption?.text = game.getString(R.string.nova_quick_menu_host_state_unavailable)
                        bidirectionalSyncCaption?.text = game.getString(R.string.nova_quick_menu_host_state_unavailable)
                        clearProfileCaption?.text = game.getString(R.string.nova_quick_menu_host_state_unavailable)
                    }
                }
            }.start()
        } else {
            refreshSessionModeState()
            refreshHealthSummary()
            updateStateChip(aiState, game.getString(R.string.nova_quick_menu_not_available), ChipTone.MUTED)
            updateStateChip(mangoState, game.getString(R.string.nova_quick_menu_not_available), ChipTone.MUTED)
            updateStateChip(stabilityState, game.getString(R.string.nova_quick_menu_not_available), ChipTone.MUTED)
            updateStateChip(bidirectionalSyncState, game.getString(R.string.nova_quick_menu_not_available), ChipTone.MUTED)
            updateStateChip(clearProfileState, game.getString(R.string.nova_quick_menu_not_available), ChipTone.MUTED)
            aiCaption?.text = game.getString(R.string.nova_quick_menu_not_polaris_session)
            mangoCaption?.text = game.getString(R.string.nova_quick_menu_not_polaris_session)
            stabilityCaption?.text = game.getString(R.string.nova_quick_menu_not_polaris_session)
            bidirectionalSyncCaption?.text = game.getString(R.string.nova_quick_menu_not_polaris_session)
            clearProfileCaption?.text = game.getString(R.string.nova_quick_menu_not_polaris_session)
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
            ChipTone.ACTIVE -> ContextCompat.getColor(game, R.color.nova_success) to Color.argb(0x33, 0x4A, 0xDE, 0x80)
            ChipTone.INACTIVE -> ContextCompat.getColor(game, R.color.nova_text_secondary) to Color.argb(0x99, 0x15, 0x1A, 0x25)
            ChipTone.MUTED -> ContextCompat.getColor(game, R.color.nova_text_muted) to Color.argb(0x66, 0x25, 0x2B, 0x38)
            ChipTone.WARNING -> ContextCompat.getColor(game, R.color.nova_warning) to Color.argb(0x33, 0xFB, 0xBF, 0x24)
        }

        chip.setTextColor(textColor)
        chip.background?.mutate()?.setTint(bgColor)
    }

    private fun updatePreferenceButton(button: MaterialButton?, selected: Boolean, enabled: Boolean) {
        button ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f

        val textColor = ContextCompat.getColor(
            game,
            when {
                selected -> R.color.nova_ice
                enabled -> R.color.nova_text_primary
                else -> R.color.nova_text_muted
            }
        )
        val backgroundColor = ContextCompat.getColor(
            game,
            if (selected) R.color.nova_accent else R.color.nova_badge_bg
        )
        val strokeColor = ContextCompat.getColor(
            game,
            if (selected) R.color.nova_accent else R.color.nova_divider
        )

        button.setTextColor(textColor)
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.strokeWidth = if (selected) 0 else 1
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
