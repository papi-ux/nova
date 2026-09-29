package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.api.PolarisSessionStatus

enum class NovaQuickMenuTone {
    ACTIVE,
    INACTIVE,
    MUTED,
    INFO,
    WARNING,
    DANGER
}

enum class NovaQuickMenuActionId {
    DISCONNECT,
    END_STREAM,
    STABILITY,
    LIVE_TUNING,
    SYNC_STATUS,
    ADVANCED_TUNING,
    CLEAR_GAME_PROFILE,
    MANGOHUD,
    QUICK_ESC,
    QUICK_ALT_ENTER,
    QUICK_ALT_F4,
    QUICK_F11,
    QUICK_INSERT,
    QUICK_META,
    QUICK_CTRL_V,
    QUICK_CTRL_1,
    QUICK_CTRL_2,
    NOVA_HUD,
    PERF_STATS,
    DIAGNOSE_STREAM,
    DOCTOR_UNDO,
    COPY_HUD_DIAGNOSTICS,
    MOUSE_MODE,
    CONTROLLER,
    KEYBOARD,
    PLAYERS,
    PASTE_CLIPBOARD,
    ROTATE_SCREEN,
    MORE_KEYS,
    MORE_CONTROLS
}

data class NovaQuickMenuChip(
    val label: String,
    val tone: NovaQuickMenuTone
)

data class NovaQuickMenuAction(
    val id: NovaQuickMenuActionId,
    val label: String,
    val caption: String = "",
    val chip: NovaQuickMenuChip? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val destructive: Boolean = false,
    /**
     * The caption is a result a screen reader should hear as it arrives, such as a switch the
     * host did not confirm: the row announces it politely.
     */
    val announce: Boolean = false
)

data class NovaQuickMenuPreferenceOption(
    val value: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean
)

data class NovaQuickMenuStabilityState(
    val title: String,
    val targetSummary: String,
    val chip: NovaQuickMenuChip,
    val enabled: Boolean,
    val profileTitle: String,
    val profileCaption: String,
    val profileOptions: List<NovaQuickMenuPreferenceOption>
) {
    // The card's own tap target, built with the state instead of on every recomposition.
    val action: NovaQuickMenuAction = NovaQuickMenuAction(
        id = NovaQuickMenuActionId.STABILITY,
        label = title,
        enabled = enabled
    )
}

data class NovaQuickMenuHudOpacityState(
    val percent: Int,
    val presets: List<Int>,
    val enabled: Boolean,
    /** Where the HUD's left edge sits across the stream window, in pixels; NaN when unknown. */
    val hudLeftPx: Float = Float.NaN
) {
    val percentLabel: String = "$percent%"
}

data class NovaQuickMenuMenuOpacityState(
    val percent: Int,
    val presets: List<Int>
) {
    val percentLabel: String = "$percent%"
}

/** Every HUD layout as a selectable option, smallest first; [selected] is the live one. */
data class NovaQuickMenuHudModeState(
    val options: List<NovaQuickMenuPreferenceOption>,
    val selected: NovaHudMode,
    val enabled: Boolean,
    /** Where the HUD's left edge sits across the stream window, in pixels; NaN when unknown. */
    val hudLeftPx: Float = Float.NaN
)

enum class NovaQuickMenuDoctorCapability {
    AUTO_FIX,
    RUN_TRIAL,
    RECHECK,
    MANUAL
}

data class NovaQuickMenuDiagnosisState(
    val classification: String,
    val likelyCause: String,
    val evidence: List<String>,
    val evidenceHighlight: String,
    val tryFirst: String,
    val confidence: String,
    val available: Boolean,
    val actionId: String,
    val actionLabel: String,
    val actionExecutable: Boolean,
    val capability: NovaQuickMenuDoctorCapability,
    val targetBitrateKbps: Int,
    val verificationDelaySeconds: Int,
    val undoSupported: Boolean,
    val aiExplanation: String,
    val informationalSource: String,
    /** Drawn for a Polaris host, as known when the Command Center opened, and never for another. */
    val visible: Boolean = true,
    /** Set for a moment after A copied the details, so the chip can say so in place. */
    val copied: Boolean = false,
    /**
     * Nothing to run while the session strip does not warn, or the last reading kept a few seconds
     * old: the card keeps its one place under the strip and reads quieter (N28).
     */
    val informational: Boolean = false,
    /** The last reading, kept a few seconds old while a status read fails. */
    val stale: Boolean = false,
    /**
     * What the session strip says while it warns about a reading with nothing to run, for the card
     * to say too where "Nothing to fix" would contradict the strip; blank otherwise.
     */
    val stripVerdict: String = "",
)

data class NovaQuickMenuUiState(
    val title: String,
    val subtitle: String,
    val sessionMode: NovaQuickMenuChip,
    val sessionDetail: String,
    val healthSummary: String,
    val healthDetail: String,
    val healthTone: NovaQuickMenuTone,
    val disconnectAction: NovaQuickMenuAction,
    val endAction: NovaQuickMenuAction,
    val stability: NovaQuickMenuStabilityState,
    val liveTuningAction: NovaQuickMenuAction = NovaQuickMenuAction(NovaQuickMenuActionId.LIVE_TUNING, "", enabled = false),
    val sync: NovaQuickMenuAction,
    val advancedToggle: NovaQuickMenuAction,
    val advancedExpanded: Boolean,
    val advancedRows: List<NovaQuickMenuAction>,
    val quickKeys: List<NovaQuickMenuAction>,
    // Esc, Meta and Alt + Enter, under the session strip so a handheld reaches Esc without
    // scrolling. Same instances as [quickKeys], which stays the whole keyboard.
    val pinnedQuickKeys: List<NovaQuickMenuAction> = emptyList(),
    val diagnosis: NovaQuickMenuDiagnosisState,
    val diagnosisAction: NovaQuickMenuAction,
    val doctorReceiptAction: NovaQuickMenuAction,
    val postSessionReport: NovaPostSessionReportUiState,
    val hudOpacity: NovaQuickMenuHudOpacityState,
    val menuOpacity: NovaQuickMenuMenuOpacityState,
    val hudMode: NovaQuickMenuHudModeState,
    val overlayRows: List<NovaQuickMenuAction>,
    val controlRows: List<NovaQuickMenuAction>,
    val sessionRows: List<NovaQuickMenuAction>
) {
    /**
     * The Quick Keys grid: the keys the pinned strip lacks, so each key shows once (N26). Derived
     * here rather than a constructor default, which a copy with other keys or other pinned keys
     * kept from the state it was copied from.
     */
    val gridQuickKeys: List<NovaQuickMenuAction> = quickKeys.filterNot { key -> pinnedQuickKeys.any { it.id == key.id } }

    companion object {
        private val autoFixActionIds = setOf(
            "lower_bitrate",
            "restore_quality"
        )

        @JvmStatic
        fun from(
            context: Context,
            status: PolarisSessionStatus?,
            apiAvailable: Boolean,
            hostStateUnavailable: Boolean = false,
            liveTuningPending: Boolean = false,
            /**
             * What the last Live Tuning switch asked for when the host did not confirm it, while
             * its row says so; null otherwise.
             */
            liveTuningUnconfirmed: Boolean? = null,
            adaptiveSupported: Boolean,
            aiSupported: Boolean,
            adaptiveEnabled: Boolean,
            aiEnabled: Boolean,
            mangoHudEnabled: Boolean,
            stabilityApplied: Boolean,
            advancedExpanded: Boolean,
            profileClearInProgress: Boolean,
            /** What the last Clear Game Profile did, shown as its caption for a while. */
            profileClearResult: String? = null,
            currentGameName: String?,
            currentGameUuid: String?,
            profilePreference: String,
            /** The Launch Preset was just saved, which its caption says for a while. */
            launchPresetSaved: Boolean = false,
            hudShowing: Boolean,
            /**
             * Where the HUD's left edge sits across the stream window, in pixels, for its rows to
             * say whether the panel covers it; NaN when unknown.
             */
            hudLeftPx: Float = Float.NaN,
            hudMode: NovaHudMode = NovaHudMode.MINIMAL,
            hudOpacityPercent: Int = NovaHudPreferences.DEFAULT_OPACITY_PERCENT,
            menuOpacityPercent: Int = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
            perfOverlayEnabled: Boolean,
            onscreenControllerEnabled: Boolean,
            keyboardVisible: Boolean,
            players: List<com.papi.nova.binding.input.NovaPlayerSlot> = emptyList(),
            waitingGamepads: List<String> = emptyList(),
            multiController: Boolean = true,
            mouseModeLabel: String,
            allowChangeMouseMode: Boolean,
            isOnExternalDisplay: Boolean,
            fallbackBitrateKbps: Int,
            fallbackTargetFps: Double,
            doctorReceipt: DoctorActionReceipt? = null,
            spaceSession: Boolean = false,
            /**
             * Whether the host is Polaris, as known when the Command Center opened. Another host
             * has no Doctor, and its card is not drawn for the whole opening.
             */
            polarisHost: Boolean = true,
            /**
             * The last status the host sent while this opening stood. The Doctor card keeps its
             * reading, a few seconds old, while a status read fails.
             */
            lastStatus: PolarisSessionStatus? = null,
            // Static per locale. The host builds it once per open and hands it back in, so
            // a refresh after every tap does not rebuild nine identical rows from resources.
            quickKeys: List<NovaQuickMenuAction> = quickKeyActions(context)
        ): NovaQuickMenuUiState {
            val viewerSession = status?.isViewer == true
            val canAdjustHostTuning = status?.canAdjustHostTuning == true
            val shutdownInProgress = status?.isShuttingDown == true ||
                status?.controls?.shutdownInProgress == true
            val ownerInputAllowed = !viewerSession
            val streamPolicy = StreamPolicyUiState.from(status, fallbackBitrateKbps, fallbackTargetFps)
            val autoQuality = AutoQualityUiState.from(status, fallbackTargetFps)
            val currentGame = currentGameName?.takeIf { it.isNotBlank() }
            val currentUuid = currentGameUuid?.takeIf { it.isNotBlank() }
            val postSessionReport = NovaPostSessionReportUiState.from(status?.health ?: PolarisSessionStatus.HealthStatus())
            val mangoToggleAllowed = canAdjustHostTuning && currentUuid != null
            val mangoRisk = status?.game.equals("Steam Big Picture", ignoreCase = true)

            val hdrDowngradeSummary = status?.hdrDowngradeSummary(context)
            val healthDetail = status?.hdrDowngradeDetail(context).orEmpty()
            val healthSummary = when {
                hostStateUnavailable -> context.getString(R.string.nova_quick_menu_host_state_unavailable)
                status == null -> context.getString(R.string.nova_quick_menu_health_checking)
                status.isHostRenderLimited -> context.getString(
                    if (status.health.relaunchRecommended || status.autoQuality.relaunchRequired) {
                        R.string.nova_quick_menu_health_host_render_recovery
                    } else {
                        R.string.nova_quick_menu_health_host_render
                    }
                )
                hdrDowngradeSummary != null -> hdrDowngradeSummary
                status.hasHealthConcerns -> status.healthToneLabel
                status.hasAuthoritativeDoctorResult ->
                    context.getString(R.string.nova_quick_menu_health_steady)
                status.health.summary.isNotBlank() -> status.health.summary
                else -> context.getString(R.string.nova_quick_menu_health_steady)
            }
            val healthTone = when {
                status == null -> NovaQuickMenuTone.MUTED
                status.isHostRenderLimited || status.isHdrDowngraded || status.hasHealthConcerns -> NovaQuickMenuTone.WARNING
                else -> NovaQuickMenuTone.MUTED
            }

            val sessionDetail = resolveSessionDetail(context, status)
            val sessionMode = NovaQuickMenuChip(
                label = resolveSessionModeLabel(context, status),
                tone = when {
                    status == null -> NovaQuickMenuTone.MUTED
                    status.isShuttingDown -> NovaQuickMenuTone.WARNING
                    status.isViewer -> NovaQuickMenuTone.WARNING
                    status.isHeadlessMode -> NovaQuickMenuTone.ACTIVE
                    else -> NovaQuickMenuTone.INACTIVE
                }
            )
            val subtitle = when {
                status?.isShuttingDown == true -> context.getString(R.string.nova_quick_menu_shutdown_subtitle)
                status?.isHeadlessMode == true -> context.getString(R.string.nova_quick_menu_headless_subtitle)
                status?.isVirtualDisplayMode == true -> context.getString(R.string.nova_quick_menu_virtual_subtitle)
                else -> context.getString(R.string.nova_quick_menu_command_center_subtitle)
            }

            val profileButtonsEnabled = currentGame != null
            val normalizedPreference = AutoQualityProfilePreferences.normalize(profilePreference)
            val profileOptions = listOf(
                "auto" to context.getString(R.string.nova_auto_quality_preference_auto),
                "quality" to context.getString(R.string.nova_auto_quality_preference_quality),
                "high_fps" to context.getString(R.string.nova_auto_quality_preference_high_fps),
                "stability" to context.getString(R.string.nova_auto_quality_preference_stability)
            ).map { (value, label) ->
                NovaQuickMenuPreferenceOption(
                    value = value,
                    label = label,
                    selected = value == normalizedPreference,
                    enabled = profileButtonsEnabled
                )
            }

            val diagnosis = diagnosisState(
                context = context,
                status = status,
                lastStatus = lastStatus,
                healthSummary = healthSummary,
                stripWarns = healthTone == NovaQuickMenuTone.WARNING,
                polarisHost = polarisHost,
            )
            val stability = NovaQuickMenuStabilityState(
                title = context.getString(R.string.nova_quick_menu_stream_card),
                targetSummary = streamPolicy.targetSummary.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.nova_quick_menu_target_checking),
                chip = if (viewerSession) chip(context.getString(R.string.nova_quick_menu_owner), NovaQuickMenuTone.MUTED) else
                    chip(profileOptions.first { it.selected }.label, NovaQuickMenuTone.INFO),
                enabled = false,
                profileTitle = context.getString(R.string.nova_quick_menu_profile_preference),
                profileCaption = when {
                    currentGame == null -> context.getString(R.string.nova_quick_menu_profile_preference_checking)
                    launchPresetSaved -> context.getString(
                        R.string.nova_quick_menu_profile_preference_saved,
                        compactGameName(currentGame)
                    )
                    else -> context.getString(
                        R.string.nova_quick_menu_profile_preference_next_launch,
                        compactGameName(currentGame)
                    )
                },
                profileOptions = profileOptions
            )

            val sync = syncAction(context, status, apiAvailable, hostStateUnavailable, streamPolicy)
            val advancedToggle = NovaQuickMenuAction(
                id = NovaQuickMenuActionId.ADVANCED_TUNING,
                label = context.getString(R.string.nova_quick_menu_advanced_tuning),
                caption = context.getString(R.string.nova_quick_menu_advanced_caption),
                // Its state, as the Keyboard row says its own: Show and Hide named what A would do.
                chip = chip(
                    if (advancedExpanded) {
                        context.getString(R.string.nova_cc_shown)
                    } else {
                        context.getString(R.string.nova_quick_menu_hidden)
                    },
                    if (advancedExpanded) NovaQuickMenuTone.ACTIVE else NovaQuickMenuTone.INACTIVE
                )
            )

            val clearRow = clearProfileAction(
                context,
                apiAvailable,
                hostStateUnavailable,
                currentGame,
                canAdjustHostTuning,
                viewerSession,
                shutdownInProgress,
                profileClearInProgress,
                profileClearResult
            )
            val mangoRow = mangoAction(
                context,
                status,
                apiAvailable,
                hostStateUnavailable,
                mangoHudEnabled,
                mangoToggleAllowed,
                viewerSession,
                shutdownInProgress,
                mangoRisk
            )

            val hudOpacity = NovaQuickMenuHudOpacityState(
                percent = NovaHudPreferences.coerceOpacityPercent(hudOpacityPercent),
                presets = NovaHudPreferences.OPACITY_PRESETS,
                enabled = hudShowing,
                hudLeftPx = hudLeftPx
            )
            val menuOpacity = NovaQuickMenuMenuOpacityState(
                percent = NovaMenuPreferences.coerceOpacityPercent(menuOpacityPercent),
                presets = NovaMenuPreferences.OPACITY_PRESETS
            )
            // One tap per layout. This was a row that cycled blind: Debug was two presses
            // away and nothing said where the next press would land.
            val hudModeState = NovaQuickMenuHudModeState(
                options = NovaHudMode.entries.map { mode ->
                    NovaQuickMenuPreferenceOption(
                        value = mode.preferenceValue,
                        label = hudModeLabel(context, mode),
                        selected = mode == hudMode,
                        enabled = hudShowing
                    )
                },
                selected = hudMode,
                enabled = hudShowing,
                hudLeftPx = hudLeftPx
            )
            val doctorReceiptAction = doctorReceiptAction(
                context = context,
                receipt = doctorReceipt,
                canAdjustHostTuning = canAdjustHostTuning
            )

            // Doctor's verdict is a card of its own, so the Overlays panel holds overlays only.
            val overlays = listOf(
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.NOVA_HUD,
                    label = context.getString(R.string.nova_quick_menu_nova_hud),
                    caption = context.getString(R.string.nova_quick_menu_nova_hud_caption),
                    chip = onOffChip(context, hudShowing),
                    enabled = true
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.PERF_STATS,
                    label = context.getString(R.string.nova_quick_menu_perf_stats),
                    caption = context.getString(R.string.nova_quick_menu_perf_stats_caption),
                    chip = onOffChip(context, perfOverlayEnabled),
                    enabled = true
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS,
                    label = context.getString(R.string.nova_quick_menu_copy_hud_diagnostics),
                    caption = context.getString(R.string.nova_quick_menu_copy_hud_diagnostics_caption),
                    enabled = true
                )
            )
            val controls = listOf(
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.MOUSE_MODE,
                    label = context.getString(R.string.nova_quick_menu_mouse),
                    chip = chip(mouseModeLabel, NovaQuickMenuTone.INACTIVE),
                    enabled = ownerInputAllowed && allowChangeMouseMode
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.CONTROLLER,
                    label = context.getString(R.string.nova_quick_menu_controller),
                    caption = context.getString(R.string.nova_quick_menu_touch_controls_caption),
                    chip = onOffChip(context, onscreenControllerEnabled),
                    enabled = ownerInputAllowed
                ),
                // Couch co-op: who plays as whom, in the order they pressed a button, and a
                // way to set that order again without ending the stream.
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.PLAYERS,
                    label = context.getString(R.string.nova_quick_menu_players),
                    caption = com.papi.nova.binding.input.NovaPlayers.caption(
                        players = players,
                        waiting = waitingGamepads,
                        multiController = multiController,
                        playerFormat = context.getString(R.string.nova_quick_menu_players_player_format),
                        unnamedPad = context.getString(R.string.nova_quick_menu_players_unnamed),
                        waitingFormat = context.getString(R.string.nova_quick_menu_players_waiting_format),
                        nobodyYet = context.getString(R.string.nova_quick_menu_players_nobody),
                        onePlayer = context.getString(R.string.nova_quick_menu_players_one_player),
                    ),
                    // A chip says a state, never an action: Reassign is what the row does.
                    chip = if (multiController) {
                        null
                    } else {
                        chip(context.getString(R.string.nova_quick_menu_players_one_chip), NovaQuickMenuTone.INACTIVE)
                    },
                    enabled = ownerInputAllowed && multiController && players.isNotEmpty()
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.KEYBOARD,
                    label = context.getString(R.string.nova_quick_menu_keyboard),
                    chip = chip(
                        if (keyboardVisible) context.getString(R.string.nova_cc_shown) else context.getString(R.string.nova_quick_menu_hidden),
                        if (keyboardVisible) NovaQuickMenuTone.ACTIVE else NovaQuickMenuTone.INACTIVE
                    ),
                    enabled = ownerInputAllowed
                )
            )
            val sessionRows = listOf(
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.PASTE_CLIPBOARD,
                    label = context.getString(R.string.nova_quick_menu_paste_clipboard),
                    caption = context.getString(R.string.nova_cc_paste_caption),
                    enabled = ownerInputAllowed
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.ROTATE_SCREEN,
                    label = context.getString(R.string.nova_quick_menu_rotate_screen),
                    caption = context.getString(R.string.nova_cc_rotate_caption),
                    enabled = true,
                    visible = !isOnExternalDisplay
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.MORE_KEYS,
                    label = context.getString(R.string.nova_quick_menu_special_keys),
                    enabled = ownerInputAllowed
                ),
                // The legacy Quick Menu's extras, on one page of their own.
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.MORE_CONTROLS,
                    label = context.getString(R.string.nova_cc_more_controls),
                    caption = context.getString(R.string.nova_cc_more_controls_caption),
                    enabled = ownerInputAllowed
                )
            )

            return NovaQuickMenuUiState(
                liveTuningAction = liveTuningAction(
                    context = context,
                    status = status,
                    enabledNow = autoQuality.enabled,
                    pending = liveTuningPending,
                    unconfirmed = liveTuningUnconfirmed,
                    hostStateUnavailable = hostStateUnavailable,
                    canAdjustHostTuning = canAdjustHostTuning,
                    adaptiveSupported = adaptiveSupported,
                ),
                title = context.getString(R.string.nova_quick_menu_command_center_title),
                subtitle = subtitle,
                sessionMode = sessionMode,
                sessionDetail = sessionDetail,
                healthSummary = healthSummary,
                healthDetail = healthDetail,
                healthTone = healthTone,
                // In a Space, disconnecting would leave the Space anyway, so the one way out is
                // the header's split, which reads Leave Space and confirms in place.
                disconnectAction = NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.DISCONNECT,
                    label = context.getString(R.string.game_menu_disconnect),
                    visible = !spaceSession,
                ),
                endAction = NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.END_STREAM,
                    label = when {
                        spaceSession -> context.getString(R.string.nova_space_leave_action)
                        viewerSession -> context.getString(R.string.nova_quick_menu_leave)
                        status?.isShuttingDown == true -> context.getString(R.string.nova_quick_menu_ending)
                        else -> context.getString(R.string.nova_quick_menu_end_stream)
                    },
                    caption = when {
                        spaceSession -> context.getString(R.string.nova_space_leave_caption)
                        viewerSession -> ""
                        else -> context.getString(R.string.nova_cc_end_session_consequence)
                    },
                    enabled = spaceSession || viewerSession || status?.canQuit != false,
                    // A viewer's Leave ends nothing on the host, so it needs no confirm.
                    destructive = !viewerSession || spaceSession
                ),
                stability = stability,
                sync = sync,
                advancedToggle = advancedToggle,
                advancedExpanded = advancedExpanded,
                // AI may explain evidence, but it no longer owns a mutable
                // launch-policy control. Presets live in the card above.
                advancedRows = listOf(clearRow, mangoRow),
                quickKeys = quickKeys,
                pinnedQuickKeys = pinnedQuickKeys(quickKeys),
                diagnosis = diagnosis,
                diagnosisAction = diagnoseAction(context, status, diagnosis),
                doctorReceiptAction = doctorReceiptAction,
                postSessionReport = postSessionReport,
                hudOpacity = hudOpacity,
                menuOpacity = menuOpacity,
                hudMode = hudModeState,
                overlayRows = overlays,
                controlRows = controls,
                sessionRows = sessionRows
            )
        }

        fun preview(context: Context): NovaQuickMenuUiState {
            val status = PolarisSessionStatus(
                state = "streaming",
                streamingActive = true,
                game = "Portal",
                gameUuid = "game-1",
                ownedByClient = true,
                controls = PolarisSessionStatus.ControlsStatus(
                    hostTuningAllowed = true,
                    quitAllowed = true
                ),
                displayMode = PolarisSessionStatus.DisplayModeStatus(
                    effectiveHeadless = true,
                    requested = "headless"
                ),
                syncStatus = PolarisSessionStatus.SyncStatus(
                    available = true,
                    state = "synced"
                ),
                tuning = PolarisSessionStatus.TuningStatus(
                    adaptiveBitrateEnabled = true,
                    aiOptimizerEnabled = true
                ),
                health = PolarisSessionStatus.HealthStatus(grade = "good")
            )
            return from(
                context = context,
                status = status,
                apiAvailable = true,
                hostStateUnavailable = false,
                adaptiveSupported = true,
                aiSupported = true,
                adaptiveEnabled = true,
                aiEnabled = true,
                mangoHudEnabled = false,
                stabilityApplied = false,
                advancedExpanded = false,
                profileClearInProgress = false,
                currentGameName = "Portal",
                currentGameUuid = "game-1",
                profilePreference = "auto",
                hudShowing = false,
                hudOpacityPercent = NovaHudPreferences.DEFAULT_OPACITY_PERCENT,
                menuOpacityPercent = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
                perfOverlayEnabled = false,
                onscreenControllerEnabled = false,
                keyboardVisible = false,
                mouseModeLabel = context.getString(R.string.nova_quick_menu_direct),
                allowChangeMouseMode = true,
                isOnExternalDisplay = false,
                fallbackBitrateKbps = 50000,
                fallbackTargetFps = 60.0
            )
        }

        private fun doctorReceiptAction(
            context: Context,
            receipt: DoctorActionReceipt?,
            canAdjustHostTuning: Boolean
        ): NovaQuickMenuAction {
            if (receipt == null) {
                return NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.DOCTOR_UNDO,
                    label = context.getString(R.string.nova_quick_menu_doctor_receipt_title),
                    visible = false,
                    enabled = false
                )
            }
            val watching = !receipt.isTerminal
            val canUndo = (canAdjustHostTuning || receipt.runId.startsWith("recovery-run-")) &&
                receipt.undoAvailable &&
                receipt.runId.isNotBlank() &&
                receipt.undoActionId.isNotBlank()
            val chip = when {
                receipt.state == "queued" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_queued), NovaQuickMenuTone.INFO)
                receipt.state == "applied" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_applied), NovaQuickMenuTone.ACTIVE)
                receipt.state == "expired" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_expired), NovaQuickMenuTone.WARNING)
                receipt.state == "rejected" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_rejected), NovaQuickMenuTone.WARNING)
                watching -> chip(context.getString(R.string.nova_quick_menu_doctor_receipt_watching), NovaQuickMenuTone.INFO)
                receipt.state == "resolved" || receipt.state == "stable" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_verified), NovaQuickMenuTone.ACTIVE)
                receipt.state == "needs_attention" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_attention), NovaQuickMenuTone.WARNING)
                receipt.state == "rollback_unconfirmed" ->
                    chip(context.getString(R.string.nova_quick_menu_doctor_receipt_attention), NovaQuickMenuTone.WARNING)
                else -> chip(context.getString(R.string.nova_quick_menu_done), NovaQuickMenuTone.INACTIVE)
            }
            val caption = buildList {
                receipt.message.takeIf { it.isNotBlank() }?.let(::add)
                if (canUndo) {
                    add(
                        context.getString(
                            if (receipt.appUuid.isNotBlank()) {
                                R.string.nova_quick_menu_doctor_recovery_undo_caption
                            } else {
                                R.string.nova_quick_menu_doctor_receipt_undo_caption
                            }
                        )
                    )
                }
            }.joinToString(" ")
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.DOCTOR_UNDO,
                label = if (canUndo) {
                    context.getString(R.string.nova_quick_menu_doctor_undo)
                } else {
                    context.getString(R.string.nova_quick_menu_doctor_receipt_title)
                },
                caption = caption,
                chip = chip,
                enabled = canUndo,
                visible = true
            )
        }

        /**
         * The Doctor card for a Polaris host, in its one place under the strip from the first frame
         * to close, so nothing below it moves (review finding 1). Before the host's first reading it
         * says it is checking, disabled but focusable, and why A does nothing yet. A failed status
         * read keeps the last reading, a few seconds old, rather than going back to checking. A
         * Space's verdict is a reading like any other. Another host has no card at all.
         */
        private fun diagnosisState(
            context: Context,
            status: PolarisSessionStatus?,
            lastStatus: PolarisSessionStatus?,
            healthSummary: String,
            stripWarns: Boolean,
            polarisHost: Boolean,
        ): NovaQuickMenuDiagnosisState {
            fun hasReading(candidate: PolarisSessionStatus?): Boolean = candidate != null && (
                candidate.doctor.available || candidate.doctor.likelyCause.isNotBlank() ||
                    candidate.doctor.primaryIssue.isNotBlank()
                )
            val stale = status == null && hasReading(lastStatus)
            val reading = if (stale) lastStatus else status?.takeIf { hasReading(it) }
            val doctor = reading?.doctor
            val informationalAiExplanation = doctor?.aiExplanation
                ?.takeIf { it.available && it.informational }
            val actionId = doctor?.actionId.orEmpty()
            val available = reading != null
            // The action is judged against the status that carried the reading. A reading kept
            // through a failed read is a few seconds old and runs nothing, whatever that status
            // allowed: A copies its details.
            val actionEnvelopeExecutable = !stale && doctor?.canExecuteAction == true
            val readOnlyRecheck = actionId in setOf("recheck_network", "recheck_pacing")
            val actionExecutable = actionEnvelopeExecutable && if (readOnlyRecheck) {
                reading?.authorityContractValid == true &&
                    reading.ownedByClient && !reading.isViewer
            } else {
                reading?.canAdjustHostTuning == true
            }
            val capability = if (!actionExecutable) {
                NovaQuickMenuDoctorCapability.MANUAL
            } else when (doctor?.actionCapability?.lowercase()) {
                "auto_fix" -> NovaQuickMenuDoctorCapability.AUTO_FIX
                "recheck" -> NovaQuickMenuDoctorCapability.RECHECK
                // Trials remain compile-time dormant in the matched host and
                // Nova has no executable trial envelope in this release.
                else -> when {
                    actionId in autoFixActionIds -> NovaQuickMenuDoctorCapability.AUTO_FIX
                    readOnlyRecheck -> NovaQuickMenuDoctorCapability.RECHECK
                    else -> NovaQuickMenuDoctorCapability.MANUAL
                }
            }
            // Before the first reading the card says it is checking, as the strip does.
            val likelyCause = if (available) {
                doctor?.likelyCause.orEmpty()
            } else {
                context.getString(R.string.nova_quick_menu_health_checking)
            }
            val evidence = doctor?.evidence ?: emptyList()
            // The host's first-try line usually opens by restating the finding, which is
            // already the card's title. Keep only the advice that follows it.
            val tryFirst = doctor?.firstTry.orEmpty().withoutLeadingSentence(likelyCause)
            // A reading with nothing Nova can run informs and reads quieter while the strip does
            // not warn, and so does one a few seconds old. While the strip warns, the card says what
            // the strip says; "Nothing to fix" under a warning contradicted it (N28).
            val informational = available && (stale || (!actionExecutable && !stripWarns))
            val stripVerdict = if (available && !stale && !actionExecutable && stripWarns) {
                healthSummary.trim().trimEnd('.')
            } else {
                ""
            }
            return NovaQuickMenuDiagnosisState(
                classification = doctor?.classification?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
                likelyCause = likelyCause,
                evidence = evidence,
                evidenceHighlight = doctorEvidenceHighlight(reading),
                tryFirst = tryFirst,
                confidence = doctor?.confidence.orEmpty(),
                available = available,
                visible = polarisHost,
                actionId = actionId,
                actionLabel = doctor?.actionLabel.orEmpty(),
                actionExecutable = actionExecutable,
                capability = capability,
                informational = informational,
                stale = stale,
                stripVerdict = stripVerdict,
                targetBitrateKbps = doctor?.targetBitrateKbps ?: 0,
                verificationDelaySeconds = doctor?.verificationDelaySeconds ?: 0,
                undoSupported = doctor?.undoSupported == true,
                aiExplanation = informationalAiExplanation
                    ?.let { explanation ->
                        buildList {
                            explanation.likelyCause.takeIf { it.isNotBlank() }?.let(::add)
                            explanation.tryFirst.firstOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { add(context.getString(R.string.nova_cc_doctor_try_first, it)) }
                        }.joinToString(" ")
                    }
                    .orEmpty(),
                informationalSource = when {
                    informationalAiExplanation != null ->
                        listOf(context.getString(R.string.nova_cc_doctor_ai_only), informationalAiExplanation.sourceMode)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    doctor?.explanationInformational == true &&
                        doctor.explanationSourceKind.equals("deterministic-fallback", ignoreCase = true) ->
                        listOf("Deterministic fallback", doctor.explanationSourceMode)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    else -> ""
                }
            )
        }

        // The host lists a passing "a stream is active" check first, so the first evidence
        // string is never the interesting one. Show the first item Doctor itself grades as
        // actionable, or nothing at all.
        private fun doctorEvidenceHighlight(status: PolarisSessionStatus?): String {
            val doctor = status?.doctor ?: return ""
            if (doctor.evidenceItems.isEmpty()) {
                return doctor.evidence.firstOrNull().orEmpty()
            }
            val index = doctor.evidenceItems.indexOfFirst { status.doctorEvidenceIsActionable(it) }
            if (index < 0) {
                return ""
            }
            val item = doctor.evidenceItems[index]
            item.detail.takeIf { it.isNotBlank() }?.let { return it }
            if (doctor.evidence.size == doctor.evidenceItems.size) {
                doctor.evidence[index].takeIf { it.isNotBlank() }?.let { return it }
            }
            return listOfNotNull(item.id.replace('_', ' '), item.value?.toString())
                .joinToString(" ")
        }

        private fun diagnoseAction(
            context: Context,
            status: PolarisSessionStatus?,
            diagnosis: NovaQuickMenuDiagnosisState
        ): NovaQuickMenuAction {
            val classification = diagnosis.classification.takeIf { it in setOf("HOST", "NET", "CLIENT") } ?: "N/A"
            val tone = when (classification) {
                "HOST", "NET", "CLIENT" -> NovaQuickMenuTone.WARNING
                else -> NovaQuickMenuTone.MUTED
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.DIAGNOSE_STREAM,
                label = diagnosis.actionLabel.takeIf { diagnosis.actionExecutable && it.isNotBlank() }
                    ?: context.getString(R.string.nova_quick_menu_diagnose_stream),
                caption = diagnosis.likelyCause,
                chip = chip(classification, tone),
                enabled = status != null
            )
        }

        /**
         * Live Tuning's row, every word from resources: its state on the chip, what it is doing and
         * the bitrate it applied under the title. It is a host setting; the row splits in place
         * before it changes, and the line under the split says so.
         */
        private fun liveTuningAction(
            context: Context,
            status: PolarisSessionStatus?,
            enabledNow: Boolean,
            pending: Boolean,
            unconfirmed: Boolean?,
            hostStateUnavailable: Boolean,
            canAdjustHostTuning: Boolean,
            adaptiveSupported: Boolean,
        ): NovaQuickMenuAction {
            // A Space streams at the bitrate it started with and says so; that is not an unknown.
            val fixedForSpace = status?.liveTuningUnavailable == true && status.liveTuning == null
            val unknown = hostStateUnavailable || status == null ||
                (!fixedForSpace && status.liveTuningPresent && status.liveTuning == null)
            val chipLabel = when {
                unknown -> R.string.nova_cc_live_tuning_unknown
                fixedForSpace -> R.string.nova_cc_live_tuning_fixed
                enabledNow -> R.string.nova_quick_menu_on
                else -> R.string.nova_quick_menu_off
            }
            // A switch the host did not confirm is said here, where it was asked for, not in a
            // snackbar: the state the host reports now, which the chip shows, and nothing that asks
            // the player to try again, which from the chip's state would undo what they asked for.
            // The host may have applied it and lost its answer; then there is nothing to say.
            val result = unconfirmed?.let { asked ->
                when {
                    unknown -> context.getString(R.string.nova_cc_live_tuning_unconfirmed)
                    enabledNow != asked -> context.getString(
                        if (enabledNow) R.string.nova_cc_live_tuning_kept_on else R.string.nova_cc_live_tuning_kept_off,
                    )
                    else -> null
                }
            }?.takeIf { !pending }
            val caption = when {
                pending -> context.getString(R.string.nova_cc_live_tuning_saving)
                // Ahead of Reconnecting: a status refresh that failed too must not hide the failure.
                result != null -> result
                hostStateUnavailable -> context.getString(R.string.nova_cc_live_tuning_reconnecting)
                fixedForSpace -> context.getString(R.string.nova_cc_live_tuning_space)
                else -> liveTuningCaption(context, status)
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.LIVE_TUNING,
                label = context.getString(R.string.nova_cc_live_tuning),
                caption = caption,
                chip = chip(
                    context.getString(chipLabel),
                    if (enabledNow) NovaQuickMenuTone.ACTIVE else NovaQuickMenuTone.INACTIVE,
                ),
                announce = result != null,
                // The row stays enabled while a save is pending, and while its result shows even
                // when the host's state could not be read: the caption says so, onLiveTuning asks
                // nothing of a host it cannot read, and disabling the row under a controller cursor
                // drops focus off it mid-press or as the result arrives.
                enabled = result != null || (
                    !hostStateUnavailable && canAdjustHostTuning &&
                        (status?.liveTuning != null || (status?.liveTuningPresent != true && adaptiveSupported))
                    ),
            )
        }

        /** What Live Tuning is doing, as AutoQualityUiState reads the host, in the player's words. */
        private fun liveTuningCaption(context: Context, status: PolarisSessionStatus?): String {
            val live = status?.liveTuning
            if (status == null || (status.liveTuningPresent && live == null)) {
                return context.getString(R.string.nova_cc_live_tuning_waiting_host)
            }
            val enabled = live?.enabled ?: (status.tuning.adaptiveBitrateEnabled || status.adaptiveBitrateEnabled)
            if (!enabled) return context.getString(R.string.nova_cc_live_tuning_off_caption)
            // Older hosts expose preference and target only. Never claim encoder acknowledgement.
            if (live == null) return context.getString(R.string.nova_cc_live_tuning_on_unconfirmed)
            val state = context.getString(
                when (live.state) {
                    "waiting" -> R.string.nova_cc_live_tuning_waiting_stream
                    "unavailable" -> R.string.nova_cc_live_tuning_unavailable
                    "applying" -> R.string.nova_cc_live_tuning_applying
                    "measuring" -> R.string.nova_cc_live_tuning_measuring
                    "adjusting" -> R.string.nova_cc_live_tuning_adjusting
                    else -> R.string.nova_cc_live_tuning_steady
                }
            )
            return when {
                live.state == "unavailable" && live.reason.isNotBlank() ->
                    context.getString(R.string.nova_cc_live_tuning_state_reason, state, live.reason.replace('_', ' '))
                live.state != "unavailable" && live.appliedBitrateKbps > 0 -> context.getString(
                    R.string.nova_cc_live_tuning_state_figures,
                    state,
                    StreamPolicyUiState.formatMbps(live.appliedBitrateKbps),
                    StreamPolicyUiState.formatMbps(live.qualityLimitKbps),
                )
                else -> context.getString(R.string.nova_cc_live_tuning_state, state)
            }
        }

        private fun syncAction(
            context: Context,
            status: PolarisSessionStatus?,
            apiAvailable: Boolean,
            hostStateUnavailable: Boolean,
            policy: StreamPolicyUiState
        ): NovaQuickMenuAction {
            if (hostStateUnavailable) {
                return NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.SYNC_STATUS,
                    label = context.getString(R.string.nova_quick_menu_sync_status),
                    caption = context.getString(R.string.nova_quick_menu_host_state_unavailable),
                    chip = chip(context.getString(R.string.nova_quick_menu_unavailable), NovaQuickMenuTone.MUTED),
                    enabled = false
                )
            }
            if (!apiAvailable && status == null) {
                return NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.SYNC_STATUS,
                    label = context.getString(R.string.nova_quick_menu_sync_status),
                    caption = context.getString(R.string.nova_quick_menu_not_polaris_session),
                    chip = chip(context.getString(R.string.nova_quick_menu_not_available), NovaQuickMenuTone.MUTED),
                    enabled = false
                )
            }
            val sync = status?.syncStatus
            val presentationStatus = status?.clientPresentation?.status.orEmpty().lowercase()
            val liveTuning = context.getString(R.string.nova_cc_live_tuning)
            val label = when {
                status == null -> "Checking"
                sync?.isManualOverride == true -> "Manual"
                sync?.needsRelaunch == true -> "Relaunch"
                sync?.isFailed == true -> "Attention"
                sync?.isApplying == true -> "Applying"
                presentationStatus == "blocked" -> "Blocked"
                presentationStatus == "pending" -> "Pending"
                policy.adaptiveTargetBitrateKbps > 0 -> liveTuning
                sync?.isSynced == true -> "Synced"
                status.isClientPresentationSynced -> "Synced"
                status.isStreaming -> "Live"
                else -> "Ready"
            }
            val tone = when (label) {
                "Synced", liveTuning, "Live" -> NovaQuickMenuTone.ACTIVE
                "Pending", "Blocked", "Relaunch", "Attention", "Applying", "Manual" -> NovaQuickMenuTone.WARNING
                "Ready" -> NovaQuickMenuTone.INACTIVE
                else -> NovaQuickMenuTone.MUTED
            }
            val syncState = sync?.state.orEmpty().lowercase()
            val caption = when {
                status == null -> "checking host and client settings"
                policy.hasAdaptiveCap -> context.getString(
                    R.string.nova_cc_live_tuning_sync_capped,
                    policy.adaptiveTargetLabel,
                    policy.qualityLimitLabel,
                )
                policy.adaptiveTargetBitrateKbps > 0 && policy.adaptiveEnabled ->
                    context.getString(R.string.nova_cc_live_tuning_sync_target, policy.adaptiveTargetLabel)
                policy.adaptiveTargetBitrateKbps > 0 -> policy.statusCaption
                sync?.message?.isNotBlank() == true -> sync.message
                syncState == "manual_override" -> "manual client tuning is active"
                syncState == "needs_relaunch" -> "saved settings apply on next launch"
                syncState == "applying" -> "Nova is reporting applied stream settings"
                presentationStatus == "blocked" -> "client could not apply the requested display sync"
                presentationStatus == "pending" -> "waiting for Nova to report display sync"
                status.isClientPresentationSynced && status.clientPresentation.appliedRefreshRateHz > 0.0 ->
                    "Retroid display ${status.clientPresentation.appliedRefreshRateHz.toInt()} Hz matches stream"
                else -> "host and client settings"
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.SYNC_STATUS,
                label = context.getString(R.string.nova_quick_menu_sync_status),
                caption = caption,
                chip = chip(label, tone),
                enabled = status != null
            )
        }

        private fun clearProfileAction(
            context: Context,
            apiAvailable: Boolean,
            hostStateUnavailable: Boolean,
            currentGame: String?,
            canAdjustHostTuning: Boolean,
            viewerSession: Boolean,
            shutdownInProgress: Boolean,
            inProgress: Boolean,
            result: String? = null
        ): NovaQuickMenuAction {
            val enabled = apiAvailable &&
                !hostStateUnavailable &&
                !currentGame.isNullOrBlank() &&
                canAdjustHostTuning &&
                !shutdownInProgress &&
                !inProgress
            val caption = when {
                hostStateUnavailable -> context.getString(R.string.nova_quick_menu_host_state_unavailable)
                !apiAvailable -> context.getString(R.string.nova_quick_menu_not_polaris_session)
                currentGame.isNullOrBlank() -> context.getString(R.string.nova_quick_menu_clear_game_profile_unavailable)
                !canAdjustHostTuning && viewerSession -> context.getString(R.string.nova_quick_menu_owner_only_caption)
                !canAdjustHostTuning -> context.getString(R.string.nova_quick_menu_host_controls_unavailable_caption)
                // The result of the last clear, in place of a floating snackbar (R6).
                result != null && !inProgress -> result
                else -> context.getString(
                    R.string.nova_quick_menu_clear_game_profile_for_game,
                    compactGameName(currentGame)
                )
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.CLEAR_GAME_PROFILE,
                label = context.getString(R.string.nova_quick_menu_clear_game_profile),
                caption = caption,
                chip = chip(
                    when {
                        hostStateUnavailable -> context.getString(R.string.nova_quick_menu_unavailable)
                        !apiAvailable -> context.getString(R.string.nova_quick_menu_not_available)
                        inProgress -> context.getString(R.string.nova_quick_menu_working)
                        !enabled -> context.getString(R.string.nova_quick_menu_locked)
                        else -> context.getString(R.string.nova_quick_menu_clear)
                    },
                    when {
                        inProgress -> NovaQuickMenuTone.WARNING
                        enabled -> NovaQuickMenuTone.INACTIVE
                        else -> NovaQuickMenuTone.MUTED
                    }
                ),
                enabled = enabled
            )
        }

        private fun mangoAction(
            context: Context,
            status: PolarisSessionStatus?,
            apiAvailable: Boolean,
            hostStateUnavailable: Boolean,
            enabledNow: Boolean,
            toggleAllowed: Boolean,
            viewerSession: Boolean,
            shutdownInProgress: Boolean,
            risky: Boolean
        ): NovaQuickMenuAction {
            val chipTone = when {
                hostStateUnavailable -> NovaQuickMenuTone.MUTED
                !apiAvailable && status == null -> NovaQuickMenuTone.MUTED
                !toggleAllowed -> NovaQuickMenuTone.MUTED
                risky && !enabledNow -> NovaQuickMenuTone.WARNING
                enabledNow -> NovaQuickMenuTone.ACTIVE
                else -> NovaQuickMenuTone.INACTIVE
            }
            val chipLabel = when {
                hostStateUnavailable -> context.getString(R.string.nova_quick_menu_unavailable)
                !apiAvailable && status == null -> context.getString(R.string.nova_quick_menu_not_available)
                !toggleAllowed -> context.getString(R.string.nova_quick_menu_locked)
                enabledNow -> context.getString(R.string.nova_quick_menu_queued)
                else -> context.getString(R.string.nova_quick_menu_off)
            }
            val caption = when {
                hostStateUnavailable -> context.getString(R.string.nova_quick_menu_host_state_unavailable)
                !apiAvailable && status == null -> context.getString(R.string.nova_quick_menu_not_polaris_session)
                shutdownInProgress -> context.getString(R.string.nova_quick_menu_session_ending_caption)
                !toggleAllowed && viewerSession -> context.getString(R.string.nova_quick_menu_owner_only_caption)
                !toggleAllowed -> context.getString(R.string.nova_quick_menu_host_controls_unavailable_caption)
                risky -> context.getString(R.string.nova_mangohud_quick_menu_caption_risky)
                else -> context.getString(R.string.nova_mangohud_quick_menu_caption_default)
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.MANGOHUD,
                label = context.getString(R.string.nova_quick_menu_mangohud),
                caption = caption,
                chip = chip(chipLabel, chipTone),
                enabled = apiAvailable && !hostStateUnavailable && toggleAllowed
            )
        }

        // The pill names the mode and the encoder. Capture path, mode source, and role go on
        // the detail line so the health summary beside the pill keeps its room.
        private fun resolveSessionModeLabel(context: Context, status: PolarisSessionStatus?): String {
            if (status == null) {
                return context.getString(R.string.nova_quick_menu_mode_unknown)
            }
            val base = listOf(sessionModeName(context, status), status.encoderSelectionLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            return if (status.isViewer) {
                context.getString(R.string.nova_session_mode_watch_format, base)
            } else {
                base
            }
        }

        /**
         * The session pill's mode, in plain words from resources, by the names the library uses.
         * The host status's own English label brackets a technical note, "Private Stream
         * (GPU-native)"; the detail line under the pill says the capture when the host reports
         * one: GPU capture, GPU encoding or CPU capture. A label Nova has no name for, such as a
         * Space's, is the host's own.
         */
        private fun sessionModeName(context: Context, status: PolarisSessionStatus): String = when (status.sessionMode) {
            PolarisSessionStatus.SessionMode.PRIVATE_STREAM,
            PolarisSessionStatus.SessionMode.PRIVATE_STREAM_GPU_NATIVE -> context.getString(R.string.nova_session_mode_headless)
            PolarisSessionStatus.SessionMode.MIRROR_DESKTOP -> context.getString(R.string.nova_session_mode_host_display)
            PolarisSessionStatus.SessionMode.DESKTOP_TAKEOVER -> context.getString(R.string.nova_session_mode_desktop_takeover)
            PolarisSessionStatus.SessionMode.HOST_VIRTUAL_DISPLAY -> context.getString(R.string.nova_session_mode_virtual_display)
            PolarisSessionStatus.SessionMode.GAMESCOPE_STREAM -> context.getString(R.string.nova_library_launch_gamescope)
            PolarisSessionStatus.SessionMode.HEADLESS_DONGLE -> context.getString(R.string.nova_library_launch_dongle)
            PolarisSessionStatus.SessionMode.HOST_LABEL -> status.displayMode.label.trim()
        }

        private fun resolveSessionDetail(context: Context, status: PolarisSessionStatus?): String {
            if (status == null) {
                return ""
            }
            val source = when (status.displayMode.requested) {
                "auto" -> context.getString(R.string.nova_quick_menu_mode_source_auto)
                "headless", "headless_stream", "virtual_display", "host_virtual_display", "windowed_stream", "desktop_display", "desktop_takeover" ->
                    context.getString(R.string.nova_quick_menu_mode_source_explicit)
                else -> ""
            }
            val role = if (!status.isViewer && status.ownedByClient) {
                context.getString(R.string.nova_quick_menu_mode_role_owner)
            } else {
                ""
            }
            // Plain words for the capture path: GPU capture when frames stay on the GPU, GPU encoding
            // when only the encoder is on it, CPU capture otherwise, and nothing when the host
            // reports no capture path.
            val capture = when {
                status.isGpuNativeCapture -> context.getString(R.string.nova_cc_capture_gpu)
                status.capturePathLabel.isBlank() -> ""
                status.isGpuPath && !status.capturePathLabel.contains("SHM") -> context.getString(R.string.nova_cc_capture_gpu_encoder)
                else -> context.getString(R.string.nova_cc_capture_cpu)
            }
            return listOf(capture, source, role)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }

        private fun hudModeLabel(context: Context, mode: NovaHudMode): String = context.getString(
            when (mode) {
                NovaHudMode.SLIM -> R.string.nova_quick_menu_hud_mode_slim
                NovaHudMode.MINIMAL -> R.string.nova_quick_menu_hud_mode_minimal
                NovaHudMode.PERFORMANCE -> R.string.nova_quick_menu_hud_mode_performance
                NovaHudMode.DEBUG -> R.string.nova_quick_menu_hud_mode_debug
            }
        )

        private fun String.sameSentenceAs(other: String): Boolean {
            val mine = trim().trimEnd('.', '!')
            val theirs = other.trim().trimEnd('.', '!')
            return mine.isNotBlank() && mine.equals(theirs, ignoreCase = true)
        }

        // "Streaming telemetry looks ready. Keep this page open..." under a title that already
        // says "Streaming telemetry looks ready" read as a stutter once the Doctor card led the
        // page. Drop that opening sentence; if nothing follows it, the row disappears.
        private fun String.withoutLeadingSentence(title: String): String {
            val mine = trim()
            val head = title.trim().trimEnd('.', '!')
            if (head.isBlank() || !mine.startsWith(head, ignoreCase = true)) {
                return mine
            }
            val boundary = mine.getOrNull(head.length)
            if (boundary != null && boundary !in ".! ") {
                return mine
            }
            return mine.substring(head.length).trimStart('.', '!', ' ')
        }

        private fun PolarisSessionStatus.hdrDowngradeDetail(context: Context): String? {
            if (!isHdrDowngraded) {
                return null
            }
            return if (isHeadlessHdrUnavailable) {
                context.getString(R.string.nova_quick_menu_health_hdr_headless_detail)
            } else {
                context.getString(R.string.nova_quick_menu_health_hdr_downgrade_detail)
            }
        }
        private fun PolarisSessionStatus.hdrDowngradeSummary(context: Context): String? {
            if (!isHdrDowngraded) {
                return null
            }
            return if (isHeadlessHdrUnavailable) {
                context.getString(R.string.nova_quick_menu_health_hdr_headless_downgrade)
            } else {
                context.getString(R.string.nova_quick_menu_health_hdr_downgrade)
            }
        }
        private fun optimizationRuntimeCaption(context: Context, status: PolarisSessionStatus?): String? {
            val source = status?.optimizationSourceLabel?.takeIf { it.isNotBlank() } ?: return null
            val confidence = status.optimizationConfidenceLabel
                .takeIf {
                    it.isNotBlank() &&
                        !status.encoder.optimizationSource.equals("device_db", ignoreCase = true)
                }
                ?.lowercase()
            val normalization = status.optimizationNormalizedLabel.takeIf { it.isNotBlank() }
            val freshness = when (status.encoder.optimizationCacheStatus.lowercase()) {
                "hit" -> context.getString(R.string.nova_optimization_cached)
                "invalidated" -> context.getString(R.string.nova_optimization_recovery)
                "miss" -> context.getString(R.string.nova_optimization_fresh)
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

        // Esc for the game's own menu, Meta for the desktop, Alt + Enter for fullscreen: the
        // keys a handheld has no other way to press, in the order they get reached for.
        val pinnedQuickKeyIds: List<NovaQuickMenuActionId> = listOf(
            NovaQuickMenuActionId.QUICK_ESC,
            NovaQuickMenuActionId.QUICK_META,
            NovaQuickMenuActionId.QUICK_ALT_ENTER
        )

        fun pinnedQuickKeys(quickKeys: List<NovaQuickMenuAction>): List<NovaQuickMenuAction> =
            pinnedQuickKeyIds.mapNotNull { id -> quickKeys.firstOrNull { it.id == id } }

        fun quickKeyActions(context: Context): List<NovaQuickMenuAction> = listOf(
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_ESC,
                label = context.getString(R.string.game_menu_send_keys_esc)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_ALT_ENTER,
                label = context.getString(R.string.game_menu_send_keys_alt_enter)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_ALT_F4,
                label = context.getString(R.string.game_menu_send_keys_alt_f4)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_F11,
                label = context.getString(R.string.game_menu_send_keys_f11)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_INSERT,
                label = context.getString(R.string.game_menu_send_keys_insert)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_META,
                label = context.getString(R.string.nova_quick_menu_key_meta)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_CTRL_V,
                label = context.getString(R.string.game_menu_send_keys_ctrl_v)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_CTRL_1,
                label = context.getString(R.string.game_menu_send_keys_ctrl_1)
            ),
            NovaQuickMenuAction(
                id = NovaQuickMenuActionId.QUICK_CTRL_2,
                label = context.getString(R.string.game_menu_send_keys_ctrl_2)
            )
        )

        private fun onOffChip(context: Context, enabled: Boolean): NovaQuickMenuChip {
            return chip(
                if (enabled) context.getString(R.string.nova_quick_menu_on) else context.getString(R.string.nova_quick_menu_off),
                if (enabled) NovaQuickMenuTone.ACTIVE else NovaQuickMenuTone.INACTIVE
            )
        }

        private fun chip(label: String, tone: NovaQuickMenuTone) = NovaQuickMenuChip(label, tone)

        private fun compactGameName(gameName: String): String {
            return if (gameName.length <= 28) {
                gameName
            } else {
                gameName.take(25).trimEnd() + "..."
            }
        }

        private fun AutoQualityUiState.Tone.toQuickTone(): NovaQuickMenuTone {
            return when (this) {
                AutoQualityUiState.Tone.STABLE -> NovaQuickMenuTone.ACTIVE
                AutoQualityUiState.Tone.INFO -> NovaQuickMenuTone.ACTIVE
                AutoQualityUiState.Tone.WARNING -> NovaQuickMenuTone.WARNING
                AutoQualityUiState.Tone.DANGER -> NovaQuickMenuTone.WARNING
                AutoQualityUiState.Tone.MUTED -> NovaQuickMenuTone.MUTED
            }
        }
    }
}
