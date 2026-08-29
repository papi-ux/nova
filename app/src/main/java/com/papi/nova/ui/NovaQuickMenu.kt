package com.papi.nova.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.snackbar.Snackbar
import com.papi.nova.Game
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisCapabilities
import com.papi.nova.api.PolarisDoctorActionResult
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.utils.DeviceUtils
import com.papi.nova.utils.UiHelper

/**
 * Stream quick menu with grouped sections for tuning, overlays, controls, and session actions.
 */
class NovaQuickMenu(private val game: Game) : Game.GameMenuCallbacks {
    private var dialog: Dialog? = null
    private val doctorActionLock = Any()
    private var doctorReceipt: DoctorActionReceipt? = null
    private var doctorReceiptScopeId: String? = null
    private var doctorReceiptValidatedScopeId: String? = null
    private var doctorActionGeneration: Long = 0L
    private val doctorMenuRefreshRegistry = DoctorMenuRefreshRegistry()
    private val doctorActionPendingRegistry = DoctorActionPendingRegistry()
    private var doctorVerificationRunnable: Runnable? = null

    override fun showMenu(device: GameInputDevice?) {
        if (dialog?.isShowing == true) return
        val menuValidationGeneration = doctorMenuRefreshRegistry.open()
        synchronized(doctorActionLock) {
            doctorReceiptValidatedScopeId = null
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
        }

        val overlay = Dialog(game)
        overlay.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val composeView = ComposeView(game)
        composeView.setViewTreeLifecycleOwner(game)
        composeView.setViewTreeSavedStateRegistryOwner(game)
        composeView.setBackgroundColor(Color.TRANSPARENT)
        overlay.setContentView(composeView)
        overlay.setOnDismissListener {
            if (doctorMenuRefreshRegistry.close(menuValidationGeneration)) {
                synchronized(doctorActionLock) {
                    doctorReceiptValidatedScopeId = null
                    doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
                    doctorVerificationRunnable = null
                }
            }
            if (dialog === overlay) dialog = null
        }
        overlay.setOnShowListener {
            overlay.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                setGravity(Gravity.START or Gravity.TOP)
                decorView.setPadding(0, 0, 0, 0)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                attributes = attributes.apply {
                    x = 0
                    y = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                decorView.systemUiVisibility = game.window.decorView.systemUiVisibility
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(game)

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
        lateinit var scheduleDoctorVerification: (DoctorActionReceipt?) -> Unit

        fun menuValidationIsCurrent(): Boolean =
            doctorMenuRefreshRegistry.isCurrent(menuValidationGeneration)

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

        fun syncDoctorReceiptScope() {
            val status = sessionStatus
            val currentAppUuid = status?.gameUuid.orEmpty().ifBlank { getRunningGameUuid().orEmpty() }
            val authoritativeRecovery = status?.recoveryRecords
                ?.firstOrNull { it.appUuid.equals(currentAppUuid, ignoreCase = true) && it.runId.isNotBlank() }
                ?: status?.recovery?.takeIf {
                    it.runId.isNotBlank() &&
                        (currentAppUuid.isBlank() || it.appUuid.equals(currentAppUuid, ignoreCase = true))
                }
            val recoveryScope = currentAppUuid.takeIf { it.isNotBlank() }?.let {
                DoctorActionReceiptStore.recoveryScopeId(
                    host = getServerAddress().orEmpty(),
                    httpsPort = getHttpsPort(),
                    appUuid = it
                )
            }
            val appSessionScope = DoctorActionReceiptStore.scopeId(
                host = getServerAddress().orEmpty(),
                httpsPort = getHttpsPort(),
                sessionStatus = status
            )
            val nextScope = if (authoritativeRecovery != null) recoveryScope else appSessionScope
            synchronized(doctorActionLock) {
                val previousScope = doctorReceiptScopeId
                if (nextScope != previousScope) {
                    doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
                    doctorVerificationRunnable = null
                    doctorActionPendingRegistry.reset()
                    doctorActionGeneration += 1L
                    doctorReceiptScopeId = nextScope
                }
                doctorReceipt = DoctorActionReceiptStore.reconcileScope(
                    preferences = prefs,
                    currentReceipt = doctorReceipt,
                    currentScopeId = previousScope,
                    nextScopeId = nextScope,
                    appSessionScopeId = appSessionScope,
                    recoveryScopeId = recoveryScope,
                    currentAppUuid = currentAppUuid,
                    authoritativeRecovery = authoritativeRecovery,
                    nowEpochMs = System.currentTimeMillis()
                )
                doctorReceiptValidatedScopeId = nextScope
            }
        }

        fun acceptRefreshedSessionStatus(refreshed: PolarisSessionStatus?): Boolean {
            if (refreshed == null) return false
            return doctorMenuRefreshRegistry.runIfCurrent(menuValidationGeneration) {
                sessionStatus = refreshed
                syncSessionDerivedState()
                syncDoctorReceiptScope()
                true
            } ?: false
        }

        fun requestIdentity(runId: String, actionId: String): DoctorActionRequestIdentity? = synchronized(doctorActionLock) {
            val scope = doctorReceiptScopeId ?: return@synchronized null
            DoctorActionRequestIdentity(
                scope,
                runId,
                doctorActionGeneration,
                sessionStatus?.appSessionId.orEmpty(),
                actionId
            )
        }

        fun currentDoctorReceipt(): DoctorActionReceipt? = synchronized(doctorActionLock) {
            doctorReceipt
        }

        fun requestIsCurrent(request: DoctorActionRequestIdentity): Boolean = synchronized(doctorActionLock) {
            DoctorActionReceiptStore.requestIsCurrent(
                current = doctorReceipt,
                activeScopeId = doctorReceiptScopeId,
                activeGeneration = doctorActionGeneration,
                request = request
            )
        }

        fun beginNewDoctorRequest(scopeId: String, actionId: String): DoctorActionRequestIdentity? = synchronized(doctorActionLock) {
            if (doctorReceiptScopeId != scopeId ||
                doctorReceiptValidatedScopeId != scopeId ||
                doctorActionPendingRegistry.isPending()
            ) {
                return@synchronized null
            }
            doctorActionGeneration += 1L
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
            DoctorActionRequestIdentity(
                scopeId,
                runId = "",
                generation = doctorActionGeneration,
                appSessionId = sessionStatus?.appSessionId.orEmpty(),
                actionId = actionId
            ).also {
                check(doctorActionPendingRegistry.begin(it.generation))
            }
        }

        fun beginDoctorUndo(
            receipt: DoctorActionReceipt,
            canAdjustHostTuning: Boolean
        ): DoctorActionRequestIdentity? = synchronized(doctorActionLock) {
            val scopeId = doctorReceiptScopeId ?: return@synchronized null
            val current = doctorReceipt ?: return@synchronized null
            if (!DoctorActionReceiptStore.undoIsAuthorized(
                    current = current,
                    candidate = receipt,
                    activeScopeId = scopeId,
                    validatedScopeId = doctorReceiptValidatedScopeId,
                    canAdjustHostTuning = canAdjustHostTuning
                ) ||
                doctorActionPendingRegistry.isPending()
            ) {
                return@synchronized null
            }
            doctorActionGeneration += 1L
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
            DoctorActionRequestIdentity(
                scopeId,
                current.runId,
                doctorActionGeneration,
                sessionStatus?.appSessionId.orEmpty(),
                current.undoActionId
            ).also {
                check(doctorActionPendingRegistry.begin(it.generation))
            }
        }

        fun storeDoctorResult(
            request: DoctorActionRequestIdentity,
            result: PolarisDoctorActionResult
        ): DoctorActionReceipt? = synchronized(doctorActionLock) {
            if (!DoctorActionReceiptStore.responseMatches(
                    current = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    activeGeneration = doctorActionGeneration,
                    request = request,
                    result = result
                )) {
                return@synchronized null
            }
            val updated = DoctorActionReceiptStore.applyResult(
                previous = doctorReceipt,
                scopeId = request.scopeId,
                result = result,
                nowEpochMs = System.currentTimeMillis()
            )
            doctorReceipt = updated
            DoctorActionReceiptStore.save(prefs, updated)
            updated
        }

        fun deferDoctorVerification(request: DoctorActionRequestIdentity): DoctorActionReceipt? = synchronized(doctorActionLock) {
            if (!DoctorActionReceiptStore.requestIsCurrent(
                    current = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    activeGeneration = doctorActionGeneration,
                    request = request
                )) {
                return@synchronized null
            }
            val pending = doctorReceipt?.takeIf { it.verificationPending } ?: return@synchronized null
            val deferred = DoctorActionReceiptStore.deferVerification(pending, System.currentTimeMillis())
            doctorReceipt = deferred
            DoctorActionReceiptStore.save(prefs, deferred)
            deferred
        }

        fun stopDoctorVerification(
            request: DoctorActionRequestIdentity,
            result: PolarisDoctorActionResult
        ): DoctorActionReceipt? = synchronized(doctorActionLock) {
            if (!DoctorActionReceiptStore.responseIdentityMatches(
                    current = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    activeGeneration = doctorActionGeneration,
                    request = request,
                    result = result
                )) {
                return@synchronized null
            }
            val pending = doctorReceipt?.takeIf { it.verificationPending } ?: return@synchronized null
            val stopped = DoctorActionReceiptStore.stopVerification(
                receipt = pending,
                result = result,
                nowEpochMs = System.currentTimeMillis()
            )
            doctorReceipt = stopped
            DoctorActionReceiptStore.save(prefs, stopped)
            stopped
        }

        fun retireDoctorUndo(
            request: DoctorActionRequestIdentity,
            result: PolarisDoctorActionResult
        ): DoctorActionReceipt? = synchronized(doctorActionLock) {
            if (!DoctorActionReceiptStore.responseIdentityMatches(
                    current = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    activeGeneration = doctorActionGeneration,
                    request = request,
                    result = result
                )) {
                return@synchronized null
            }
            val current = doctorReceipt?.takeIf {
                it.scopeId == request.scopeId && it.runId == request.runId
            } ?: return@synchronized null
            val retired = DoctorActionReceiptStore.retireUndo(
                receipt = current,
                result = result,
                nowEpochMs = System.currentTimeMillis()
            )
            doctorReceipt = retired
            DoctorActionReceiptStore.save(prefs, retired)
            retired
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
                hudOpacityPercent = NovaHudPreferences.readOpacityPercent(prefs),
                menuOpacityPercent = NovaMenuPreferences.readOpacityPercent(prefs),
                perfOverlayEnabled = game.prefConfig.enablePerfOverlay,
                onscreenControllerEnabled = game.prefConfig.onscreenController,
                keyboardVisible = game.isKeyboardLayoutVisible,
                mouseModeLabel = game.currentMouseModeLabel ?: "",
                allowChangeMouseMode = game.allowChangeMouseMode,
                isOnExternalDisplay = game.isOnExternalDisplay,
                fallbackBitrateKbps = game.prefConfig.bitrate,
                fallbackTargetFps = game.configuredHudTargetFps.toDouble(),
                doctorReceipt = DoctorActionReceiptStore.visibleReceipt(
                    receipt = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    validatedScopeId = doctorReceiptValidatedScopeId
                )
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
                NovaQuickMenuActionId.QUICK_INSERT -> keys(KeyboardTranslator.VK_INSERT)
                NovaQuickMenuActionId.QUICK_META -> keys(KeyboardTranslator.VK_LWIN)
                NovaQuickMenuActionId.QUICK_CTRL_V -> keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V)
                NovaQuickMenuActionId.QUICK_CTRL_1 -> keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_1)
                NovaQuickMenuActionId.QUICK_CTRL_2 -> keys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_2)
                else -> return
            }
            dismiss()
            sendKeysWithFocus(quickKeys)
        }

        fun doctorResultMessage(result: PolarisDoctorActionResult): String {
            if (result.message.isNotBlank()) return result.message
            return when (result.state) {
                "stable" -> game.getString(R.string.nova_quick_menu_doctor_stable)
                "confirmed_pressure" -> game.getString(R.string.nova_quick_menu_doctor_confirmed)
                "watching" -> game.getString(R.string.nova_quick_menu_doctor_watching)
                "resolved" -> game.getString(R.string.nova_quick_menu_doctor_resolved)
                "queued" -> game.getString(R.string.nova_quick_menu_doctor_recovery_queued)
                "applied" -> game.getString(R.string.nova_quick_menu_doctor_recovery_applied)
                "expired" -> game.getString(R.string.nova_quick_menu_doctor_recovery_expired)
                "rejected" -> game.getString(R.string.nova_quick_menu_doctor_recovery_rejected)
                "needs_attention" -> game.getString(R.string.nova_quick_menu_doctor_needs_attention)
                "undone" -> game.getString(
                    if (result.appUuid.isNotBlank()) {
                        R.string.nova_quick_menu_doctor_recovery_undone
                    } else {
                        R.string.nova_quick_menu_doctor_undone
                    }
                )
                else -> result.error.takeIf { it.isNotBlank() }
                    ?: game.getString(R.string.nova_quick_menu_doctor_failed)
            }
        }

        fun undoDoctorRun(receipt: DoctorActionReceipt) {
            val client = apiClient ?: return
            val canAdjustHostTuning = sessionStatus?.canAdjustHostTuning == true
            val canCancelLegacyRecovery = receipt.runId.startsWith("recovery-run-")
            if ((!canAdjustHostTuning && !canCancelLegacyRecovery) ||
                receipt.runId.isBlank() || receipt.undoActionId.isBlank() ||
                doctorActionPendingRegistry.isPending()
            ) {
                return
            }
            val undoRequest = beginDoctorUndo(
                receipt,
                canAdjustHostTuning || canCancelLegacyRecovery
            ) ?: return
            game.launchReplacingRuntimeIo("NovaQuickMenuDoctorUndo") {
                val latestStatus = client.getSessionStatus()
                if (!acceptRefreshedSessionStatus(latestStatus) ||
                    (latestStatus?.canAdjustHostTuning != true && !canCancelLegacyRecovery) ||
                    !requestIsCurrent(undoRequest)
                ) {
                    game.runOnMainIfRuntimeActive {
                        doctorActionPendingRegistry.clearIfOwned(undoRequest.generation)
                        doctorMenuRefreshRegistry.dispatch()
                    }
                    return@launchReplacingRuntimeIo
                }
                val result = client.runDoctorAction(
                    actionId = receipt.undoActionId,
                    appSessionId = undoRequest.appSessionId,
                    runId = receipt.runId
                )
                val updated = when {
                    result == null -> null
                    result.status -> storeDoctorResult(undoRequest, result)
                    else -> retireDoctorUndo(undoRequest, result)
                }
                if (result?.status == true && updated != null) {
                    acceptRefreshedSessionStatus(client.getSessionStatus())
                }
                game.runOnMainIfRuntimeActive {
                    doctorActionPendingRegistry.clearIfOwned(undoRequest.generation)
                    val canPresentHere = menuValidationIsCurrent() && dialog === overlay && overlay.isShowing
                    if (canPresentHere) {
                        if (result?.status == true && updated != null) {
                            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
                            doctorVerificationRunnable = null
                            NovaSnackbar.showSuccess(game, doctorResultMessage(result), anchor = composeView)
                        } else if (requestIsCurrent(undoRequest)) {
                            NovaSnackbar.showError(
                                game,
                                result?.error?.takeIf { it.isNotBlank() }
                                    ?: game.getString(R.string.nova_quick_menu_doctor_failed),
                                anchor = composeView
                            )
                        }
                    }
                    doctorMenuRefreshRegistry.dispatch()
                }
            }
        }

        fun presentDoctorResult(result: PolarisDoctorActionResult, receipt: DoctorActionReceipt?) {
            val message = doctorResultMessage(result)
            if (!result.status) {
                NovaSnackbar.showError(game, message, anchor = composeView)
                return
            }
            if ((sessionStatus?.canAdjustHostTuning == true ||
                    receipt?.runId?.startsWith("recovery-run-") == true) &&
                receipt?.undoAvailable == true &&
                receipt.runId.isNotBlank() &&
                receipt.undoActionId.isNotBlank()
            ) {
                NovaSnackbar.showSuccessWithAction(
                    activity = game,
                    message = message,
                    actionLabel = game.getString(R.string.nova_quick_menu_doctor_undo),
                    anchor = composeView,
                    onAction = { undoDoctorRun(receipt) }
                )
            } else {
                NovaSnackbar.showSuccess(game, message, anchor = composeView)
            }
        }

        scheduleDoctorVerification = fun(receipt: DoctorActionReceipt?) {
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
            if (!menuValidationIsCurrent() || dialog?.isShowing != true) return
            val client = apiClient ?: return
            val pending = receipt?.takeIf { it.verificationPending } ?: return
            val scopeIsValidated = synchronized(doctorActionLock) {
                doctorReceiptScopeId == pending.scopeId && doctorReceiptValidatedScopeId == pending.scopeId
            }
            if (!scopeIsValidated) return
            val request = requestIdentity(pending.runId, pending.verificationActionId) ?: return
            val delayMs = DoctorActionReceiptStore.nextVerificationDelayMs(
                pending,
                System.currentTimeMillis()
            )
            if (delayMs < 0L) return

            val runnable = Runnable {
                doctorVerificationRunnable = null
                if (!menuValidationIsCurrent() ||
                    dialog?.isShowing != true ||
                    !requestIsCurrent(request) ||
                    !doctorActionPendingRegistry.begin(request.generation)
                ) {
                    return@Runnable
                }
                game.launchReplacingRuntimeIo("NovaQuickMenuDoctorVerify") {
                    val latestStatus = client.getSessionStatus()
                    if (!acceptRefreshedSessionStatus(latestStatus) ||
                        latestStatus?.canAdjustHostTuning != true ||
                        !requestIsCurrent(request)
                    ) {
                        game.runOnMainIfRuntimeActive {
                            doctorActionPendingRegistry.clearIfOwned(request.generation)
                            doctorMenuRefreshRegistry.dispatch()
                        }
                        return@launchReplacingRuntimeIo
                    }
                    val verification = client.runDoctorAction(
                        actionId = pending.verificationActionId,
                        appSessionId = request.appSessionId,
                        runId = pending.runId
                    )
                    val updated = when {
                        verification == null -> deferDoctorVerification(request)
                        !verification.status -> stopDoctorVerification(request, verification)
                        else -> storeDoctorResult(request, verification)
                    }
                    if (verification?.status == true && updated != null) {
                        acceptRefreshedSessionStatus(client.getSessionStatus())
                    }
                    game.runOnMainIfRuntimeActive {
                        doctorActionPendingRegistry.clearIfOwned(request.generation)
                        if (!requestIsCurrent(request) || !menuValidationIsCurrent()) {
                            doctorMenuRefreshRegistry.dispatch()
                            return@runOnMainIfRuntimeActive
                        }
                        if (verification != null && updated != null && dialog?.isShowing == true) {
                            presentDoctorResult(verification, updated)
                        }
                        doctorMenuRefreshRegistry.dispatch()
                    }
                }
            }
            doctorVerificationRunnable = runnable
            game.window.decorView.postDelayed(runnable, delayMs)
        }

        doctorMenuRefreshRegistry.attach(menuValidationGeneration) {
            if (menuValidationIsCurrent() && dialog?.isShowing == true) {
                scheduleDoctorVerification(currentDoctorReceipt())
                refreshState()
            }
        }

        fun executeConfirmedDoctorAction(doctor: PolarisSessionStatus.DoctorStatus, client: PolarisApiClient) {
            game.launchReplacingRuntimeIo("NovaQuickMenuDoctorAction") {
                val latestStatus = client.getSessionStatus()
                if (!acceptRefreshedSessionStatus(latestStatus) ||
                    latestStatus?.canAdjustHostTuning != true ||
                    latestStatus.doctor.matchesConfirmedAction(doctor).not()
                ) {
                    return@launchReplacingRuntimeIo
                }
                val scope = synchronized(doctorActionLock) { doctorReceiptScopeId }
                    ?: return@launchReplacingRuntimeIo
                val request = beginNewDoctorRequest(scope, doctor.actionId) ?: return@launchReplacingRuntimeIo
                val result = client.runDoctorAction(
                    actionId = doctor.actionId,
                    appSessionId = request.appSessionId,
                    appUuid = doctor.actionAppUuid,
                    sourceResultId = doctor.resultId,
                    targetBitrateKbps = doctor.targetBitrateKbps,
                    confirmed = doctor.requiresConfirmation
                )
                val readOnlySuccess = result?.let {
                    DoctorActionReceiptStore.successfulReadOnlyNewRunResult(request, it)
                } == true
                val receipt = result
                    ?.takeUnless { readOnlySuccess }
                    ?.let { storeDoctorResult(request, it) }
                if (receipt != null || readOnlySuccess) {
                    acceptRefreshedSessionStatus(client.getSessionStatus())
                }
                game.runOnMainIfRuntimeActive {
                    doctorActionPendingRegistry.clearIfOwned(request.generation)
                    val canPresentHere = requestIsCurrent(request) &&
                        menuValidationIsCurrent() && dialog === overlay && overlay.isShowing
                    if (canPresentHere) {
                        if (result == null) {
                            NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_doctor_failed), anchor = composeView)
                        } else if (receipt != null) {
                            presentDoctorResult(result, receipt)
                        } else if (readOnlySuccess) {
                            presentDoctorResult(result, receipt = null)
                        } else {
                            NovaSnackbar.showError(
                                game,
                                result.error.takeIf { it.isNotBlank() }
                                    ?: game.getString(R.string.nova_quick_menu_doctor_failed),
                                anchor = composeView
                            )
                        }
                    }
                    doctorMenuRefreshRegistry.dispatch()
                }
            }
        }

        fun runDoctorAction() {
            val status = sessionStatus
            val doctor = status?.doctor
            val client = apiClient
            if (client == null || status == null || doctor == null || !doctor.canExecuteAction ||
                !status.canAdjustHostTuning ||
                doctorActionPendingRegistry.isPending()
            ) {
                game.copyNovaHudDiagnostics()
                return
            }
            if (doctor.requiresConfirmation) {
                // Legacy next-launch recovery confirmations are intentionally
                // non-executable. Current Auto Fix actions are reversible
                // same-stream changes and do not use this confirmation path.
                game.copyNovaHudDiagnostics()
                return
            }
            executeConfirmedDoctorAction(doctor, client)
        }

        val callbacks = NovaQuickMenuCallbacks(
            onDismiss = { dismiss() },
            onDisconnect = {
                haptic {
                    dismiss()
                    game.disconnect()
                }
            },
            onEndStream = {
                haptic {
                    if (sessionStatus?.isViewer != true && sessionStatus?.isShuttingDown == true) {
                        NovaSnackbar.show(game, game.getString(R.string.nova_quick_menu_shutdown_already_running), anchor = composeView)
                        return@haptic
                    }
                    if (sessionStatus?.isViewer != true && sessionStatus?.canQuit == false) {
                        NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_host_session_unavailable), anchor = composeView)
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
                    // This shortcut shares the same evidence-gated Doctor
                    // path. It cannot directly toggle AI, alter bitrate, or
                    // relaunch with a historical profile.
                    runDoctorAction()
                }
            },
            onSyncStatus = {
                haptic {
                    if (apiClient == null) return@haptic
                    if (sessionStatus?.syncStatus?.needsRelaunch == true) {
                        dismiss()
                        NovaSnackbar.show(game, game.getString(R.string.nova_quick_menu_relaunching_sync), anchor = composeView)
                        game.relaunchStream()
                        return@haptic
                    }
                    game.launchRuntimeIo("NovaQuickMenuSyncStatus") {
                        acceptRefreshedSessionStatus(apiClient.getSessionStatus())
                        game.runOnMainIfRuntimeActive {
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
                    if (status == null || apiClient == null || !aiSupported || !status.canAdjustHostTuning) {
                        return@haptic
                    }
                    val next = !aiEnabled
                    aiEnabled = next
                    refreshState()
                    game.launchRuntimeIo("NovaQuickMenuAiAutoQuality") {
                        val success = apiClient.setAiAutoQualityEnabled(next)
                        if (success) {
                            acceptRefreshedSessionStatus(apiClient.getSessionStatus())
                        }
                        game.runOnMainIfRuntimeActive {
                            if (!success) {
                                aiEnabled = !next
                                NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_ai_toggle_failed), anchor = composeView)
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
                            acceptRefreshedSessionStatus(apiClient.getSessionStatus())
                        }
                        game.runOnMainIfRuntimeActive {
                            profileClearInProgress = false
                            val message = when (cleared) {
                                true -> R.string.nova_library_reset_game_profile_cleared
                                false -> R.string.nova_library_reset_game_profile_empty
                                null -> R.string.nova_library_reset_game_profile_failed
                            }
                            NovaSnackbar.show(game, game.getString(message), anchor = composeView)
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
                        NovaSnackbar.show(
                            game,
                            game.getString(R.string.nova_mangohud_warning_big_picture),
                            Snackbar.LENGTH_LONG,
                            anchor = composeView
                        )
                    }
                    game.launchRuntimeIo("NovaQuickMenuMangoHud") {
                        val success = apiClient.setMangoHud(gameUuid, next)
                        if (success) {
                            acceptRefreshedSessionStatus(apiClient.getSessionStatus())
                        }
                        game.runOnMainIfRuntimeActive {
                            if (!success) {
                                mangoHudEnabled = !next
                                NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_mangohud_failed), anchor = composeView)
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
                    NovaSnackbar.showSuccess(
                        game,
                        game.getString(R.string.nova_quick_menu_profile_preference_saved),
                        anchor = composeView
                    )
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
                        NovaQuickMenuActionId.DIAGNOSE_STREAM -> {
                            runDoctorAction()
                        }
                        NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS -> {
                            game.copyNovaHudDiagnostics()
                        }
                        else -> Unit
                    }
                    refreshState()
                }
            },
            onDoctorUndo = {
                haptic {
                    DoctorActionReceiptStore.visibleReceipt(
                        receipt = doctorReceipt,
                        activeScopeId = doctorReceiptScopeId,
                        validatedScopeId = doctorReceiptValidatedScopeId
                    )?.takeIf {
                        (sessionStatus?.canAdjustHostTuning == true ||
                            it.runId.startsWith("recovery-run-")) &&
                            it.undoAvailable && it.runId.isNotBlank() && it.undoActionId.isNotBlank()
                    }?.let(::undoDoctorRun)
                }
            },
            onHudOpacityChange = { percent ->
                haptic {
                    game.launchRuntimeIo("NovaQuickMenuHudOpacity") {
                        NovaHudPreferences.writeOpacityPercent(game, percent)
                        game.runOnMainIfRuntimeActive {
                            refreshState()
                        }
                    }
                }
            },
            onMenuOpacityChange = { percent ->
                haptic {
                    game.launchRuntimeIo("NovaQuickMenuMenuOpacity") {
                        NovaMenuPreferences.writeOpacityPercent(game, percent)
                        game.runOnMainIfRuntimeActive {
                            refreshState()
                        }
                    }
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
            NovaComposeTheme(menuOpacityPercent = uiState.menuOpacity.percent) {
                NovaQuickMenuDrawer(
                    state = uiState,
                    callbacks = callbacks
                )
            }
        }

        dialog = overlay
        overlay.show()

        if (apiClient != null) {
            game.launchReplacingRuntimeIo("NovaQuickMenuStateRefresh") {
                try {
                    val refreshedCapabilities = apiClient.getCapabilities()
                    val refreshedSessionStatus = apiClient.getSessionStatus()
                    val accepted = doctorMenuRefreshRegistry.runIfCurrent(menuValidationGeneration) {
                        synchronized(doctorActionLock) {
                            capabilities = refreshedCapabilities
                            sessionStatus = refreshedSessionStatus
                            val polarisSessionApiAvailable = sessionStatus != null
                            adaptiveSupported = capabilities?.features?.adaptiveBitrateControl == true || polarisSessionApiAvailable
                            aiSupported = capabilities?.features?.aiAutoQualityControl == true ||
                                capabilities?.features?.aiOptimizerControl == true
                            hostStateUnavailable = false
                            syncSessionDerivedState()
                            syncDoctorReceiptScope()
                            true
                        }
                    } ?: false
                    if (!accepted) return@launchReplacingRuntimeIo

                    game.runOnMainIfRuntimeActive {
                        if (!menuValidationIsCurrent()) return@runOnMainIfRuntimeActive
                        scheduleDoctorVerification(doctorReceipt)
                        refreshState()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Quick menu state refresh failed: ${e.message}")
                    val accepted = doctorMenuRefreshRegistry.runIfCurrent(menuValidationGeneration) {
                        synchronized(doctorActionLock) {
                            hostStateUnavailable = true
                            true
                        }
                    } ?: false
                    if (accepted) {
                        game.runOnMainIfRuntimeActive {
                            if (menuValidationIsCurrent()) refreshState()
                        }
                    }
                }
            }
        } else {
            refreshState()
        }

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
