package com.papi.nova.ui

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisStreamDisplayMode
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.NovaFocusableCard
import com.papi.nova.utils.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Bottom sheet showing game details, tuning, and explicit launch modes.
 * Triggered when opening a game from the Polaris library.
 */
class NovaGameDetailSheet : BottomSheetDialogFragment() {

    private var game: PolarisGame? = null
    private var apiClient: PolarisApiClient? = null
    private var defaultToVirtualDisplay: Boolean = false
    private var clientSettings: PolarisClientSettings? = null
    private var onLaunch: ((PolarisGame, Boolean, Boolean, Boolean, String, JSONObject?) -> Unit)? = null

    companion object {
        fun newInstance(
            game: PolarisGame,
            apiClient: PolarisApiClient,
            defaultToVirtualDisplay: Boolean,
            clientSettings: PolarisClientSettings?,
            onLaunch: (PolarisGame, Boolean, Boolean, Boolean, String, JSONObject?) -> Unit
        ): NovaGameDetailSheet {
            return NovaGameDetailSheet().apply {
                this.game = game
                this.apiClient = apiClient
                this.defaultToVirtualDisplay = defaultToVirtualDisplay
                this.clientSettings = clientSettings
                this.onLaunch = onLaunch
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            background = NovaSheetChrome.createSheetBackground(requireContext())
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
        val game = this.game ?: return
        val apiClient = this.apiClient ?: return
        val composeView = view as? ComposeView ?: return
        val deviceName = DeviceUtils.getModel()

        var currentGame by mutableStateOf(game)
        var profilePreference by mutableStateOf(loadProfilePreference(currentGame))
        var uiState by mutableStateOf(buildUiState(currentGame, profilePreference))
        var mangoHudEnabled by mutableStateOf(game.mangohud)
        var resetWorking by mutableStateOf(false)
        var optimizationState by mutableStateOf(NovaGameDetailOptimizationState())
        var launchOptionsState by mutableStateOf<NovaLaunchOptionsState?>(null)
        var profileOptionsState by mutableStateOf<NovaProfilePreferenceOptionsState?>(null)
        var steamLaunchOptionsState by mutableStateOf<NovaSteamLaunchModeOptionsState?>(null)

        fun refreshUiState(preference: String = profilePreference) {
            uiState = buildUiState(currentGame, preference)
        }

        fun loadOptimization(preference: String, usesVirtualDisplay: Boolean = uiState.playUsesVirtualDisplay) {
            LimeLog.info(
                "Nova: Preflight optimization requested game=${currentGame.name} " +
                    "preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            android.util.Log.i(
                "NovaPreflight",
                "requested game=${currentGame.name} preference=$preference virtualDisplay=$usesVirtualDisplay"
            )
            viewLifecycleOwner.lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(requireContext(), apiClient, usesVirtualDisplay, clientSettings)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, preference)
                    }
                    logPreflightOptimization("Preflight optimization", opt, preference)
                    buildOptimizationState(opt, preference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Preflight optimization failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
            }
        }

        fun retryHighFpsTrial() {
            profilePreference = "high_fps"
            saveProfilePreference(currentGame, profilePreference)
            refreshUiState(profilePreference)
            viewLifecycleOwner.lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        syncLaunchPreflightSettings(requireContext(), apiClient, uiState.playUsesVirtualDisplay, clientSettings)?.let {
                            clientSettings = it
                        }
                        apiClient.getOptimization(deviceName, currentGame.name, profilePreference, "high_fps")
                    }
                    logPreflightOptimization("High FPS trial preflight", opt, profilePreference)
                    buildOptimizationState(opt, profilePreference)
                } catch (e: Exception) {
                    LimeLog.warning("Nova: High FPS trial preflight failed: ${e.message}")
                    NovaGameDetailOptimizationState()
                }
            }
        }

        fun selectLaunchMode(mode: String) {
            val allowed = when (mode) {
                "virtual_display" -> uiState.virtualDisplayAllowed && !uiState.virtualDisplayUnavailable
                else -> uiState.headlessAllowed
            }
            if (!allowed || mode == uiState.playMode) return

            val previousLaunchMode = currentGame.launchMode
            val allowedModes = previousLaunchMode?.allowedModes
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("headless", "virtual_display")
            val updatedLaunchMode = (previousLaunchMode ?: PolarisGame.LaunchModeContract()).copy(
                preferredMode = mode,
                allowedModes = allowedModes
            )
            currentGame = currentGame.copy(launchMode = updatedLaunchMode)
            refreshUiState()
            optimizationState = NovaGameDetailOptimizationState()
            loadOptimization(profilePreference, usesVirtualDisplay = mode == "virtual_display")
        }

        composeView.setContent {
            NovaComposeTheme {
                NovaGameDetailSheetContent(
                    uiState = uiState,
                    launchIntro = buildLaunchIntro(uiState),
                    recommendedBadge = getString(
                        R.string.nova_library_launch_recommended_mode_badge,
                        modeBadgeLabel(uiState.recommendedMode)
                    ),
                    lastPlayedText = lastPlayedText(currentGame),
                    profilePreferenceLabel = getString(AutoQualityProfilePreferences.labelRes(profilePreference)),
                    resetProfileLabel = getString(
                        if (resetWorking) {
                            R.string.nova_library_reset_game_profile_working
                        } else {
                            R.string.nova_library_reset_game_profile
                        }
                    ),
                    resetProfileWorking = resetWorking,
                    mangoHudEnabled = mangoHudEnabled,
                    mangoHudStatusLabel = getString(R.string.nova_mangohud_enabled_status),
                    mangoHudStatusCaption = getString(R.string.nova_mangohud_novahud_caption),
                    mangoHudWarning = uiState.mangoHudRisk != NovaGameDetailUiState.MangoHudRisk.NONE,
                    steamLaunchLabel = getString(R.string.nova_steam_launch_detail_label),
                    steamLaunchModeLabel = steamLaunchModeLabel(uiState.steamLaunchMode),
                    steamLaunchCaption = steamLaunchCaption(uiState),
                    optimizationState = optimizationState,
                    launchOptionsState = launchOptionsState,
                    profileOptionsState = profileOptionsState,
                    playLabel = if (optimizationState.reviewRequired) {
                        getString(R.string.nova_library_review_and_launch)
                    } else {
                        optimizationState.profileSummary
                            ?.primaryLaunchLabel
                            ?.takeIf { it.isNotBlank() }
                            ?: primaryPlayLabel(uiState)
                    },
                    launchOptionsLabel = getString(R.string.nova_library_launch_options_secondary),
                    launchModeTitle = getString(R.string.nova_library_launch_mode_title),
                    headlessModeLabel = modeBadgeLabel("headless"),
                    virtualDisplayModeLabel = modeBadgeLabel("virtual_display"),
                    coverContentDescription = getString(R.string.nova_a11y_game_cover),
                    onSheetHandleDismiss = { dismiss() },
                    onPrimaryLaunch = {
                        if (!uiState.playEnabled) return@NovaGameDetailSheetContent
                        fun launchConfirmed(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
                            onLaunch?.invoke(
                                currentGame.copy(mangohud = mangoHudEnabled),
                                uiState.playUsesVirtualDisplay,
                                mirrorDesktop,
                                forcePrivateAfterSteamClose,
                                profilePreference,
                                optimizationState.rawOptimization
                            )
                            dismiss()
                        }
                        val desktopSteamDecision = NovaDesktopSteamLaunchDecision.from(
                            uiState,
                            optimizationState.rawOptimization
                        )
                        if (desktopSteamDecision.required) {
                            showDesktopSteamLaunchDecision(
                                decision = desktopSteamDecision,
                                onPrivateStream = { launchConfirmed(mirrorDesktop = false, forcePrivateAfterSteamClose = false) },
                                onMirrorDesktop = { launchConfirmed(mirrorDesktop = true, forcePrivateAfterSteamClose = false) },
                                onForcePrivateAfterSteamClose = { launchConfirmed(mirrorDesktop = false, forcePrivateAfterSteamClose = true) }
                            )
                        } else if (optimizationState.reviewRequired) {
                            showPreflightReview(
                                optimizationState = optimizationState,
                                onLaunchConfirmed = { launchConfirmed(false) },
                                onRetryHighFps = { retryHighFpsTrial() },
                                onResetProfile = {
                                    resetWorking = true
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        withContext(Dispatchers.IO) {
                                            apiClient.clearOptimizerProfile(deviceName, currentGame.name)
                                        }
                                        optimizationState = NovaGameDetailOptimizationState()
                                        loadOptimization(profilePreference)
                                        resetWorking = false
                                    }
                                }
                            )
                        } else {
                            launchConfirmed(false)
                        }
                    },
                    onLaunchOptions = {
                        val nextState = showLaunchOptions(currentGame, uiState)
                        if (nextState == null) {
                            Toast.makeText(requireContext(), R.string.nova_library_no_launch_modes, Toast.LENGTH_SHORT).show()
                        } else {
                            launchOptionsState = nextState
                            profileOptionsState = null
                        }
                    },
                    onLaunchModeSelected = ::selectLaunchMode,
                    onLaunchOptionSelected = { option ->
                        fun launchSelected(mirrorDesktop: Boolean, forcePrivateAfterSteamClose: Boolean = false) {
                            onLaunch?.invoke(
                                currentGame.copy(mangohud = mangoHudEnabled),
                                option.usesVirtualDisplay,
                                mirrorDesktop,
                                forcePrivateAfterSteamClose,
                                profilePreference,
                                optimizationState.rawOptimization
                            )
                            launchOptionsState = null
                            dismiss()
                        }
                        val desktopSteamDecision = NovaDesktopSteamLaunchDecision.from(
                            uiState,
                            optimizationState.rawOptimization,
                            usesVirtualDisplay = option.usesVirtualDisplay
                        )
                        if (desktopSteamDecision.required) {
                            showDesktopSteamLaunchDecision(
                                decision = desktopSteamDecision,
                                onPrivateStream = { launchSelected(mirrorDesktop = false, forcePrivateAfterSteamClose = false) },
                                onMirrorDesktop = { launchSelected(mirrorDesktop = true, forcePrivateAfterSteamClose = false) },
                                onForcePrivateAfterSteamClose = { launchSelected(mirrorDesktop = false, forcePrivateAfterSteamClose = true) }
                            )
                        } else {
                            launchSelected(mirrorDesktop = false)
                        }
                    },
                    onDismissLaunchOptions = {
                        launchOptionsState = null
                    },
                    onProfilePreference = {
                        profileOptionsState = showProfilePreferenceOptions(currentGame)
                        launchOptionsState = null
                    },
                    onProfilePreferenceSelected = { selected ->
                        saveProfilePreference(currentGame, selected.value)
                        profilePreference = selected.value
                        refreshUiState(selected.value)
                        optimizationState = NovaGameDetailOptimizationState()
                        profileOptionsState = null
                        loadOptimization(selected.value)
                    },
                    onDismissProfileOptions = {
                        profileOptionsState = null
                    },
                    onRetryHighFps = { retryHighFpsTrial() },
                    onResetProfile = {
                        resetWorking = true
                        viewLifecycleOwner.lifecycleScope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                apiClient.clearOptimizerProfile(deviceName, currentGame.name)
                            }
                            val sheetContext = context ?: return@launch
                            if (cleared == true) {
                                optimizationState = NovaGameDetailOptimizationState()
                            }
                            val message = when (cleared) {
                                true -> R.string.nova_library_reset_game_profile_cleared
                                false -> R.string.nova_library_reset_game_profile_empty
                                null -> R.string.nova_library_reset_game_profile_failed
                            }
                            Toast.makeText(sheetContext, message, Toast.LENGTH_SHORT).show()
                            resetWorking = false
                        }
                    },
                    steamLaunchOptionsState = steamLaunchOptionsState,
                    onSteamLaunchMode = {
                        steamLaunchOptionsState = steamLaunchModeOptionsState(currentGame)
                    },
                    onSteamLaunchModeSelected = { selected ->
                        val previousGame = currentGame
                        val requestedMode = PolarisGame.SteamLaunchContract.normalizeMode(selected.value)
                        if (requestedMode == previousGame.steamLaunchMode) {
                            steamLaunchOptionsState = null
                            return@NovaGameDetailSheetContent
                        }

                        currentGame = previousGame.copy(
                            steamLaunch = previousGame.steamLaunch?.copy(mode = requestedMode)
                        )
                        refreshUiState()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val confirmedMode = withContext(Dispatchers.IO) {
                                apiClient.setSteamLaunchMode(previousGame.id, requestedMode)
                            }
                            val message = if (confirmedMode != null) {
                                currentGame = currentGame.copy(
                                    steamLaunch = currentGame.steamLaunch?.copy(mode = confirmedMode)
                                )
                                refreshUiState()
                                steamLaunchOptionsState = null
                                R.string.nova_steam_launch_mode_updated
                            } else {
                                currentGame = previousGame
                                refreshUiState()
                                steamLaunchOptionsState = steamLaunchModeOptionsState(previousGame)
                                R.string.nova_steam_launch_mode_failed
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDismissSteamLaunchModeOptions = {
                        steamLaunchOptionsState = null
                    },
                    coverLoader = { imageView ->
                        apiClient.loadCoverInto(imageView, currentGame)
                    }
                )
            }
        }

        loadOptimization(profilePreference)
    }

    private fun buildUiState(game: PolarisGame, profilePreference: String): NovaGameDetailUiState {
        return NovaGameDetailUiState.from(
            game = game,
            defaultToVirtualDisplay = defaultToVirtualDisplay,
            clientSettings = clientSettings,
            profilePreference = profilePreference
        )
    }

    private fun loadProfilePreference(game: PolarisGame): String {
        return AutoQualityProfilePreferences.load(requireContext(), game.name)
    }

    private fun saveProfilePreference(game: PolarisGame, preference: String) {
        AutoQualityProfilePreferences.save(requireContext(), game.name, preference)
    }

    private fun logPreflightOptimization(
        label: String,
        opt: JSONObject?,
        preference: String
    ) {
        if (opt == null) {
            LimeLog.warning("Nova: $label returned no profile for preference=$preference")
            return
        }

        val profileState = opt.optJSONObject("profile_state")
        val effective = opt.optJSONObject("effective_profile")
        val selectedFps = opt.optDouble(
            "effective_target_fps",
            profileState
                ?.optJSONObject("current_profile")
                ?.optDouble("target_fps", 0.0)
                ?: 0.0
        )
        LimeLog.info(
            "Nova: $label loaded source=${opt.optString("source", "unknown")} " +
                "cache=${opt.optString("cache_status", "unknown")} " +
                "state=${profileState?.optString("state", "none") ?: "none"} " +
                "effective=${effective?.optString("display_mode", "") ?: ""} " +
                "fps=$selectedFps preference=$preference " +
                "applied=${opt.optBoolean("preference_applied", false)} " +
                "trial=${opt.optBoolean("trial_profile", false)}"
        )
    }

    private fun showProfilePreferenceOptions(
        game: PolarisGame
    ): NovaProfilePreferenceOptionsState {
        val values = AutoQualityProfilePreferences.values()
        val current = loadProfilePreference(game)
        val labels = values.map {
            when (it) {
                "quality" -> "Prefer Quality"
                "high_fps" -> "Prefer High FPS"
                "stability" -> "Prefer Stability"
                else -> "Auto"
            }
        }
        return NovaProfilePreferenceOptionsState(
            title = getString(R.string.nova_library_profile_preference_title),
            closeLabel = getString(R.string.nova_controller_hint_close),
            options = values.mapIndexed { index, value ->
                NovaProfilePreferenceItem(
                    label = labels[index],
                    value = value,
                    selected = value == current
                )
            }
        )
    }

    private fun steamLaunchModeOptionsState(game: PolarisGame): NovaSteamLaunchModeOptionsState {
        val modes = listOf("direct", "big-picture")
        return NovaSteamLaunchModeOptionsState(
            title = getString(R.string.nova_steam_launch_options_title),
            subtitle = getString(R.string.nova_steam_launch_detail_label),
            closeLabel = getString(R.string.nova_controller_hint_close),
            options = modes.map { mode ->
                val normalizedMode = PolarisGame.SteamLaunchContract.normalizeMode(mode)
                NovaSteamLaunchModeItem(
                    label = steamLaunchModeLabel(normalizedMode),
                    value = normalizedMode,
                    selected = normalizedMode == game.steamLaunchMode
                )
            }
        )
    }

    private fun showLaunchOptions(
        game: PolarisGame,
        uiState: NovaGameDetailUiState
    ): NovaLaunchOptionsState? {
        val options = mutableListOf<NovaLaunchOptionItem>()
        if (uiState.headlessAllowed) {
            options += NovaLaunchOptionItem(
                label = optionLabel("headless", uiState.recommendedMode),
                usesVirtualDisplay = false,
                recommended = uiState.recommendedMode == "headless"
            )
        }
        if (uiState.virtualDisplayAllowed) {
            options += NovaLaunchOptionItem(
                label = optionLabel("virtual_display", uiState.recommendedMode),
                usesVirtualDisplay = true,
                recommended = uiState.recommendedMode == "virtual_display"
            )
        }

        if (options.isEmpty()) return null

        return NovaLaunchOptionsState(
            title = getString(R.string.nova_library_launch_options_title),
            closeLabel = getString(R.string.nova_controller_hint_close),
            gameName = game.name,
            options = options
        )
    }

    private fun optionLabel(mode: String, recommendedMode: String): String {
        val label = modeLabel(mode)
        return if (mode == recommendedMode) {
            getString(R.string.nova_library_launch_recommended_format, label)
        } else {
            label
        }
    }

    private fun syncLaunchPreflightSettings(
        context: Context,
        apiClient: PolarisApiClient,
        usesVirtualDisplay: Boolean,
        clientSettings: PolarisClientSettings?
    ): PolarisClientSettings? {
        val preferences = PreferenceConfiguration.readPreferences(context)
        return apiClient.updateClientSettings(
            streamDisplayMode = PolarisStreamDisplayMode.preflightModeForLaunch(usesVirtualDisplay, clientSettings),
            displayMode = PreferenceConfiguration.formatStreamingDisplayMode(
                preferences.width,
                preferences.height,
                preferences.fps
            ),
            targetBitrateKbps = preferences.bitrate.takeIf { it > 0 }
        )
    }

    private fun showPreflightReview(
        optimizationState: NovaGameDetailOptimizationState,
        onLaunchConfirmed: () -> Unit,
        onRetryHighFps: () -> Unit,
        onResetProfile: () -> Unit
    ) {
        val reason = optimizationState.reviewReason.ifBlank { "fps_override" }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nova_library_preflight_review_title)
            .setMessage(getString(R.string.nova_library_preflight_review_message, reason))
            .setPositiveButton(R.string.nova_library_preflight_launch) { _, _ -> onLaunchConfirmed() }
            .setNeutralButton(R.string.nova_library_retry_high_fps) { _, _ -> onRetryHighFps() }
            .setNegativeButton(R.string.nova_library_reset_game_profile) { _, _ -> onResetProfile() }
            .show()
    }

    private fun showDesktopSteamLaunchDecision(
        decision: NovaDesktopSteamLaunchDecision,
        onPrivateStream: () -> Unit,
        onMirrorDesktop: () -> Unit,
        onForcePrivateAfterSteamClose: () -> Unit
    ) {
        val sheet = BottomSheetDialog(requireContext(), theme)
        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NovaComposeTheme {
                    NovaDesktopSteamLaunchDecisionContent(
                        title = stringResource(R.string.nova_desktop_steam_title),
                        message = decision.reason.ifBlank {
                            stringResource(R.string.nova_desktop_steam_message)
                        },
                        privateStreamLabel = stringResource(R.string.nova_desktop_steam_private_stream),
                        privateStreamUnavailableReason = decision.privateStreamUnavailableReason,
                        privateStreamEnabled = decision.privateStreamEnabled,
                        mirrorDesktopLabel = stringResource(R.string.nova_desktop_steam_mirror_desktop),
                        mirrorDesktopEnabled = decision.mirrorDesktopEnabled,
                        mirrorDesktopCaption = stringResource(R.string.nova_desktop_steam_mirror_caption),
                        forcePrivateLabel = decision.forcePrivateAfterSteamCloseLabel.ifBlank {
                            stringResource(R.string.nova_desktop_steam_force_private)
                        },
                        forcePrivateEnabled = decision.forcePrivateAfterSteamCloseEnabled,
                        forcePrivateCaption = stringResource(R.string.nova_desktop_steam_force_private_caption),
                        cancelLabel = stringResource(R.string.nova_desktop_steam_cancel),
                        onPrivateStream = {
                            sheet.dismiss()
                            onPrivateStream()
                        },
                        onMirrorDesktop = {
                            sheet.dismiss()
                            onMirrorDesktop()
                        },
                        onForcePrivateAfterSteamClose = {
                            sheet.dismiss()
                            onForcePrivateAfterSteamClose()
                        },
                        onCancel = { sheet.dismiss() }
                    )
                }
            }
        }
        sheet.setContentView(composeView)
        sheet.setOnShowListener { expandBottomSheet(sheet) }
        sheet.show()
    }

    private fun modeLabel(mode: String): String {
        return when (mode) {
            "virtual_display" -> getString(R.string.nova_library_launch_virtual_display)
            else -> getString(R.string.nova_library_launch_headless)
        }
    }

    private fun modeBadgeLabel(mode: String): String {
        return when (mode) {
            "virtual_display" -> getString(R.string.nova_library_launch_virtual_short)
            else -> getString(R.string.nova_library_launch_headless)
        }
    }

    private fun primaryPlayLabel(uiState: NovaGameDetailUiState): String {
        return if (uiState.playEnabled) {
            getString(R.string.nova_library_play_mode, modeBadgeLabel(uiState.playMode))
        } else {
            getString(R.string.nova_library_play_unavailable)
        }
    }

    private fun steamLaunchModeLabel(mode: String): String {
        return when (PolarisGame.SteamLaunchContract.normalizeMode(mode)) {
            "big-picture" -> getString(R.string.nova_steam_launch_big_picture)
            else -> getString(R.string.nova_steam_launch_direct)
        }
    }

    private fun steamLaunchCaption(uiState: NovaGameDetailUiState): String {
        return if (uiState.steamLaunchWarning) {
            getString(R.string.nova_steam_launch_caption_big_picture)
        } else {
            getString(R.string.nova_steam_launch_caption_direct)
        }
    }

    private fun buildLaunchIntro(uiState: NovaGameDetailUiState): String {
        val parts = mutableListOf<String>()
        if (uiState.preferredMode != uiState.recommendedMode) {
            parts += getString(R.string.nova_library_launch_preferred_mode_format, modeLabel(uiState.preferredMode))
        }
        if (uiState.hostStreamDisplayMode in setOf(
                PolarisClientSettings.MODE_DESKTOP_DISPLAY,
                PolarisClientSettings.MODE_GPU_NATIVE_TEST
            ) && uiState.hostStreamDisplayModeLabel.isNotBlank()
        ) {
            parts += getString(R.string.nova_polaris_sync_host_mode_detail, uiState.hostStreamDisplayModeLabel)
        }
        parts += when {
            uiState.virtualDisplayUnavailable -> {
                val unavailableParts = mutableListOf(
                    getString(R.string.nova_library_virtual_display_unavailable_body)
                )
                uiState.virtualDisplayUnavailableReason
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        unavailableParts += getString(
                            R.string.nova_library_virtual_display_unavailable_reason_format,
                            it
                        )
                    }
                unavailableParts.joinToString(" ")
            }
            uiState.launchChoice.hostModeReason.isNotBlank() -> uiState.launchChoice.hostModeReason
            uiState.game.launchMode?.modeReason?.isNotBlank() == true -> uiState.game.launchMode?.modeReason.orEmpty()
            uiState.recommendedMode == "virtual_display" -> getString(R.string.nova_library_launch_intro_virtual_default)
            else -> getString(R.string.nova_library_launch_intro_headless_default)
        }
        return parts.joinToString(" ")
    }

    private fun lastPlayedText(game: PolarisGame): String? {
        if (game.lastLaunched <= 0) return null
        val relative = DateUtils.getRelativeTimeSpanString(
            game.lastLaunched * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
        return getString(R.string.nova_library_meta_last_played, relative)
    }

    private fun buildOptimizationState(
        opt: JSONObject?,
        profilePreference: String
    ): NovaGameDetailOptimizationState {
        if (opt == null) return NovaGameDetailOptimizationState()

        val profileState = opt.optJSONObject("profile_state")
        val currentProfile = profileState?.optJSONObject("current_profile") ?: opt.optJSONObject("effective_profile")
        val lastResult = profileState?.optJSONObject("last_result")
        val source = opt.optString("source", "")
        val confidence = opt.optString("confidence", "")
        val cacheStatus = opt.optString("cache_status", "")
        val displayMode = currentProfile
            ?.optString("display_mode", "")
            ?.takeIf { it.isNotBlank() }
            ?: opt.optString("display_mode", "")
        val bitrate = currentProfile
            ?.optInt("target_bitrate_kbps", 0)
            ?.takeIf { it > 0 }
            ?: opt.optInt("target_bitrate_kbps", 0)
        val targetFps = currentProfile?.optDouble("target_fps", 0.0) ?: 0.0
        val codec = currentProfile
            ?.optString("preferred_codec", "")
            ?.takeIf { it.isNotBlank() }
            ?: opt.optString("preferred_codec", "")
        val reasoning = opt.optString("reasoning", "")
        val normalizationReason = opt.optString("normalization_reason", "")
        val generatedAt = opt.optLong("generated_at", 0L)

        val aiCard = if (displayMode.isNotEmpty() || codec.isNotEmpty() || profileState != null) {
            val parts = mutableListOf<String>()
            if (displayMode.isNotEmpty()) parts.add(displayMode)
            if (displayMode.isEmpty() && targetFps > 0.0) parts.add("${formatFps(targetFps)} FPS")
            if (codec.isNotEmpty()) parts.add(codec.uppercase())
            if (bitrate > 0) parts.add("up to ${bitrate / 1000} Mbps")
            val settingsText = parts.joinToString(" · ").ifBlank { "Profile is being learned" }

            val titleLabel = profileState
                ?.optString("label", "")
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                        "Auto Quality Recovery"
                    source.contains("ai_cached") -> "Auto Quality Ready"
                    source.contains("ai_live") -> "Auto Quality Optimized"
                    source.contains("device_db") -> "Auto Quality Baseline"
                    else -> "Auto Quality"
                }
            val sourceLabel = when {
                source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                    "Recovery"
                source.contains("ai_cached") -> "Cached profile"
                source.contains("ai_live") -> "Fresh profile"
                source.contains("device_db") -> getString(R.string.nova_library_ai_baseline_source_label)
                else -> source
            }
            val profileStateLabel = profileState
                ?.optString("state", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { profileStateLabel(it) }
                .orEmpty()
            val stateLabel = when {
                profileStateLabel.isNotBlank() -> profileStateLabel
                normalizationReason.isNotBlank() -> getString(R.string.nova_optimization_host_adjusted)
                cacheStatus.equals("hit", ignoreCase = true) -> getString(R.string.nova_optimization_cached)
                cacheStatus.equals("invalidated", ignoreCase = true) -> getString(R.string.nova_optimization_recovery)
                cacheStatus.equals("miss", ignoreCase = true) -> getString(R.string.nova_optimization_fresh)
                source.contains("device_db") -> getString(R.string.nova_optimization_device_tune)
                else -> ""
            }
            val lastResultText = buildLastResultText(lastResult)
            val generatedLabel = if (generatedAt > 0) {
                DateUtils.getRelativeTimeSpanString(
                    generatedAt * 1000,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                ""
            }
            val sourceText = listOf(
                stateLabel.takeIf { it.isNotBlank() },
                profileState?.optString("preference_label", "")?.takeIf { it.isNotBlank() },
                lastResultText.takeIf { it.isNotBlank() },
                sourceLabel.takeIf { it.isNotBlank() && sourceLabel != titleLabel },
                confidence.takeIf { it.isNotBlank() }?.lowercase()?.plus(" confidence"),
                generatedLabel.takeIf { it.isNotBlank() }
            ).filter { !it.isNullOrBlank() }.joinToString(" · ")
            val profileReason = profileState?.optString("reason", "").orEmpty()
            val preferenceNote = profileState
                ?.optString("preference_note", "")
                ?.takeIf { profilePreference != "auto" }
                .orEmpty()
            val requestedFps = opt.optDouble("requested_target_fps", 0.0)
            val effectiveFps = opt.optDouble("effective_target_fps", 0.0)
            val requestedReason = if (requestedFps > 0.0 && effectiveFps > 0.0 && abs(requestedFps - effectiveFps) > 0.5) {
                "Requested ${formatFps(requestedFps)} FPS, selected ${formatFps(effectiveFps)} FPS."
            } else {
                ""
            }
            val fullReasoning = listOf(profileReason, preferenceNote, requestedReason, reasoning, normalizationReason)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            NovaGameDetailInsightCard(
                label = titleLabel,
                source = sourceText,
                settings = settingsText,
                reasoning = fullReasoning,
                isWarning = cacheStatus.equals("invalidated", ignoreCase = true)
            )
        } else {
            null
        }

        val stabilityCard = opt.optJSONObject("stability")?.let { stability ->
            val safeProfile = stability.optJSONObject("safe_profile")
            val safeProfileParts = mutableListOf<String>()
            val safeCodec = safeProfile?.optString("preferred_codec", "").orEmpty()
            if (safeCodec.isNotBlank()) {
                safeProfileParts += safeCodec.uppercase()
            }
            val safeBitrate = safeProfile?.optInt("target_bitrate_kbps", 0) ?: 0
            if (safeBitrate > 0) {
                safeProfileParts += "${safeBitrate / 1000} Mbps"
            }
            val safeDisplayMode = safeProfile?.optString("display_mode", "").orEmpty()
            if (safeDisplayMode.isNotBlank()) {
                safeProfileParts += modeBadgeLabel(safeDisplayMode)
            }
            if (safeProfile?.has("hdr") == true && !safeProfile.optBoolean("hdr", false)) {
                safeProfileParts += "HDR off"
            }

            val discouragedFeatures = stability.optJSONArray("discouraged_features")
            val firstDiscouragedReason = if (discouragedFeatures != null && discouragedFeatures.length() > 0) {
                discouragedFeatures.optJSONObject(0)?.optString("reason", "").orEmpty()
            } else {
                ""
            }
            val relaunchNotes = stability.optJSONArray("relaunch_notes")
            val relaunchNote = if (relaunchNotes != null && relaunchNotes.length() > 0) {
                relaunchNotes.optString(0)
            } else {
                ""
            }
            val stabilitySummary = stability.optString("summary", "")
            val stabilityMode = stability.optString("mode", "")
            val stabilityDetails = listOfNotNull(
                stabilitySummary.takeIf { it.isNotBlank() },
                firstDiscouragedReason.takeIf { it.isNotBlank() },
                relaunchNote.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            if (safeProfileParts.isNotEmpty() || stabilityDetails.isNotBlank()) {
                val isStabilityFirst = stabilityMode.equals("stability_first", ignoreCase = true) ||
                    opt.optInt("consecutive_poor_outcomes", 0) > 0
                val relaunchRequired = stability.optBoolean("relaunch_required", false)
                NovaGameDetailInsightCard(
                    label = when {
                        isStabilityFirst -> "Recovery Profile"
                        relaunchRequired -> "Recovery Queued"
                        else -> "Safer Fallback"
                    },
                    source = "",
                    settings = if (safeProfileParts.isNotEmpty()) {
                        safeProfileParts.joinToString(" · ")
                    } else {
                        "Safer next launch"
                    },
                    reasoning = stabilityDetails,
                    isWarning = isStabilityFirst
                )
            } else {
                null
            }
        }

        return NovaGameDetailOptimizationState(
            ai = aiCard,
            stability = stabilityCard,
            profileSummary = buildNovaLaunchProfileSummary(opt),
            rawOptimization = opt,
            reviewRequired = StreamSyncManager.requiresLaunchPreflightReview(opt),
            reviewReason = StreamSyncManager.launchPreflightReviewReason(opt)
        )
    }

    private fun profileStateLabel(state: String): String {
        return when (state.lowercase()) {
            "manual_override" -> "Manual"
            "upgrade_available" -> "Ready"
            "recovering" -> "Recovery"
            "blocked" -> "Holding"
            "learning" -> "Learning"
            "stable" -> "Stable"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatFps(fps: Double): String {
        val rounded = round(fps)
        return if (abs(fps - rounded) < 0.01) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", fps)
        }
    }

    private fun buildLastResultText(lastResult: JSONObject?): String {
        if (lastResult == null) return ""
        val grade = lastResult.optString("grade", "")
        val delivered = lastResult.optDouble("delivered_fps", 0.0)
        val target = lastResult.optDouble("target_fps", 0.0)
        val fpsText = if (delivered > 0.0 && target > 0.0) {
            "${formatFps(delivered)}/${formatFps(target)} FPS"
        } else {
            ""
        }
        return listOf(
            grade.takeIf { it.isNotBlank() }?.let { "Last $it" },
            fpsText.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" · ")
    }

    private fun sheetBackgroundRes(): Int {
        return if (NovaThemeManager.isOled(requireContext())) {
            R.drawable.nova_sheet_bg_oled
        } else {
            R.drawable.nova_sheet_bg
        }
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
                val minWidth = (720 * density).toInt()
                val maxWidth = (1260 * density).toInt()
                (displayWidth * 0.7f).toInt().coerceIn(minWidth, maxWidth)
            } else {
                displayWidth
            }
            val horizontalMargin = if (isLandscape) {
                ((displayWidth - desiredWidth) / 2).coerceAtLeast((18 * density).toInt())
            } else {
                0
            }

            contentView.layoutParams = contentView.layoutParams.apply {
                height = if (contentHeight > maxHeight) desiredHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            sheet.layoutParams = sheet.layoutParams.apply {
                width = if (isLandscape) displayWidth - (horizontalMargin * 2) else ViewGroup.LayoutParams.MATCH_PARENT
                height = desiredHeight
            }
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = horizontalMargin
                lp.marginEnd = horizontalMargin
                sheet.layoutParams = lp
            }
            sheet.minimumHeight = 0
            sheet.requestLayout()

            val behavior = BottomSheetBehavior.from(sheet)
            behavior.isFitToContents = true
            behavior.isDraggable = false
            behavior.skipCollapsed = true
            behavior.peekHeight = desiredHeight
            behavior.state = BottomSheetBehavior.STATE_EXPANDED

            when (contentView) {
                is NestedScrollView -> contentView.post { contentView.scrollTo(0, 0) }
                is ScrollView -> contentView.post { contentView.scrollTo(0, 0) }
            }
        }
    }
}


@Composable
private fun NovaSheetDragHandle(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .size(height = 28.dp, width = 1.dp)
            .novaSheetHandleDrag(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.divider)
        )
    }
}

private fun Modifier.novaSheetHandleDrag(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    val dismissThreshold = 42.dp.toPx()
    var draggedDown = 0f
    detectVerticalDragGestures(
        onDragStart = { draggedDown = 0f },
        onDragCancel = { draggedDown = 0f },
        onDragEnd = {
            if (draggedDown >= dismissThreshold) {
                onDismiss()
            }
            draggedDown = 0f
        },
        onVerticalDrag = { change, dragAmount ->
            if (dragAmount > 0f) {
                draggedDown += dragAmount
                change.consume()
            }
        }
    )
}

data class NovaGameDetailOptimizationState(
    val ai: NovaGameDetailInsightCard? = null,
    val stability: NovaGameDetailInsightCard? = null,
    val profileSummary: NovaLaunchProfileSummary? = null,
    val rawOptimization: JSONObject? = null,
    val reviewRequired: Boolean = false,
    val reviewReason: String = ""
)

data class NovaLaunchOptionsState(
    val title: String,
    val closeLabel: String,
    val gameName: String,
    val options: List<NovaLaunchOptionItem>
)

data class NovaLaunchOptionItem(
    val label: String,
    val usesVirtualDisplay: Boolean,
    val recommended: Boolean
)

data class NovaProfilePreferenceOptionsState(
    val title: String,
    val closeLabel: String,
    val options: List<NovaProfilePreferenceItem>
)

data class NovaProfilePreferenceItem(
    val label: String,
    val value: String,
    val selected: Boolean
)

data class NovaGameDetailInsightCard(
    val label: String,
    val source: String,
    val settings: String,
    val reasoning: String,
    val isWarning: Boolean
)

data class NovaSteamLaunchModeItem(
    val label: String,
    val value: String,
    val selected: Boolean
)

data class NovaSteamLaunchModeOptionsState(
    val title: String,
    val subtitle: String,
    val closeLabel: String,
    val options: List<NovaSteamLaunchModeItem>
)

@Composable
fun NovaGameDetailSheetContent(
    uiState: NovaGameDetailUiState,
    launchIntro: String,
    recommendedBadge: String,
    lastPlayedText: String?,
    profilePreferenceLabel: String,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    mangoHudEnabled: Boolean,
    mangoHudStatusLabel: String,
    mangoHudStatusCaption: String,
    mangoHudWarning: Boolean,
    steamLaunchLabel: String,
    steamLaunchModeLabel: String,
    steamLaunchCaption: String,
    optimizationState: NovaGameDetailOptimizationState,
    launchOptionsState: NovaLaunchOptionsState?,
    profileOptionsState: NovaProfilePreferenceOptionsState?,
    playLabel: String,
    launchOptionsLabel: String,
    launchModeTitle: String,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    coverContentDescription: String,
    onSheetHandleDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
    onLaunchOptionSelected: (NovaLaunchOptionItem) -> Unit,
    onDismissLaunchOptions: () -> Unit,
    onProfilePreference: () -> Unit,
    onProfilePreferenceSelected: (NovaProfilePreferenceItem) -> Unit,
    onDismissProfileOptions: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    steamLaunchOptionsState: NovaSteamLaunchModeOptionsState? = null,
    onSteamLaunchMode: () -> Unit,
    onSteamLaunchModeSelected: (NovaSteamLaunchModeItem) -> Unit = {},
    onDismissSteamLaunchModeOptions: () -> Unit = {},
    coverLoader: (ImageView) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val verticalScroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp, topEnd = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp))
            .background(surfaces.panel)
            .verticalScroll(verticalScroll)
            .padding(bottom = 16.dp)
    ) {
        NovaSheetDragHandle(onDismiss = onSheetHandleDismiss)

        GameDetailsPanel(
            uiState = uiState,
            lastPlayedText = lastPlayedText,
            coverContentDescription = coverContentDescription,
            coverLoader = coverLoader
        )

        LaunchControlsPanel(
            uiState = uiState,
            launchIntro = launchIntro,
            launchModeTitle = launchModeTitle,
            recommendedBadge = recommendedBadge,
            playLabel = playLabel,
            launchOptionsLabel = launchOptionsLabel,
            profilePreferenceLabel = profilePreferenceLabel,
            profileSummary = optimizationState.profileSummary,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            headlessModeLabel = headlessModeLabel,
            virtualDisplayModeLabel = virtualDisplayModeLabel,
            onPrimaryLaunch = onPrimaryLaunch,
            onLaunchOptions = onLaunchOptions,
            onLaunchModeSelected = onLaunchModeSelected,
            onProfilePreference = onProfilePreference,
            onRetryHighFps = onRetryHighFps,
            onResetProfile = onResetProfile
        )

        launchOptionsState?.let {
            NovaLaunchOptionsSheet(
                state = it,
                onLaunch = onLaunchOptionSelected,
                onDismiss = onDismissLaunchOptions
            )
        }

        profileOptionsState?.let {
            NovaProfilePreferenceSheet(
                state = it,
                onSelected = onProfilePreferenceSelected,
                onDismiss = onDismissProfileOptions
            )
        }

        SteamLaunchModeCard(
            visible = uiState.showSteamLaunchMode,
            label = steamLaunchLabel,
            modeLabel = steamLaunchModeLabel,
            caption = steamLaunchCaption,
            warning = uiState.steamLaunchWarning,
            onClick = onSteamLaunchMode
        )

        steamLaunchOptionsState?.let { state ->
            NovaSteamLaunchModeSheet(
                state = state,
                onSelected = onSteamLaunchModeSelected,
                onDismiss = onDismissSteamLaunchModeOptions
            )
        }

        if (mangoHudEnabled) {
            MangoHudPassiveStatus(
                label = mangoHudStatusLabel,
                caption = mangoHudStatusCaption,
                warning = mangoHudWarning
            )
        }

        optimizationState.ai?.let {
            InsightCard(card = it)
        }

        optimizationState.stability?.let {
            InsightCard(card = it)
        }

        NovaControllerHintBar(
            hints = novaGameDetailControllerHints(),
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
        )
    }
}

@Composable
private fun novaGameDetailControllerHints(): List<NovaControllerHint> = listOf(
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_a),
        label = stringResource(R.string.nova_controller_hint_launch)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_b),
        label = stringResource(R.string.nova_controller_hint_close)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_lb_rb),
        label = stringResource(R.string.nova_controller_hint_launch_mode)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_y),
        label = stringResource(R.string.nova_controller_hint_profile)
    )
)

@Composable
private fun NovaDetailPanel(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    accent: Boolean = false,
    warning: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(14.dp)
    val backgroundColor = when {
        warning -> colors.warning.copy(alpha = 0.12f)
        accent -> colors.accentSurface
        else -> surfaces.tile
    }
    val borderColor = when {
        warning -> colors.warning.copy(alpha = 0.55f)
        else -> surfaces.tileBorder
    }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .then(semanticsModifier)
            .padding(contentPadding)
    ) {
        content()
    }
}

@Composable
private fun NovaDesktopSteamLaunchDecisionContent(
    title: String,
    message: String,
    privateStreamLabel: String,
    privateStreamUnavailableReason: String,
    privateStreamEnabled: Boolean,
    mirrorDesktopLabel: String,
    mirrorDesktopEnabled: Boolean,
    mirrorDesktopCaption: String,
    forcePrivateLabel: String,
    forcePrivateEnabled: Boolean,
    forcePrivateCaption: String,
    cancelLabel: String,
    onPrivateStream: () -> Unit,
    onMirrorDesktop: () -> Unit,
    onForcePrivateAfterSteamClose: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp, topEnd = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp))
            .background(LocalNovaLibrarySurfaces.current.panel)
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 16.dp)
    ) {
        NovaSheetDragHandle(
            onDismiss = onCancel,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        NovaDetailPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = true,
            warning = true,
            contentPadding = PaddingValues(14.dp)
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 8.dp),
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp
            )
            if (privateStreamUnavailableReason.isNotBlank()) {
                Text(
                    text = privateStreamUnavailableReason,
                    modifier = Modifier.padding(top = 8.dp),
                    color = colors.warning,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
            Text(
                text = mirrorDesktopCaption,
                modifier = Modifier.padding(top = 8.dp),
                color = colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        NovaActionButton(
            text = privateStreamLabel,
            onClick = onPrivateStream,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            enabled = privateStreamEnabled,
            contentDescription = privateStreamLabel,
            minHeight = 46.dp,
            cornerRadius = 12.dp,
            fontSize = 14.sp
        )
        NovaActionButton(
            text = forcePrivateLabel,
            onClick = onForcePrivateAfterSteamClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = forcePrivateEnabled,
            primary = false,
            contentDescription = forcePrivateLabel,
            minHeight = 46.dp,
            cornerRadius = 12.dp,
            fontSize = 14.sp
        )
        Text(
            text = forcePrivateCaption,
            modifier = Modifier.padding(top = 5.dp),
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
        NovaActionButton(
            text = mirrorDesktopLabel,
            onClick = onMirrorDesktop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = mirrorDesktopEnabled,
            primary = true,
            contentDescription = mirrorDesktopLabel,
            minHeight = 48.dp,
            cornerRadius = 12.dp,
            fontSize = 15.sp
        )
        NovaActionButton(
            text = cancelLabel,
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentDescription = cancelLabel,
            minHeight = 42.dp,
            cornerRadius = 10.dp,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun GameDetailsPanel(
    uiState: NovaGameDetailUiState,
    lastPlayedText: String?,
    coverContentDescription: String,
    coverLoader: (ImageView) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val game = uiState.game

    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .heightIn(min = 136.dp),
        contentDescription = "Game details",
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            key(game.id, game.coverUrl) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(ContextCompat.getColor(context, R.color.nova_deep))
                            contentDescription = coverContentDescription
                            coverLoader(this)
                        }
                    },
                    modifier = Modifier
                        .width(108.dp)
                        .aspectRatio(88f / 118f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.window)
                        .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
                        .semantics { contentDescription = coverContentDescription }
                )
            }

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = game.name,
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (game.sourceRuntimeLabel.isNotBlank()) {
                    Text(
                        text = game.sourceRuntimeLabel,
                        modifier = Modifier.padding(top = 5.dp),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                MetadataBadges(game)
                GenresRow(game.genres)

                if (lastPlayedText != null) {
                    NovaBadge(
                        text = lastPlayedText,
                        modifier = Modifier.padding(top = 7.dp),
                        color = colors.textSecondary,
                        backgroundColor = surfaces.control.copy(alpha = 0.78f),
                        borderColor = surfaces.tileBorder,
                        fontSize = 11.sp,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchControlsPanel(
    uiState: NovaGameDetailUiState,
    launchIntro: String,
    launchModeTitle: String,
    recommendedBadge: String,
    playLabel: String,
    launchOptionsLabel: String,
    profilePreferenceLabel: String,
    profileSummary: NovaLaunchProfileSummary?,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
    onProfilePreference: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit
) {
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = "Launch controls",
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        LaunchControls(
            uiState = uiState,
            launchIntro = launchIntro,
            launchModeTitle = launchModeTitle,
            recommendedBadge = recommendedBadge,
            playLabel = playLabel,
            launchOptionsLabel = launchOptionsLabel,
            profilePreferenceLabel = profilePreferenceLabel,
            profileSummary = profileSummary,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            headlessModeLabel = headlessModeLabel,
            virtualDisplayModeLabel = virtualDisplayModeLabel,
            onPrimaryLaunch = onPrimaryLaunch,
            onLaunchOptions = onLaunchOptions,
            onLaunchModeSelected = onLaunchModeSelected,
            onProfilePreference = onProfilePreference,
            onRetryHighFps = onRetryHighFps,
            onResetProfile = onResetProfile
        )
    }
}

@Composable
private fun MetadataBadges(game: PolarisGame) {
    val horizontalScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .horizontalScroll(horizontalScroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (game.sourceLabel.isNotEmpty()) {
            NovaBadge(text = game.sourceLabel)
        }
        if (game.categoryLabel.isNotEmpty()) {
            NovaBadge(text = game.categoryLabel)
        }
    }
}

@Composable
private fun GenresRow(genres: List<String>) {
    if (genres.isEmpty()) return
    val horizontalScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .padding(top = 5.dp)
            .horizontalScroll(horizontalScroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        genres.forEach { genre ->
            NovaBadge(
                text = genre,
                color = LocalNovaComposeColors.current.textMuted
            )
        }
    }
}

@Composable
private fun LaunchControls(
    uiState: NovaGameDetailUiState,
    launchIntro: String,
    launchModeTitle: String,
    recommendedBadge: String,
    playLabel: String,
    launchOptionsLabel: String,
    profilePreferenceLabel: String,
    profileSummary: NovaLaunchProfileSummary?,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
    onProfilePreference: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val playFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.playEnabled) {
        if (uiState.playEnabled) {
            playFocusRequester.requestFocus()
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = launchModeTitle,
                modifier = Modifier.weight(1f),
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (uiState.showRecommendedModeBadge) {
                Spacer(modifier = Modifier.width(8.dp))
                NovaBadge(
                    text = recommendedBadge,
                    color = colors.onAccent,
                    backgroundColor = colors.accent.copy(alpha = 0.86f),
                    borderColor = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Text(
            text = launchIntro,
            modifier = Modifier.padding(top = 6.dp),
            color = if (uiState.virtualDisplayUnavailable) colors.warning else colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (uiState.showLaunchModeSummary) {
            val launchModeSummary = when {
                uiState.showVirtualUnavailableHint && uiState.virtualDisplayUnavailableReason.isNotBlank() ->
                    virtualDisplayModeLabel + " unavailable: " + uiState.virtualDisplayUnavailableReason
                uiState.showVirtualUnavailableHint ->
                    virtualDisplayModeLabel + " unavailable"
                uiState.playMode == "virtual_display" -> launchModeTitle + ": " + virtualDisplayModeLabel
                uiState.playMode == "headless" -> launchModeTitle + ": " + headlessModeLabel
                else -> launchModeTitle
            }
            Text(
                text = launchModeSummary,
                modifier = Modifier.padding(top = 6.dp),
                color = if (uiState.showVirtualUnavailableHint) colors.warning else colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        profileSummary?.let { LaunchProfilePrimaryNotice(it) }

        NovaActionButton(
            text = playLabel,
            onClick = onPrimaryLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(playFocusRequester)
                .padding(top = 10.dp),
            enabled = uiState.playEnabled,
            primary = true,
            contentDescription = playLabel,
            minHeight = 50.dp,
            cornerRadius = 12.dp,
            fontSize = 16.sp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        )

        if (uiState.showLaunchOptionsButton || uiState.showVirtualUnavailableHint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LaunchModeChoicePill(
                    label = headlessModeLabel,
                    status = when {
                        uiState.playMode == "headless" -> "Selected"
                        uiState.recommendedMode == "headless" && uiState.headlessAllowed -> "Recommended"
                        uiState.headlessAllowed -> "Available"
                        else -> "Unavailable"
                    },
                    recommended = uiState.recommendedMode == "headless" && uiState.headlessAllowed,
                    selected = uiState.playMode == "headless",
                    unavailable = !uiState.headlessAllowed,
                    onClick = { onLaunchModeSelected("headless") },
                    modifier = Modifier.weight(1f)
                )
                LaunchModeChoicePill(
                    label = virtualDisplayModeLabel,
                    status = when {
                        uiState.virtualDisplayUnavailable -> "Unavailable"
                        uiState.playMode == "virtual_display" -> "Selected"
                        uiState.recommendedMode == "virtual_display" && uiState.virtualDisplayAllowed -> "Recommended"
                        uiState.virtualDisplayAllowed -> "Available"
                        else -> "Unavailable"
                    },
                    recommended = uiState.recommendedMode == "virtual_display" && uiState.virtualDisplayAllowed,
                    selected = uiState.playMode == "virtual_display",
                    unavailable = uiState.virtualDisplayUnavailable || !uiState.virtualDisplayAllowed,
                    onClick = { onLaunchModeSelected("virtual_display") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.showLaunchOptionsButton) {
                NovaActionButton(
                    text = launchOptionsLabel,
                    onClick = onLaunchOptions,
                    modifier = Modifier.weight(1f),
                    contentDescription = launchOptionsLabel,
                    minHeight = 42.dp,
                    cornerRadius = 10.dp,
                    fontSize = 12.sp,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            NovaActionButton(
                text = profilePreferenceLabel,
                onClick = onProfilePreference,
                modifier = if (uiState.showLaunchOptionsButton) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                contentDescription = profilePreferenceLabel,
                minHeight = 42.dp,
                cornerRadius = 10.dp,
                fontSize = 12.sp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        profileSummary?.let {
            LaunchProfileSummaryInline(
                summary = it,
                onRetryHighFps = onRetryHighFps
            )
        }

        NovaActionButton(
            text = resetProfileLabel,
            onClick = onResetProfile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = !resetProfileWorking,
            contentDescription = resetProfileLabel,
            minHeight = 36.dp,
            cornerRadius = 10.dp,
            fontSize = 11.sp,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun LaunchProfilePrimaryNotice(summary: NovaLaunchProfileSummary) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val notice = listOf(summary.limitingLine, summary.reasonLine)
        .firstOrNull { it.isNotBlank() }
        ?: summary.freshnessLine.takeIf { it.isNotBlank() }
        ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.warning.copy(alpha = 0.14f))
            .border(1.dp, colors.warning.copy(alpha = 0.52f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NovaBadge(
            text = "Heads up",
            color = colors.warning,
            backgroundColor = surfaces.control.copy(alpha = 0.72f),
            borderColor = colors.warning.copy(alpha = 0.35f),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = notice,
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LaunchProfileSummaryInline(
    summary: NovaLaunchProfileSummary,
    onRetryHighFps: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .semantics { contentDescription = "Launch profile summary" }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .heightIn(min = 1.dp, max = 1.dp)
                .background(colors.divider.copy(alpha = 0.55f))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Launch Profile",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProfileSummaryText(summary.selectedLine, topPadding = 4)
                ProfileSummaryText(summary.requestedLine)
                ProfileSummaryText(summary.limitingLine)
                ProfileSummaryText(summary.reasonLine)
            }
        }

        if (summary.historyLines.isNotEmpty()) {
            Text(
                text = summary.historyLines.first(),
                modifier = Modifier.padding(top = 4.dp),
                color = colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            ProfileSummaryText(summary.freshnessLine)
        }

        if (summary.showRetryHighFps) {
            NovaActionButton(
                text = summary.retryHighFpsLabel,
                onClick = onRetryHighFps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentDescription = summary.retryHighFpsLabel,
                minHeight = 36.dp,
                cornerRadius = 8.dp,
                fontSize = 11.sp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun LaunchModeChoicePill(
    label: String,
    status: String,
    recommended: Boolean,
    selected: Boolean,
    unavailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val statusColor = when {
        unavailable -> colors.warning
        selected || recommended -> colors.accent
        else -> colors.textMuted
    }

    NovaFocusableCard(
        modifier = modifier.heightIn(min = 52.dp),
        onClick = onClick,
        enabled = !unavailable,
        contentDescription = "$label. $status",
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = label,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status,
                modifier = Modifier.padding(top = 3.dp),
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProfileSummaryText(text: String, topPadding: Int = 3) {
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = Modifier.padding(top = topPadding.dp),
        color = LocalNovaComposeColors.current.textMuted,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}


@Composable
private fun NovaLaunchOptionsSheet(
    state: NovaLaunchOptionsState,
    onLaunch: (NovaLaunchOptionItem) -> Unit,
    onDismiss: () -> Unit
) {
    NovaOptionPanel(
        title = state.title,
        subtitle = state.gameName,
        closeLabel = state.closeLabel,
        onDismiss = onDismiss
    ) {
        state.options.forEach { option ->
            NovaActionButton(
                text = option.label,
                onClick = { onLaunch(option) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                primary = option.recommended,
                contentDescription = option.label,
                minHeight = 44.dp,
                cornerRadius = 10.dp,
                fontSize = 13.sp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun NovaProfilePreferenceSheet(
    state: NovaProfilePreferenceOptionsState,
    onSelected: (NovaProfilePreferenceItem) -> Unit,
    onDismiss: () -> Unit
) {
    NovaOptionPanel(
        title = state.title,
        subtitle = "Auto Quality",
        closeLabel = state.closeLabel,
        onDismiss = onDismiss
    ) {
        state.options.forEach { option ->
            NovaActionButton(
                text = if (option.selected) option.label + " · Selected" else option.label,
                onClick = { onSelected(option) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                primary = option.selected,
                contentDescription = option.label,
                minHeight = 44.dp,
                cornerRadius = 10.dp,
                fontSize = 13.sp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun NovaOptionPanel(
    title: String,
    subtitle: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = title,
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(top = 2.dp),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            NovaActionButton(
                text = closeLabel,
                onClick = onDismiss,
                modifier = Modifier.width(104.dp),
                contentDescription = closeLabel,
                minHeight = 36.dp,
                cornerRadius = 10.dp,
                fontSize = 11.sp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
            )
        }
        content()
    }
}

@Composable
private fun NovaSteamLaunchModeSheet(
    state: NovaSteamLaunchModeOptionsState,
    onSelected: (NovaSteamLaunchModeItem) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = state.title,
        accent = true,
        contentPadding = PaddingValues(12.dp)
    ) {
        Text(
            text = state.title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = state.subtitle,
            color = colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.options.forEach { item ->
                NovaFocusableCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelected(item) },
                    contentDescription = item.label,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.label,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.selected) {
                            NovaBadge(
                                text = stringResource(R.string.nova_library_filter_selected),
                                color = colors.onAccent,
                                backgroundColor = colors.accent,
                                borderColor = colors.accent,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
        NovaActionButton(
            text = state.closeLabel,
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            minHeight = 36.dp,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SteamLaunchModeCard(
    visible: Boolean,
    label: String,
    modeLabel: String,
    caption: String,
    warning: Boolean,
    onClick: () -> Unit
) {
    if (!visible) return

    val colors = LocalNovaComposeColors.current
    NovaFocusableCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp)
            .heightIn(min = 58.dp),
        onClick = onClick,
        contentDescription = "$label. $modeLabel. $caption",
        contentPadding = PaddingValues(start = 12.dp, top = 9.dp, end = 12.dp, bottom = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = caption,
                    modifier = Modifier.padding(top = 2.dp),
                    color = if (warning) colors.warning else colors.textSecondary,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            NovaBadge(text = modeLabel, color = if (warning) colors.warning else colors.textSecondary)
        }
    }
}

@Composable
private fun MangoHudPassiveStatus(
    label: String,
    caption: String,
    warning: Boolean
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp)
            .semantics { contentDescription = "$label. $caption" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NovaBadge(
            text = label,
            color = if (warning) colors.warning else colors.textSecondary,
            backgroundColor = surfaces.control.copy(alpha = 0.56f),
            borderColor = if (warning) colors.warning.copy(alpha = 0.44f) else surfaces.tileBorder,
            fontSize = 10.sp,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
        )
        Text(
            text = caption,
            modifier = Modifier.weight(1f),
            color = colors.textMuted,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InsightCard(card: NovaGameDetailInsightCard) {
    val colors = LocalNovaComposeColors.current
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        accent = !card.isWarning,
        warning = card.isWarning,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.label,
                    color = if (card.isWarning) colors.warning else colors.accent,
                    fontSize = if (card.isWarning) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.source.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    NovaBadge(text = card.source, color = colors.textMuted)
                }
            }
            Text(
                text = card.settings,
                modifier = Modifier.padding(top = 5.dp),
                color = colors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (card.reasoning.isNotBlank()) {
                Text(
                    text = card.reasoning,
                    modifier = Modifier.padding(top = 3.dp),
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
