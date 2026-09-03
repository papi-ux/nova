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
    SYNC_STATUS,
    ADVANCED_TUNING,
    AI_AUTO_QUALITY,
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
    PASTE_CLIPBOARD,
    ROTATE_SCREEN,
    MORE_KEYS
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
    val destructive: Boolean = false
)

data class NovaQuickMenuPreferenceOption(
    val value: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean
)

data class NovaQuickMenuStabilityState(
    val title: String,
    val caption: String,
    val targetSummary: String,
    val chip: NovaQuickMenuChip,
    val enabled: Boolean,
    val profileTitle: String,
    val profileCaption: String,
    val profileOptions: List<NovaQuickMenuPreferenceOption>
)

data class NovaQuickMenuHudOpacityState(
    val percent: Int,
    val presets: List<Int>,
    val enabled: Boolean
)

data class NovaQuickMenuMenuOpacityState(
    val percent: Int,
    val presets: List<Int>
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
    val informationalSource: String
)

data class NovaQuickMenuUiState(
    val title: String,
    val subtitle: String,
    val sessionMode: NovaQuickMenuChip,
    val healthSummary: String,
    val healthDetail: String,
    val healthTone: NovaQuickMenuTone,
    val disconnectAction: NovaQuickMenuAction,
    val endAction: NovaQuickMenuAction,
    val stability: NovaQuickMenuStabilityState,
    val sync: NovaQuickMenuAction,
    val advancedToggle: NovaQuickMenuAction,
    val advancedExpanded: Boolean,
    val advancedRows: List<NovaQuickMenuAction>,
    val quickKeys: List<NovaQuickMenuAction>,
    val diagnosis: NovaQuickMenuDiagnosisState,
    val doctorReceiptAction: NovaQuickMenuAction,
    val postSessionReport: NovaPostSessionReportUiState,
    val hudOpacity: NovaQuickMenuHudOpacityState,
    val menuOpacity: NovaQuickMenuMenuOpacityState,
    val overlayRows: List<NovaQuickMenuAction>,
    val controlRows: List<NovaQuickMenuAction>,
    val sessionRows: List<NovaQuickMenuAction>
) {
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
            adaptiveSupported: Boolean,
            aiSupported: Boolean,
            adaptiveEnabled: Boolean,
            aiEnabled: Boolean,
            mangoHudEnabled: Boolean,
            stabilityApplied: Boolean,
            advancedExpanded: Boolean,
            profileClearInProgress: Boolean,
            currentGameName: String?,
            currentGameUuid: String?,
            profilePreference: String,
            hudShowing: Boolean,
            hudOpacityPercent: Int = NovaHudPreferences.DEFAULT_OPACITY_PERCENT,
            menuOpacityPercent: Int = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
            perfOverlayEnabled: Boolean,
            onscreenControllerEnabled: Boolean,
            keyboardVisible: Boolean,
            mouseModeLabel: String,
            allowChangeMouseMode: Boolean,
            isOnExternalDisplay: Boolean,
            fallbackBitrateKbps: Int,
            fallbackTargetFps: Double,
            doctorReceipt: DoctorActionReceipt? = null
        ): NovaQuickMenuUiState {
            val viewerSession = status?.isViewer == true
            val canAdjustHostTuning = status?.canAdjustHostTuning == true
            val shutdownInProgress = status?.isShuttingDown == true ||
                status?.controls?.shutdownInProgress == true
            val ownerInputAllowed = !viewerSession
            val streamPolicy = StreamPolicyUiState.from(status, fallbackBitrateKbps, fallbackTargetFps)
            val autoQuality = AutoQualityUiState.from(status, fallbackTargetFps)
            val effectiveAdaptiveEnabled = adaptiveEnabled ||
                status?.tuning?.adaptiveBitrateEnabled == true ||
                status?.adaptiveBitrateEnabled == true
            val effectiveAiEnabled = aiEnabled ||
                status?.autoQuality?.enabled == true ||
                status?.tuning?.aiAutoQualityEnabled == true ||
                status?.aiAutoQualityEnabled == true ||
                status?.tuning?.aiOptimizerEnabled == true ||
                status?.aiOptimizerEnabled == true ||
                effectiveAdaptiveEnabled
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

            val safeBitrate = status?.health?.safeBitrateKbps ?: 0
            val liveBitrate = streamPolicy.effectiveBitrateKbps
            val qualityBlocked = status?.autoQuality?.isBlocked == true || status?.isHostRenderLimited == true
            val canLowerBitrate = !qualityBlocked && safeBitrate > 0 && liveBitrate > 0 && safeBitrate < liveBitrate
            val canEnableAdaptive = !qualityBlocked && canAdjustHostTuning && !effectiveAiEnabled
            val relaunchOnly = !qualityBlocked &&
                (
                    status?.autoQuality?.let { it.isUpgradeAvailable && it.relaunchRequired } == true ||
                        status?.health?.relaunchRecommended == true
                    ) &&
                !canLowerBitrate &&
                !canEnableAdaptive
            val stabilityEnabled = canAdjustHostTuning && (canLowerBitrate || canEnableAdaptive || relaunchOnly)
            val stabilityChip = when {
                hostStateUnavailable -> chip(context.getString(R.string.nova_quick_menu_unavailable), NovaQuickMenuTone.MUTED)
                !apiAvailable && status == null -> chip(context.getString(R.string.nova_quick_menu_not_available), NovaQuickMenuTone.MUTED)
                status == null -> chip(context.getString(R.string.nova_quick_menu_loading), NovaQuickMenuTone.MUTED)
                !stabilityEnabled && viewerSession -> chip(context.getString(R.string.nova_quick_menu_owner), NovaQuickMenuTone.MUTED)
                !stabilityEnabled -> chip(autoQuality.label, autoQuality.tone.toQuickTone())
                stabilityApplied -> chip(context.getString(R.string.nova_quick_menu_done), NovaQuickMenuTone.ACTIVE)
                relaunchOnly -> chip("Relaunch", NovaQuickMenuTone.WARNING)
                else -> chip(autoQuality.label, autoQuality.tone.toQuickTone())
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

            val stability = NovaQuickMenuStabilityState(
                title = context.getString(R.string.nova_quick_menu_ai_auto_quality),
                caption = when {
                    hostStateUnavailable -> context.getString(R.string.nova_quick_menu_host_state_unavailable)
                    !apiAvailable && status == null -> context.getString(R.string.nova_quick_menu_not_polaris_session)
                    status == null -> context.getString(R.string.nova_quick_menu_health_checking)
                    else -> autoQuality.detail
                },
                targetSummary = streamPolicy.targetSummary.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.nova_quick_menu_target_checking),
                chip = stabilityChip,
                enabled = stabilityEnabled,
                profileTitle = context.getString(R.string.nova_quick_menu_profile_preference),
                profileCaption = when {
                    currentGame == null -> context.getString(R.string.nova_quick_menu_profile_preference_checking)
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
                chip = chip(
                    if (advancedExpanded) {
                        context.getString(R.string.nova_quick_menu_hide)
                    } else {
                        context.getString(R.string.nova_quick_menu_show)
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
                profileClearInProgress
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
                enabled = hudShowing
            )
            val menuOpacity = NovaQuickMenuMenuOpacityState(
                percent = NovaMenuPreferences.coerceOpacityPercent(menuOpacityPercent),
                presets = NovaMenuPreferences.OPACITY_PRESETS
            )
            val diagnosis = diagnosisState(status)
            val doctorReceiptAction = doctorReceiptAction(
                context = context,
                receipt = doctorReceipt,
                canAdjustHostTuning = canAdjustHostTuning
            )

            val overlays = listOf(
                diagnoseAction(context, status, diagnosis),
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
                    chip = onOffChip(context, perfOverlayEnabled),
                    enabled = true
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS,
                    label = context.getString(R.string.nova_quick_menu_copy_hud_diagnostics),
                    caption = context.getString(R.string.nova_quick_menu_copy_hud_diagnostics_caption),
                    chip = chip(context.getString(R.string.nova_quick_menu_safe), NovaQuickMenuTone.INFO),
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
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.KEYBOARD,
                    label = context.getString(R.string.nova_quick_menu_keyboard),
                    chip = chip(
                        if (keyboardVisible) "Shown" else context.getString(R.string.nova_quick_menu_hidden),
                        if (keyboardVisible) NovaQuickMenuTone.ACTIVE else NovaQuickMenuTone.INACTIVE
                    ),
                    enabled = ownerInputAllowed
                )
            )
            val sessionRows = listOf(
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.PASTE_CLIPBOARD,
                    label = context.getString(R.string.nova_quick_menu_paste_clipboard),
                    enabled = ownerInputAllowed
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.ROTATE_SCREEN,
                    label = context.getString(R.string.nova_quick_menu_rotate_screen),
                    enabled = true,
                    visible = !isOnExternalDisplay
                ),
                NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.MORE_KEYS,
                    label = context.getString(R.string.nova_quick_menu_special_keys),
                    enabled = ownerInputAllowed
                )
            )

            return NovaQuickMenuUiState(
                title = context.getString(R.string.nova_quick_menu_command_center_title),
                subtitle = subtitle,
                sessionMode = sessionMode,
                healthSummary = healthSummary,
                healthDetail = healthDetail,
                healthTone = healthTone,
                disconnectAction = NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.DISCONNECT,
                    label = context.getString(R.string.game_menu_disconnect)
                ),
                endAction = NovaQuickMenuAction(
                    id = NovaQuickMenuActionId.END_STREAM,
                    label = when {
                        viewerSession -> context.getString(R.string.nova_quick_menu_leave)
                        status?.isShuttingDown == true -> context.getString(R.string.nova_quick_menu_ending)
                        else -> context.getString(R.string.nova_quick_menu_end_stream)
                    },
                    enabled = viewerSession || status?.canQuit != false,
                    destructive = true
                ),
                stability = stability,
                sync = sync,
                advancedToggle = advancedToggle,
                advancedExpanded = advancedExpanded,
                // AI may explain evidence, but it no longer owns a mutable
                // launch-policy control. Presets live in the card above.
                advancedRows = listOf(clearRow, mangoRow),
                quickKeys = quickKeyActions(context),
                diagnosis = diagnosis,
                doctorReceiptAction = doctorReceiptAction,
                postSessionReport = postSessionReport,
                hudOpacity = hudOpacity,
                menuOpacity = menuOpacity,
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

        private fun diagnosisState(status: PolarisSessionStatus?): NovaQuickMenuDiagnosisState {
            val doctor = status?.doctor
            val informationalAiExplanation = doctor?.aiExplanation
                ?.takeIf { it.available && it.informational }
            val actionId = doctor?.actionId.orEmpty()
            val available = status != null &&
                (doctor?.likelyCause?.isNotBlank() == true || doctor?.primaryIssue?.isNotBlank() == true)
            val actionEnvelopeExecutable = doctor?.canExecuteAction == true
            val readOnlyRecheck = actionId in setOf("recheck_network", "recheck_pacing")
            val actionExecutable = actionEnvelopeExecutable && if (readOnlyRecheck) {
                status?.authorityContractValid == true &&
                    status.ownedByClient && !status.isViewer
            } else {
                status?.canAdjustHostTuning == true
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
            return NovaQuickMenuDiagnosisState(
                classification = doctor?.classification?.takeIf { it.isNotBlank() } ?: "UNKNOWN",
                likelyCause = doctor?.likelyCause?.takeIf { it.isNotBlank() } ?: "Connect to Polaris for HOST / NET / CLIENT diagnostics.",
                evidence = doctor?.evidence ?: emptyList(),
                tryFirst = doctor?.firstTry.orEmpty(),
                confidence = doctor?.confidence.orEmpty(),
                available = available,
                actionId = actionId,
                actionLabel = doctor?.actionLabel.orEmpty(),
                actionExecutable = actionExecutable,
                capability = capability,
                targetBitrateKbps = doctor?.targetBitrateKbps ?: 0,
                verificationDelaySeconds = doctor?.verificationDelaySeconds ?: 0,
                undoSupported = doctor?.undoSupported == true,
                aiExplanation = informationalAiExplanation
                    ?.let { explanation ->
                        buildList {
                            explanation.likelyCause.takeIf { it.isNotBlank() }?.let(::add)
                            explanation.tryFirst.firstOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { add("Try first: $it") }
                        }.joinToString(" ")
                    }
                    .orEmpty(),
                informationalSource = when {
                    informationalAiExplanation != null ->
                        listOf("AI explanation only", informationalAiExplanation.sourceMode)
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
            val label = when {
                status == null -> "Checking"
                sync?.isManualOverride == true -> "Manual"
                sync?.needsRelaunch == true -> "Relaunch"
                sync?.isFailed == true -> "Attention"
                sync?.isApplying == true -> "Applying"
                presentationStatus == "blocked" -> "Blocked"
                presentationStatus == "pending" -> "Pending"
                policy.adaptiveTargetBitrateKbps > 0 -> "Live Tuning"
                sync?.isSynced == true -> "Synced"
                status.isClientPresentationSynced -> "Synced"
                status.isStreaming -> "Live"
                else -> "Ready"
            }
            val tone = when (label) {
                "Synced", "Live Tuning", "Live" -> NovaQuickMenuTone.ACTIVE
                "Pending", "Blocked", "Relaunch", "Attention", "Applying", "Manual" -> NovaQuickMenuTone.WARNING
                "Ready" -> NovaQuickMenuTone.INACTIVE
                else -> NovaQuickMenuTone.MUTED
            }
            val syncState = sync?.state.orEmpty().lowercase()
            val caption = when {
                status == null -> "checking host and client settings"
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

        private fun aiAction(
            context: Context,
            status: PolarisSessionStatus?,
            apiAvailable: Boolean,
            hostStateUnavailable: Boolean,
            supported: Boolean,
            enabledNow: Boolean,
            canAdjustHostTuning: Boolean,
            viewerSession: Boolean,
            shutdownInProgress: Boolean,
            policy: StreamPolicyUiState,
            autoQuality: AutoQualityUiState
        ): NovaQuickMenuAction {
            val rowEnabled = apiAvailable && supported && canAdjustHostTuning
            val chip = when {
                hostStateUnavailable -> chip(context.getString(R.string.nova_quick_menu_unavailable), NovaQuickMenuTone.MUTED)
                !apiAvailable && status == null -> chip(context.getString(R.string.nova_quick_menu_not_available), NovaQuickMenuTone.MUTED)
                !supported -> chip(context.getString(R.string.nova_quick_menu_not_available), NovaQuickMenuTone.MUTED)
                enabledNow -> chip(context.getString(R.string.nova_quick_menu_on), NovaQuickMenuTone.ACTIVE)
                else -> chip(context.getString(R.string.nova_quick_menu_off), NovaQuickMenuTone.INACTIVE)
            }
            val caption = when {
                hostStateUnavailable -> context.getString(R.string.nova_quick_menu_host_state_unavailable)
                !apiAvailable && status == null -> context.getString(R.string.nova_quick_menu_not_polaris_session)
                !supported -> "server unavailable"
                shutdownInProgress -> context.getString(R.string.nova_quick_menu_session_ending_caption)
                !canAdjustHostTuning && viewerSession -> context.getString(R.string.nova_quick_menu_owner_only_caption)
                !canAdjustHostTuning -> context.getString(R.string.nova_quick_menu_host_controls_unavailable_caption)
                enabledNow && policy.adaptiveTargetBitrateKbps > 0 ->
                    "${autoQuality.detail} · ${policy.adaptiveTargetLabel} live bitrate"
                enabledNow -> autoQuality.detail.ifBlank {
                    optimizationRuntimeCaption(context, status) ?: context.getString(R.string.nova_quick_menu_ai_caption_default)
                }
                else -> "manual stream tuning"
            }
            return NovaQuickMenuAction(
                id = NovaQuickMenuActionId.AI_AUTO_QUALITY,
                label = context.getString(R.string.nova_quick_menu_ai_auto_quality),
                caption = caption,
                chip = chip,
                enabled = rowEnabled
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
            inProgress: Boolean
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

        private fun resolveSessionModeLabel(context: Context, status: PolarisSessionStatus?): String {
            val mode = when {
                status == null -> context.getString(R.string.nova_quick_menu_mode_unknown)
                else -> status.sessionModeWithCaptureLabel.ifBlank { status.sessionModeLabel }
            }
            if (status == null) {
                return mode
            }
            val source = when (status.displayMode.requested) {
                "auto" -> "Auto"
                "headless", "headless_stream", "virtual_display", "host_virtual_display", "windowed_stream", "desktop_display" -> "Explicit"
                else -> ""
            }
            val base = listOf(mode, status.encoderSelectionLabel, source)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            return when {
                status.isViewer -> context.getString(R.string.nova_session_mode_watch_format, base)
                status.ownedByClient -> context.getString(R.string.nova_session_mode_owner_format, base)
                else -> context.getString(R.string.nova_quick_menu_mode_format, base)
            }
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

        private fun quickKeyActions(context: Context) = listOf(
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
