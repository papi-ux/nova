package com.papi.nova.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
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
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.novaSurfaces
import com.papi.nova.utils.DeviceUtils
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The Command Center: a panel at the start edge over the stream, or over the companion deck when
 * [surfaces] belong to a companion display. Its root page is the session, Doctor, overlays,
 * controls and quick keys; Mouse Mode, More Keys and More Controls are pages pushed inside it, so
 * choosing one never closes the Command Center and B returns to the row that opened it.
 */
class NovaQuickMenu(
    private val game: Game,
    private val surfaces: NovaSurfaces = game.novaSurfaces,
) : Game.GameMenuCallbacks {
    /** The Command Center that is open, or was open last. */
    private var session: MenuSession? = null
    /** Where the root page's focus and scroll were when it last closed, for the next opening. */
    private val rootPlace = NovaQuickMenuPlace()
    private val doctorActionLock = Any()
    private var doctorReceipt: DoctorActionReceipt? = null
    private var doctorReceiptScopeId: String? = null
    private var doctorReceiptValidatedScopeId: String? = null
    /** A reading validated during this opening may stay visible while a later poll fails. */
    private var doctorReceiptDisplayScopeId: String? = null
    private var doctorActionGeneration: Long = 0L
    private val doctorMenuRefreshRegistry = DoctorMenuRefreshRegistry()
    private val doctorActionPendingRegistry = DoctorActionPendingRegistry()
    private var doctorVerificationRunnable: Runnable? = null
    /** The stream's runtime, where the Command Center's work runs. */
    private val runtime = NovaCommandCenterRuntime.of(game)

    /**
     * One opening of the Command Center: the controller that opened it, for More Controls, and
     * what the open pages lend the host code (their scope, for closing and waiting on the
     * stream's focus, and a view in the panel window for snackbars).
     */
    private class MenuSession(val device: GameInputDevice?, val rootKey: String) {
        var scope: NovaPageScope? = null
        var bitrateJob: kotlinx.coroutines.Job? = null
        var anchorRef: WeakReference<View>? = null
        val anchor: View? get() = anchorRef?.get()
    }

    override fun showMenu(device: GameInputDevice?) {
        open(device, keysAsRoot = false)
    }

    /** Opens the Command Center on its Keys page, as the companion deck's Quick Keys does. */
    fun showKeys() {
        open(device = null, keysAsRoot = true)
    }

    private fun open(device: GameInputDevice?, keysAsRoot: Boolean) {
        if (isMenuOpen()) return
        val menuValidationGeneration = doctorMenuRefreshRegistry.open()
        synchronized(doctorActionLock) {
            doctorReceiptValidatedScopeId = null
            doctorReceiptDisplayScopeId = null
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
        }

        val rootKey = if (keysAsRoot) CommandCenterPage.KeysKey else CommandCenterPage.RootKey
        val menu = MenuSession(device, rootKey)
        val commandClient = game.novaApiClient
        val commandConnection = game.conn
        session = menu
        // The panel window keeps A, B and focus to itself and hands input back to the stream (or
        // the deck) when it closes; what is left here is the Command Center's own teardown.
        // The HUD's figures read through the panel's glass and collided with its title and header
        // buttons, so the HUD steps away while the Command Center is open.
        game.setNovaHudCovered(true)
        fun onMenuClosed() {
            menu.bitrateJob?.cancel()
            game.setNovaHudCovered(false)
            game.cancelRuntimeTask("NovaQuickMenuLiveTuning")
            if (doctorMenuRefreshRegistry.close(menuValidationGeneration)) {
                synchronized(doctorActionLock) {
                    doctorReceiptValidatedScopeId = null
                    doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
                    doctorVerificationRunnable = null
                }
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
        // This opening's hold on the host's status, where every status read lands: the status
        // the client has now, the last reading the host sent, whether the newest read failed, and
        // whether the host is Polaris at all, known when the Command Center opens.
        val host = NovaCommandCenterHostStatus(
            api = apiClient,
            registry = doctorMenuRefreshRegistry,
            generation = menuValidationGeneration,
            polarisServer = game::novaIsPolarisServer,
            runtime = runtime,
        )
        val prefs = PreferenceManager.getDefaultSharedPreferences(game)

        val sessionStatus by host::status
        var capabilities: PolarisCapabilities? = null
        var adaptiveSupported = false
        var aiSupported = false
        var adaptiveEnabled = false
        var aiEnabled = false
        var mangoHudEnabled = false
        var stabilityApplied = false
        var advancedTuningVisible = false
        var profileClearInProgress = false
        var profileClearResult: String? = null
        var diagnosticsCopied = false
        // Results said in their rows' own captions for a moment, where snackbars had floated.
        var launchPresetSaved = false
        var launchPresetSaves = 0
        lateinit var scheduleDoctorVerification: (DoctorActionReceipt?) -> Unit

        fun menuValidationIsCurrent(): Boolean =
            doctorMenuRefreshRegistry.isCurrent(menuValidationGeneration)

        // Whether this opening of the Command Center is still on screen.
        fun menuShowing(): Boolean = showingNow(menu)

        fun syncSessionDerivedState() {
            adaptiveEnabled = sessionStatus?.liveTuning?.enabled ?: (sessionStatus?.tuning?.adaptiveBitrateEnabled == true || sessionStatus?.adaptiveBitrateEnabled == true)
            aiEnabled = adaptiveEnabled
            mangoHudEnabled = sessionStatus?.tuning?.mangohudConfigured == true ||
                sessionStatus?.mangohudConfigured == true
        }

        fun syncDoctorReceiptScope() {
            val status = sessionStatus
            if (status == null) {
                // A failed read grants no authority, but does not end the last observed session.
                // Keep its receipt in place; action and verification paths still require validation.
                synchronized(doctorActionLock) { doctorReceiptValidatedScopeId = null }
                return
            }
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
                doctorReceiptDisplayScopeId = nextScope
            }
        }

        // What the page derives from each status the host sends.
        host.derive = {
            syncSessionDerivedState()
            syncDoctorReceiptScope()
        }

        // All menu/receipt publication runs on Main. Async completions resolve
        // the current store there, so an earlier GET cannot replay old state.
        fun publishCurrentSessionStatus(): Boolean = host.publish()

        suspend fun acceptRefreshedSessionStatus(@Suppress("UNUSED_PARAMETER") refreshed: PolarisSessionStatus?): Boolean {
            var accepted = false
            game.runOnMainIfRuntimeActive { accepted = publishCurrentSessionStatus() }
            return accepted
        }

        fun canExecuteDoctorAction(
            status: PolarisSessionStatus,
            doctor: PolarisSessionStatus.DoctorStatus
        ): Boolean {
            val readOnlyRecheck = doctor.actionId in setOf("recheck_network", "recheck_pacing")
            val exactStreamScope = status.appSessionIdPresent &&
                status.appSessionId.isNotBlank() && status.sessionGeneration > 0L &&
                doctor.actionAppSessionId == status.appSessionId &&
                doctor.actionSessionGeneration == status.sessionGeneration
            if (!exactStreamScope) return false
            return if (readOnlyRecheck) {
                status.authorityContractValid && status.ownedByClient && !status.isViewer
            } else {
                status.canAdjustHostTuning
            }
        }

        fun requestIdentity(runId: String, actionId: String): DoctorActionRequestIdentity? = synchronized(doctorActionLock) {
            val scope = doctorReceiptScopeId ?: return@synchronized null
            DoctorActionRequestIdentity(
                scopeId = scope,
                runId = runId,
                generation = doctorActionGeneration,
                appSessionId = sessionStatus?.appSessionId.orEmpty(),
                sessionGeneration = sessionStatus?.sessionGeneration ?: 0L,
                actionId = actionId
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
                sessionGeneration = sessionStatus?.sessionGeneration ?: 0L,
                actionId = actionId,
                requestId = if (actionId in setOf("lower_bitrate", "restore_quality")) {
                    UUID.randomUUID().toString()
                } else {
                    ""
                }
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
                scopeId = scopeId,
                runId = current.runId,
                generation = doctorActionGeneration,
                appSessionId = if (current.runId.startsWith("recovery-run-")) "" else sessionStatus?.appSessionId.orEmpty(),
                sessionGeneration = if (current.runId.startsWith("recovery-run-")) 0L else sessionStatus?.sessionGeneration ?: 0L,
                actionId = current.undoActionId
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
                nowEpochMs = System.currentTimeMillis(),
                sessionGeneration = request.sessionGeneration
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
            val gameUuid = currentGameUuid().orEmpty()
            val statusPreference = sessionStatus?.profileState?.preference
                ?.takeIf { it.isNotBlank() && it != "auto" }
            return if (gameName.isNullOrBlank() || gameUuid.isBlank()) {
                AutoQualityProfilePreferences.normalize(statusPreference)
            } else if (AutoQualityProfilePreferences.hasSaved(game, gameUuid, gameName)) {
                AutoQualityProfilePreferences.load(game, gameUuid, gameName)
            } else {
                AutoQualityProfilePreferences.normalize(statusPreference)
            }
        }

        // Static per locale; built once per open instead of once per refresh.
        val quickKeys = NovaQuickMenuUiState.quickKeyActions(game)
        // An opacity row shows its new value at once, while the preference every Nova surface
        // reads is written once the player stops stepping through the presets.
        var pendingHudOpacity: Int? = null
        var pendingMenuOpacity: Int? = null

        fun buildState(): NovaQuickMenuUiState {
            val gameName = currentProfileGameName()
            return NovaQuickMenuUiState.from(
                context = game,
                quickKeys = quickKeys,
                status = sessionStatus,
                polarisHost = host.polaris,
                lastStatus = host.last,
                commandKeysAllowed = game.canSendCommandKeys(),
                apiAvailable = apiClient != null,
                spaceSession = game.isSpaceSession(),
                hostStateUnavailable = host.unavailable,
                liveTuningPending = host.liveTuning?.pending == true,
                liveTuningUnconfirmed = host.liveTuning?.unconfirmed,
                adaptiveSupported = adaptiveSupported,
                aiSupported = aiSupported,
                adaptiveEnabled = adaptiveEnabled,
                aiEnabled = aiEnabled,
                mangoHudEnabled = mangoHudEnabled,
                stabilityApplied = stabilityApplied,
                advancedExpanded = advancedTuningVisible,
                profileClearInProgress = profileClearInProgress,
                profileClearResult = profileClearResult,
                currentGameName = gameName,
                currentGameUuid = currentGameUuid(),
                profilePreference = currentProfilePreference(gameName),
                launchPresetSaved = launchPresetSaved,
                hudShowing = game.isNovaHudShowing(),
                hudLeftPx = game.novaHudLeftPx,
                hudPositionCorner = game.novaHudPositionCorner,
                hudMode = NovaHudMode.fromPreference(prefs.getString("nova_polaris_hud_mode", "minimal")),
                hudOpacityPercent = pendingHudOpacity ?: NovaHudPreferences.readOpacityPercent(prefs),
                menuOpacityPercent = pendingMenuOpacity ?: NovaMenuPreferences.readOpacityPercent(prefs),
                perfOverlayEnabled = game.prefConfig.enablePerfOverlay,
                onscreenControllerEnabled = game.prefConfig.onscreenController,
                keyboardVisible = game.isKeyboardLayoutVisible,
                players = game.currentPlayers(),
                waitingGamepads = game.waitingGamepads(),
                multiController = game.prefConfig.multiController,
                mouseModeLabel = game.currentMouseModeLabel ?: "",
                allowChangeMouseMode = game.allowChangeMouseMode,
                isOnExternalDisplay = game.isOnExternalDisplay,
                fallbackBitrateKbps = game.prefConfig.bitrate,
                fallbackTargetFps = game.configuredHudTargetFps.toDouble(),
                doctorReceipt = DoctorActionReceiptStore.visibleReceipt(
                    receipt = doctorReceipt,
                    activeScopeId = doctorReceiptScopeId,
                    validatedScopeId = doctorReceiptDisplayScopeId
                )
            ).let { state ->
                (if (diagnosticsCopied) state.copy(diagnosis = state.diagnosis.copy(copied = true)) else state)
                    .copy(liveBitrate = game.novaLiveBitrate.state.value.let { picture ->
                        if (game.novaApiClient === commandClient && game.conn === commandConnection) picture
                        else picture.copy(rate = picture.rate.copy(canChange = false), reason = "Stream changed. Reopen Command Center")
                    })
            }
        }

        // A flow, not one state read at the top: each part of the page collects the slice it
        // shows, so a status refresh recomposes only what changed.
        val uiState = MutableStateFlow(buildState())
        host.redraw = { uiState.value = buildState() }
        fun refreshState() = host.refresh()

        fun sendQuickKey(actionId: NovaQuickMenuActionId) {
            if (!game.canSendCommandKeys()) return
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
            sendKeysWithFocus(quickKeys, commandClient, commandConnection)
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
                    sessionGeneration = undoRequest.sessionGeneration,
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
                    val canPresentHere = menuValidationIsCurrent() && menuShowing()
                    if (canPresentHere) {
                        if (result?.status == true && updated != null) {
                            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
                            doctorVerificationRunnable = null
                            NovaSnackbar.showSuccess(game, doctorResultMessage(result), anchor = menu.anchor)
                        } else if (requestIsCurrent(undoRequest)) {
                            NovaSnackbar.showError(
                                game,
                                result?.error?.takeIf { it.isNotBlank() }
                                    ?: game.getString(R.string.nova_quick_menu_doctor_failed),
                                anchor = menu.anchor
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
                NovaSnackbar.showError(game, message, anchor = menu.anchor)
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
                    anchor = menu.anchor,
                    onAction = { undoDoctorRun(receipt) }
                )
            } else {
                NovaSnackbar.showSuccess(game, message, anchor = menu.anchor)
            }
        }

        scheduleDoctorVerification = fun(receipt: DoctorActionReceipt?) {
            doctorVerificationRunnable?.let(game.window.decorView::removeCallbacks)
            doctorVerificationRunnable = null
            if (!menuValidationIsCurrent() || !menuShowing()) return
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
                    !menuShowing() ||
                    !requestIsCurrent(request) ||
                    !doctorActionPendingRegistry.begin(request.generation)
                ) {
                    return@Runnable
                }
                game.launchReplacingRuntimeIo("NovaQuickMenuDoctorVerify") {
                    val latestStatus = client.getSessionStatus()
                    val acceptedStatus = acceptRefreshedSessionStatus(latestStatus)
                    if (!requestIsCurrent(request)) {
                        game.runOnMainIfRuntimeActive {
                            doctorActionPendingRegistry.clearIfOwned(request.generation)
                            doctorMenuRefreshRegistry.dispatch()
                        }
                        return@launchReplacingRuntimeIo
                    }
                    if (!acceptedStatus || latestStatus?.canAdjustHostTuning != true) {
                        // A revoked owner scope or transient status failure must
                        // not leave a past-due receipt dispatching a zero-delay
                        // Verify loop. Back off and eventually surface the
                        // durable receipt as Needs attention without discarding
                        // its still-unresolved Undo metadata.
                        deferDoctorVerification(request)
                        game.runOnMainIfRuntimeActive {
                            doctorActionPendingRegistry.clearIfOwned(request.generation)
                            doctorMenuRefreshRegistry.dispatch()
                        }
                        return@launchReplacingRuntimeIo
                    }
                    val verification = client.runDoctorAction(
                        actionId = pending.verificationActionId,
                        appSessionId = request.appSessionId,
                        sessionGeneration = request.sessionGeneration,
                        runId = pending.runId
                    )
                    val updated = when {
                        verification == null -> deferDoctorVerification(request)
                        !verification.status ->
                            stopDoctorVerification(request, verification)
                                ?: deferDoctorVerification(request)
                        else -> storeDoctorResult(request, verification)
                            ?: deferDoctorVerification(request)
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
                        if (verification != null && updated != null && menuShowing()) {
                            presentDoctorResult(verification, updated)
                        }
                        doctorMenuRefreshRegistry.dispatch()
                    }
                }
            }
            doctorVerificationRunnable = runnable
            game.window.decorView.postDelayed(runnable, delayMs)
        }

        host.afterCurrentReading = NovaDoctorVerificationResume(
            scheduled = { doctorVerificationRunnable != null },
            pending = doctorActionPendingRegistry::isPending,
            schedule = { scheduleDoctorVerification(currentDoctorReceipt()) },
        )::resumeIfIdle

        doctorMenuRefreshRegistry.attach(menuValidationGeneration) {
            if (menuValidationIsCurrent() && menuShowing()) {
                scheduleDoctorVerification(currentDoctorReceipt())
                refreshState()
            }
        }

        fun executeConfirmedDoctorAction(doctor: PolarisSessionStatus.DoctorStatus, client: PolarisApiClient) {
            game.launchReplacingRuntimeIo("NovaQuickMenuDoctorAction") {
                val latestStatus = client.getSessionStatus()
                val latestDoctor = latestStatus?.doctor
                if (!acceptRefreshedSessionStatus(latestStatus) ||
                    latestStatus == null ||
                    latestDoctor == null ||
                    !canExecuteDoctorAction(latestStatus, latestDoctor) ||
                    latestDoctor.matchesExecutableActionIntent(doctor).not()
                ) {
                    game.runOnMainIfRuntimeActive {
                        if (menuValidationIsCurrent() && menuShowing()) {
                            refreshState()
                        }
                    }
                    return@launchReplacingRuntimeIo
                }
                val scope = synchronized(doctorActionLock) { doctorReceiptScopeId }
                    ?: return@launchReplacingRuntimeIo
                val request = beginNewDoctorRequest(scope, latestDoctor.actionId) ?: return@launchReplacingRuntimeIo
                val result = client.runDoctorAction(
                    actionId = latestDoctor.actionId,
                    appSessionId = request.appSessionId,
                    sessionGeneration = request.sessionGeneration,
                    appUuid = latestDoctor.actionAppUuid,
                    sourceResultId = latestDoctor.resultId,
                    targetBitrateKbps = latestDoctor.targetBitrateKbps,
                    controllerRevision = latestDoctor.actionControllerRevision,
                    evidenceRevision = latestDoctor.actionEvidenceRevision,
                    requestId = request.requestId,
                    confirmed = latestDoctor.requiresConfirmation
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
                        menuValidationIsCurrent() && menuShowing()
                    if (canPresentHere) {
                        if (result == null) {
                            NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_doctor_failed), anchor = menu.anchor)
                        } else if (receipt != null) {
                            presentDoctorResult(result, receipt)
                        } else if (readOnlySuccess) {
                            presentDoctorResult(result, receipt = null)
                        } else {
                            NovaSnackbar.showError(
                                game,
                                result.error.takeIf { it.isNotBlank() }
                                    ?: game.getString(R.string.nova_quick_menu_doctor_failed),
                                anchor = menu.anchor
                            )
                        }
                    }
                    doctorMenuRefreshRegistry.dispatch()
                }
            }
        }

        // The Doctor card's A copies the details when there is nothing to run; the card's chip
        // says Copied for a moment, where a floating Toast had said it.
        fun copyDiagnostics() {
            game.copyNovaHudDiagnostics()
            diagnosticsCopied = true
            refreshState()
            game.window.decorView.postDelayed({
                diagnosticsCopied = false
                refreshState()
            }, PROFILE_CLEAR_RESULT_SHOWN_MS)
        }

        fun runDoctorAction() {
            val status = sessionStatus
            val doctor = status?.doctor
            val client = apiClient
            if (client == null || status == null || doctor == null || !doctor.canExecuteAction ||
                !canExecuteDoctorAction(status, doctor) ||
                doctorActionPendingRegistry.isPending()
            ) {
                copyDiagnostics()
                return
            }
            if (doctor.requiresConfirmation) {
                // Legacy next-launch recovery confirmations are intentionally
                // non-executable. Current Auto Fix actions are reversible
                // same-stream changes and do not use this confirmation path.
                copyDiagnostics()
                return
            }
            executeConfirmedDoctorAction(doctor, client)
        }

        val sessionEnd = NovaCommandCenterEndSession(
            polaris = { host.polaris },
            status = { sessionStatus },
            space = game::isSpaceSession,
            standing = { menuValidationIsCurrent() && menuShowing() },
            close = ::dismiss,
            end = game::endSession,
            disconnect = game::disconnect,
            unavailable = { NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_host_session_unavailable), anchor = menu.anchor) },
            ending = { NovaSnackbar.show(game, game.getString(R.string.nova_quick_menu_shutdown_already_running), anchor = menu.anchor) },
        )

        fun bitrateMenuCurrent(): Boolean = menuValidationIsCurrent() && menuShowing() &&
            game.novaApiClient === commandClient && game.conn === commandConnection
        val bitrateAction = game.novaBitrateAction(::bitrateMenuCurrent)
        fun changeBitrate(token: com.papi.nova.manager.NovaLiveBitrateToken?, direction: Int? = null, kbps: Int? = null) =
            bitrateAction(token, direction, kbps)
        val callbacks = NovaQuickMenuCallbacks(
            onBitrateStep = { token, direction -> changeBitrate(token, direction = direction) },
            onBitrateRecommended = { token -> changeBitrate(token) },
            onBitrateExact = { picture ->
                val rate = picture.rate
                if (bitrateMenuCurrent() && rate.canChange && !rate.busy) {
                    surfaces.panel.push(com.papi.nova.ui.panel.NovaCommonPage.Form(
                        key = "stream-bitrate-exact", title = "Bitrate for this stream",
                        fields = listOf(com.papi.nova.ui.panel.NovaField("kbps", "Bitrate (kbps)",
                            rate.requestedKbps?.toString().orEmpty(), com.papi.nova.ui.panel.NovaFieldKind.Number)),
                        submitLabel = "Apply for this stream",
                        warning = "${rate.minimumKbps} to ${rate.maximumKbps} kbps",
                        onSubmit = { values ->
                            val value = values["kbps"]?.toIntOrNull()
                            when {
                                !bitrateMenuCurrent() || picture.token != game.novaLiveBitrate.state.value.token -> "Stream changed. Reopen Command Center"
                                value == null || value !in rate.minimumKbps..rate.maximumKbps -> "Enter a bitrate within this range"
                                else -> { changeBitrate(picture.token, kbps = value); null }
                            }
                        },
                    ))
                }
            },
            onDismiss = { dismiss() },
            onDisconnect = {
                haptic {
                    dismiss()
                    game.disconnect()
                }
            },
            onEndStream = { haptic(sessionEnd::perform) },
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
                        NovaSnackbar.show(game, game.getString(R.string.nova_quick_menu_relaunching_sync), anchor = menu.anchor)
                        game.relaunchStream()
                        return@haptic
                    }
                    game.launchRuntimeIo("NovaQuickMenuSyncStatus") {
                        acceptRefreshedSessionStatus(apiClient.getSessionStatus())
                        game.runOnMainIfRuntimeActive {
                            refreshState()
                        }
                    }
                }
            },
            // The state the split offered, not a flip of whatever the host says now; the result
            // is said in the row's own caption (NovaLiveTuningSave).
            onLiveTuning = host::switchLiveTuning,
            onToggleAdvanced = {
                haptic {
                    advancedTuningVisible = !advancedTuningVisible
                    refreshState()
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
                            // Said in the row's own caption, where the clear was asked for.
                            val result = game.getString(message)
                            profileClearResult = result
                            refreshState()
                            game.window.decorView.postDelayed({
                                if (profileClearResult == result) {
                                    profileClearResult = null
                                    refreshState()
                                }
                            }, PROFILE_CLEAR_RESULT_SHOWN_MS)
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
                            anchor = menu.anchor
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
                                NovaSnackbar.showError(game, game.getString(R.string.nova_quick_menu_mangohud_failed), anchor = menu.anchor)
                            }
                            refreshState()
                        }
                    }
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
                            // The two overlays share the top-left corner, so the legacy text
                            // replaces Nova HUD, and the swap is remembered for the next
                            // stream: toggleNovaHud persists, dismissNovaHud did not.
                            if (!game.prefConfig.enablePerfOverlay && game.isNovaHudShowing()) {
                                game.toggleNovaHud()
                            }
                            game.toggleHUD()
                        }
                        NovaQuickMenuActionId.DIAGNOSE_STREAM -> {
                            runDoctorAction()
                        }
                        NovaQuickMenuActionId.COPY_HUD_DIAGNOSTICS -> {
                            copyDiagnostics()
                        }
                        else -> Unit
                    }
                    refreshState()
                }
            },
            onHudModeSelect = { mode ->
                haptic {
                    game.setNovaHudMode(mode)
                    refreshState()
                }
            },
            onHudPreview = { previewing -> game.setNovaHudPreviewing(previewing) },
            onHudPositionSelect = { corner ->
                haptic {
                    game.setNovaHudPosition(corner)
                    refreshState()
                }
            },
            onHudPositionReset = {
                haptic {
                    game.resetNovaHudPosition()
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
                pendingHudOpacity = percent
                refreshState()
                writeAfterSteps(HUD_OPACITY_WRITE) {
                    game.launchRuntimeIo("NovaQuickMenuHudOpacity") {
                        NovaHudPreferences.writeOpacityPercent(game, percent)
                        game.runOnMainIfRuntimeActive {
                            if (pendingHudOpacity == percent) pendingHudOpacity = null
                            if (menuShowing()) refreshState()
                        }
                    }
                }
            },
            onMenuOpacityChange = { percent ->
                pendingMenuOpacity = percent
                refreshState()
                writeAfterSteps(MENU_OPACITY_WRITE) {
                    game.launchRuntimeIo("NovaQuickMenuMenuOpacity") {
                        NovaMenuPreferences.writeOpacityPercent(game, percent)
                        game.runOnMainIfRuntimeActive {
                            if (pendingMenuOpacity == percent) pendingMenuOpacity = null
                            if (menuShowing()) refreshState()
                        }
                    }
                }
            },
            onControlAction = { actionId ->
                haptic {
                    when (actionId) {
                        NovaQuickMenuActionId.MOUSE_MODE -> {
                            // More than four modes, so a page pushed in this panel: it opens on
                            // the current mode, and choosing one returns to this row.
                            if (game.allowChangeMouseMode) surfaces.panel.push(mouseModePage { refreshState() })
                        }
                        NovaQuickMenuActionId.CONTROLLER -> {
                            // A setting, and nothing else wants the screen: the drawer stays open
                            // and its own row shows the new state, so a wrong guess costs one tap
                            // rather than the whole menu and the place in it.
                            game.toggleVirtualController()
                            refreshState()
                        }
                        NovaQuickMenuActionId.KEYBOARD -> {
                            // The keyboard layout covers the screen and takes every touch on it,
                            // so it opens once the stream holds focus again.
                            closeThenOnStream(menu) { game.toggleFullKeyboard() }
                        }
                        NovaQuickMenuActionId.PLAYERS -> {
                            // The menu takes every pad's buttons while it is open, so nobody
                            // could join; it closes and says what to do instead.
                            closeThenOnStream(menu) {
                                game.reassignPlayers()
                                // Shown once the panel window is gone, so the snackbar lands
                                // on the stream instead of leaving with the menu.
                                // The panel's view is detached by then, so this falls back to the stream.
                                NovaSnackbar.show(
                                    game,
                                    game.getString(R.string.nova_quick_menu_players_reassigned),
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                                    anchor = menu.anchor
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            },
            onSessionAction = { actionId ->
                haptic {
                    when (actionId) {
                        NovaQuickMenuActionId.PASTE_CLIPBOARD -> {
                            // The text goes to the host and nothing here needs the screen, so the
                            // drawer stays where the hand left it.
                            game.sendClipboard(true)
                        }
                        NovaQuickMenuActionId.ROTATE_SCREEN -> {
                            // The rotation re-lays out everything under the panel, and the panel
                            // with it. It closes so the menu is not resized mid-turn.
                            closeThenOnStream(menu) { game.rotateScreen() }
                        }
                        NovaQuickMenuActionId.MORE_KEYS -> {
                            // The key list itself, pushed in this panel; B comes back here.
                            surfaces.panel.push(keysPage(menu, besideTheRoot = true))
                        }
                        NovaQuickMenuActionId.MORE_CONTROLS -> {
                            // The legacy Quick Menu's extras, pushed in this panel.
                            surfaces.panel.push(moreControlsPage(menu.device))
                        }
                        else -> Unit
                    }
                }
            }
        )

        val root: NovaPage = if (keysAsRoot) keysPage(menu, besideTheRoot = false) else CommandCenterPage.Root(uiState.value.title)
        surfaces.open(root, NovaEdge.Start) { page ->
            // Every page lends its scope, for closing and then waiting on the stream's focus, and
            // a view in the panel window, where snackbars about the menu belong.
            val view = LocalView.current
            SideEffect {
                menu.scope = this
                menu.anchorRef = WeakReference(view)
            }
            when (page) {
                is CommandCenterPage.Root -> NovaQuickMenuContent(state = uiState, callbacks = callbacks, place = rootPlace)
                is CommandCenterPage.Listing -> CommandCenterListingPage(page)
                is CommandCenterPage.MouseMode -> CommandCenterMouseModePage(page)
                else -> Unit
            }
        }
        game.lifecycleScope.launch {
            snapshotFlow { surfaces.panel.contains(rootKey) }.dropWhile { !it }.first { !it }
            onMenuClosed()
        }

        menu.bitrateJob = game.lifecycleScope.launch {
            game.novaLiveBitrate.state.collect { if (menuValidationIsCurrent() && menuShowing()) refreshState() }
        }
        if (apiClient != null) {
            game.launchReplacingRuntimeIo("NovaQuickMenuLiveTuning") {
                apiClient.sessionStatusUpdates.collect {
                    game.runOnMainIfRuntimeActive {
                        if (!menuValidationIsCurrent()) return@runOnMainIfRuntimeActive
                        publishCurrentSessionStatus()
                        refreshState()
                    }
                }
            }
            game.launchReplacingRuntimeIo("NovaQuickMenuStateRefresh") {
                try {
                    // Capabilities do not change inside a stream; one fetch per session,
                    // not one per open.
                    val refreshedCapabilities = capabilities ?: apiClient.getCapabilities()
                    apiClient.getSessionStatus()
                    game.runOnMainIfRuntimeActive {
                        if (!menuValidationIsCurrent()) return@runOnMainIfRuntimeActive
                        capabilities = refreshedCapabilities
                        adaptiveSupported = capabilities?.features?.adaptiveBitrateControl == true
                        aiSupported = capabilities?.features?.aiAutoQualityControl == true ||
                            capabilities?.features?.aiOptimizerControl == true
                        publishCurrentSessionStatus()
                        scheduleDoctorVerification(doctorReceipt)
                        refreshState()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Quick menu state refresh failed: ${e.message}")
                    game.runOnMainIfRuntimeActive {
                        if (menuValidationIsCurrent()) refreshState()
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
        val open = session ?: return false
        return surfaces.panel.contains(open.rootKey)
    }

    /** Closes the Command Center; the panel slides out and the window hands input back when it lands. */
    private fun dismiss() {
        if (isMenuOpen()) surfaces.panel.close()
    }

    /**
     * Closes the Command Center and runs [action] once the stream (or the deck) holds focus again,
     * for actions that need the window they act on: a keyboard, a rotation, a player prompt.
     */
    private fun closeThenOnStream(menu: MenuSession, action: () -> Unit) {
        val scope = menu.scope
        if (scope != null && showingNow(menu)) {
            scope.closeThen(awaitHostFocus = true, action = action)
        } else {
            dismiss()
            action()
        }
    }

    private fun showingNow(menu: MenuSession): Boolean = session === menu && isMenuOpen()

    private val pendingWrites = HashMap<String, Runnable>()

    /** Runs [write] once no new step has arrived for a moment, replacing a write still waiting. */
    private fun writeAfterSteps(key: String, write: () -> Unit) {
        val decor = game.window.decorView
        pendingWrites.remove(key)?.let(decor::removeCallbacks)
        val runnable = Runnable {
            pendingWrites.remove(key)
            write()
        }
        pendingWrites[key] = runnable
        decor.postDelayed(runnable, SETTING_WRITE_DEBOUNCE_MS)
    }

    /**
     * Mouse Mode at the Command Center's width: it opens on the current mode, one A applies a mode
     * and pops, and the local cursor switches in its own row after the modes.
     */
    private fun mouseModePage(onChosen: () -> Unit): CommandCenterPage.MouseMode = NovaMouseModeChoices.page(
        title = game.getString(R.string.nova_cc_mouse_mode),
        options = game.mouseModeChoices(),
        current = game.currentMouseModeChoice,
        onChoose = { choice ->
            game.chooseMouseMode(choice)
            onChosen()
        },
        localCursor = NovaLocalCursorRow(
            label = game.getString(R.string.nova_cc_local_cursor),
            caption = game.getString(R.string.nova_cc_local_cursor_caption),
            shown = game.isLocalCursorShown,
            onChange = { shown ->
                if (shown != game.isLocalCursorShown) game.chooseMouseMode(NovaMouseModeChoices.LocalCursor)
                onChosen()
            },
        ),
    )

    /**
     * The Keys page: the default special keys and the imported custom ones. Pushed from the
     * Command Center, [besideTheRoot], it leaves out the keys the root already offers, so each key
     * shows once; as the companion deck's own page it is the whole list.
     */
    private fun keysPage(menu: MenuSession, besideTheRoot: Boolean): CommandCenterPage.Keys {
        val commandClient = game.novaApiClient
        val commandConnection = game.conn
        val defaults = if (PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys) {
            emptyList()
        } else {
            NovaCommandCenterKeys.defaults(game).filterNot { besideTheRoot && it.key in NovaCommandCenterKeys.OnTheRoot }
        }
        val custom = NovaCommandCenterKeys.custom(game) { error ->
            LimeLog.warning("Nova: Custom keys could not be read: ${error.message}")
            NovaSnackbar.showError(game, game.getString(R.string.wrong_import_format), anchor = menu.anchor)
        }
        fun rows(keys: List<NovaCommandCenterKey>) = keys.map { key ->
            if (key.key == NovaCommandCenterKeys.CLOSE_APP_KEY) {
                // Alt + F4 closes the host's focused window, normally the game: never one A away (R3).
                NovaMenuItem.Destructive(
                    key = key.key,
                    label = key.label,
                    confirmLabel = game.getString(R.string.nova_cc_close_app),
                    consequence = game.getString(R.string.nova_cc_alt_f4_consequence),
                    onConfirm = { sendKeysWithFocus(key.codes, commandClient, commandConnection) },
                )
            } else {
                NovaMenuItem.Action(key = key.key, label = key.label,
                    onClick = { sendKeysWithFocus(key.codes, commandClient, commandConnection) })
            }
        }
        val sections = buildList {
            if (defaults.isNotEmpty()) add(CommandCenterSection(null, rows(defaults)))
            if (custom.isNotEmpty()) add(CommandCenterSection(game.getString(R.string.nova_cc_custom_keys), rows(custom)))
            if (isEmpty()) {
                add(
                    CommandCenterSection(
                        null,
                        listOf(
                            NovaMenuItem.Action(
                                key = "no-keys",
                                label = game.getString(R.string.nova_cc_keys_empty),
                                disabledReason = game.getString(R.string.nova_cc_keys_empty_reason),
                                onClick = {},
                            ),
                        ),
                    ),
                )
            }
        }
        return CommandCenterPage.Keys(game.getString(R.string.nova_quick_menu_special_keys), sections)
    }

    /** The host's server commands, each run once the stream holds focus again. */
    private fun serverCommandsPage(): CommandCenterPage.ServerCommands {
        val rows = game.serverCmds.mapIndexed { index, command ->
            NovaMenuItem.Action(key = "server-command-$index", label = command, onClick = { game.sendExecServerCmd(index) })
        }
        return CommandCenterPage.ServerCommands(
            game.getString(R.string.game_menu_server_cmd),
            listOf(CommandCenterSection(null, rows)),
        )
    }

    /**
     * Every extra the legacy Quick Menu had and the Command Center's own rows do not: settings
     * change in place, and actions that need the stream close the panel first.
     */
    private fun moreControlsPage(device: GameInputDevice?): CommandCenterPage.MoreControls {
        val commandClient = game.novaApiClient
        val commandConnection = game.conn
        fun switch(key: String, label: Int, current: Boolean, apply: (Boolean) -> Unit) = NovaMenuItem.Value(
            key = key,
            label = game.getString(label),
            options = listOf(
                NovaOption(false, game.getString(R.string.nova_cc_off)),
                NovaOption(true, game.getString(R.string.nova_cc_on)),
            ),
            current = current,
            onChange = apply,
        )
        val serverCommands = if (game.serverCmds.isEmpty()) {
            NovaMenuItem.Action(
                key = "server-commands",
                label = game.getString(R.string.game_menu_server_cmd),
                disabledReason = game.getString(R.string.game_dialog_message_server_cmd_empty),
                onClick = {},
            )
        } else {
            NovaMenuItem.Opens(
                key = "server-commands",
                label = game.getString(R.string.game_menu_server_cmd),
                page = ::serverCommandsPage,
            )
        }
        val host = CommandCenterSection(
            game.getString(R.string.nova_cc_host_section),
            listOf(
                serverCommands,
                NovaMenuItem.Action(
                    key = "fetch-clipboard",
                    label = game.getString(R.string.nova_cc_fetch_clipboard),
                    caption = game.getString(R.string.nova_cc_fetch_clipboard_caption),
                    onClick = { game.getClipboard(0) },
                ),
                NovaMenuItem.Action(
                    key = "task-manager",
                    label = game.getString(R.string.nova_cc_task_manager),
                    caption = game.getString(R.string.nova_cc_task_manager_caption),
                    onClick = {
                        sendKeysWithFocus(
                            shortArrayOf(
                                KeyboardTranslator.VK_LCONTROL.toShort(),
                                KeyboardTranslator.VK_LSHIFT.toShort(),
                                KeyboardTranslator.VK_ESCAPE.toShort(),
                            ),
                            commandClient,
                            commandConnection,
                        )
                    },
                ),
            ),
        )
        val touch = CommandCenterSection(
            game.getString(R.string.nova_cc_touch_section),
            listOfNotNull(
                NovaMenuItem.Action(
                    key = "android-keyboard",
                    label = game.getString(R.string.nova_cc_android_keyboard),
                    caption = game.getString(R.string.nova_cc_android_keyboard_caption),
                    onClick = { game.toggleKeyboard() },
                ),
                switch("zoom", R.string.nova_cc_zoom, game.isZoomModeEnabled) {
                    if (it != game.isZoomModeEnabled) game.toggleZoomMode()
                },
                // For touch players only: without a touchscreen, or on a TV, there is nothing to press.
                if (NovaTouchMenuButton.available(game)) {
                    switch("floating-button", R.string.nova_cc_floating_button, game.isFloatingButtonVisible) {
                        if (it != game.isFloatingButtonVisible) game.toggleFloatingButtonVisibility()
                    }
                } else {
                    null
                },
                switch("special-keys-layout", R.string.nova_cc_special_keys_layout, game.isKeyboardControllerShown) {
                    if (it != game.isKeyboardControllerShown) game.toggleKeyboardController()
                },
                switch("touch-sensitivity", R.string.nova_cc_touch_sensitivity, game.prefConfig.enableTouchSensitivity) {
                    if (it != game.prefConfig.enableTouchSensitivity) game.switchTouchSensitivity()
                },
            ),
        )
        val controller = device?.getGameMenuOptions()?.takeIf { it.isNotEmpty() }?.let {
            CommandCenterSection(game.getString(R.string.nova_cc_controller_section), it)
        }
        return CommandCenterPage.MoreControls(
            game.getString(R.string.nova_cc_more_controls),
            listOfNotNull(host, touch, controller),
        )
    }

    private fun sendKeysWithFocus(keys: ShortArray, client: PolarisApiClient?, connection: NvConnection?) {
        fun currentTarget() = game.novaApiClient === client && game.conn === connection && game.canSendCommandKeys()
        if (!currentTarget()) return
        game.window.decorView.postDelayed({
            if (currentTarget()) {
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
        // A held Left or Right steps through presets faster than this; only the last one is written.
        private const val SETTING_WRITE_DEBOUNCE_MS = 250L
        /** How long Clear Game Profile's caption says what the clear did. */
        private const val PROFILE_CLEAR_RESULT_SHOWN_MS = 4_000L
        private const val HUD_OPACITY_WRITE = "hud-opacity"
        private const val MENU_OPACITY_WRITE = "menu-opacity"
    }
}

/**
 * Where the Command Center's work runs: off the main thread, back on it while the stream stands,
 * and on it after a delay. The stream's own runtime in the app; a test's own in a test.
 */
internal interface NovaCommandCenterRuntime {
    fun launchIo(name: String, block: suspend () -> Unit)
    suspend fun onMain(block: () -> Unit)
    fun postDelayed(delayMs: Long, block: () -> Unit)

    companion object {
        /** [game]'s own: its runtime tasks, its main thread while it runs, and its window. */
        fun of(game: Game): NovaCommandCenterRuntime = object : NovaCommandCenterRuntime {
            override fun launchIo(name: String, block: suspend () -> Unit) = game.launchRuntimeIo(name) { block() }
            override suspend fun onMain(block: () -> Unit) = game.runOnMainIfRuntimeActive(block)
            override fun postDelayed(delayMs: Long, block: () -> Unit) {
                game.window.decorView.postDelayed(block, delayMs)
            }
        }
    }
}
