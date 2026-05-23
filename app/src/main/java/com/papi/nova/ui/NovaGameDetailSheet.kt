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
import com.papi.nova.api.PolarisGame
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
    private var onLaunch: ((PolarisGame, Boolean, String, JSONObject?) -> Unit)? = null

    companion object {
        fun newInstance(
            game: PolarisGame,
            apiClient: PolarisApiClient,
            defaultToVirtualDisplay: Boolean,
            clientSettings: PolarisClientSettings?,
            onLaunch: (PolarisGame, Boolean, String, JSONObject?) -> Unit
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
            setBackgroundResource(sheetBackgroundRes())
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
                        syncLaunchPreflightSettings(requireContext(), apiClient, usesVirtualDisplay)?.let {
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
                        syncLaunchPreflightSettings(requireContext(), apiClient, uiState.playUsesVirtualDisplay)?.let {
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
                    playLabel = if (optimizationState.reviewRequired) {
                        getString(R.string.nova_library_review_and_launch)
                    } else {
                        optimizationState.profileSummary
                            ?.primaryLaunchLabel
                            ?.takeIf { it.isNotBlank() }
                            ?: primaryPlayLabel(uiState)
                    },
                    launchModeTitle = getString(R.string.nova_library_launch_mode_title),
                    headlessModeLabel = modeBadgeLabel("headless"),
                    virtualDisplayModeLabel = modeBadgeLabel("virtual_display"),
                    coverContentDescription = getString(R.string.nova_a11y_game_cover),
                    onPrimaryLaunch = {
                        if (!uiState.playEnabled) return@NovaGameDetailSheetContent
                        val launchConfirmed = {
                            onLaunch?.invoke(
                                currentGame.copy(mangohud = mangoHudEnabled),
                                uiState.playUsesVirtualDisplay,
                                profilePreference,
                                optimizationState.rawOptimization
                            )
                            dismiss()
                        }
                        if (optimizationState.reviewRequired) {
                            showPreflightReview(
                                optimizationState = optimizationState,
                                onLaunchConfirmed = launchConfirmed,
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
                            launchConfirmed()
                        }
                    },
                    onLaunchModeSelected = ::selectLaunchMode,
                    onProfilePreference = {
                        showProfilePreferenceOptions(currentGame) { selected ->
                            profilePreference = selected
                            refreshUiState(selected)
                            optimizationState = NovaGameDetailOptimizationState()
                            loadOptimization(selected)
                        }
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
                    onSteamLaunchMode = {
                        showSteamLaunchModeOptions(currentGame) { selected ->
                            val previousGame = currentGame
                            val updatedLaunch = previousGame.steamLaunch?.copy(
                                mode = PolarisGame.SteamLaunchContract.normalizeMode(selected)
                            )
                            currentGame = previousGame.copy(steamLaunch = updatedLaunch)
                            refreshUiState()
                            viewLifecycleOwner.lifecycleScope.launch {
                                val updated = withContext(Dispatchers.IO) {
                                    apiClient.setSteamLaunchMode(previousGame.id, selected)
                                }
                                val message = if (updated) {
                                    R.string.nova_steam_launch_mode_updated
                                } else {
                                    currentGame = previousGame
                                    refreshUiState()
                                    R.string.nova_steam_launch_mode_failed
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                            }
                        }
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
        game: PolarisGame,
        onChanged: (String) -> Unit
    ) {
        val values = AutoQualityProfilePreferences.values()
        val labels = values.map {
            when (it) {
                "quality" -> "Prefer Quality"
                "high_fps" -> "Prefer High FPS"
                "stability" -> "Prefer Stability"
                else -> "Auto"
            }
        }.toTypedArray()
        val checked = values.indexOf(loadProfilePreference(game)).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nova_library_profile_preference_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = values[which]
                saveProfilePreference(game, selected)
                onChanged(selected)
                dialog.dismiss()
            }
            .show()
    }

    private fun showSteamLaunchModeOptions(
        game: PolarisGame,
        onChanged: (String) -> Unit
    ) {
        val modes = listOf("direct", "big-picture")
        val labels = modes.map { steamLaunchModeLabel(it) }.toTypedArray()
        val checked = modes.indexOf(game.steamLaunchMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nova_steam_launch_options_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onChanged(modes[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun syncLaunchPreflightSettings(
        context: Context,
        apiClient: PolarisApiClient,
        usesVirtualDisplay: Boolean
    ): PolarisClientSettings? {
        val preferences = PreferenceConfiguration.readPreferences(context)
        return apiClient.updateClientSettings(
            streamDisplayMode = if (usesVirtualDisplay) "host_virtual_display" else "headless_stream",
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
        parts += when {
            uiState.virtualDisplayUnavailable -> uiState.virtualDisplayUnavailableReason
                .takeIf { it.isNotBlank() }
                ?: getString(R.string.nova_library_launch_intro_virtual_unavailable)
            uiState.launchChoice.hostModeReason.isNotBlank() -> uiState.launchChoice.hostModeReason
            uiState.game.launchMode?.modeReason?.isNotBlank() == true -> uiState.game.launchMode.modeReason
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
        sheet.setBackgroundResource(sheetBackgroundRes())
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

data class NovaGameDetailOptimizationState(
    val ai: NovaGameDetailInsightCard? = null,
    val stability: NovaGameDetailInsightCard? = null,
    val profileSummary: NovaLaunchProfileSummary? = null,
    val rawOptimization: JSONObject? = null,
    val reviewRequired: Boolean = false,
    val reviewReason: String = ""
)

data class NovaGameDetailInsightCard(
    val label: String,
    val source: String,
    val settings: String,
    val reasoning: String,
    val isWarning: Boolean
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
    playLabel: String,
    launchModeTitle: String,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    coverContentDescription: String,
    onPrimaryLaunch: () -> Unit,
    onLaunchModeSelected: (String) -> Unit,
    onProfilePreference: () -> Unit,
    onRetryHighFps: () -> Unit,
    onResetProfile: () -> Unit,
    onSteamLaunchMode: () -> Unit,
    coverLoader: (ImageView) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val verticalScroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(surfaces.panel)
            .verticalScroll(verticalScroll)
            .padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, bottom = 6.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.divider)
                .align(Alignment.CenterHorizontally)
        )

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
            profilePreferenceLabel = profilePreferenceLabel,
            profileSummary = optimizationState.profileSummary,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            headlessModeLabel = headlessModeLabel,
            virtualDisplayModeLabel = virtualDisplayModeLabel,
            onPrimaryLaunch = onPrimaryLaunch,
            onLaunchModeSelected = onLaunchModeSelected,
            onProfilePreference = onProfilePreference,
            onRetryHighFps = onRetryHighFps,
            onResetProfile = onResetProfile
        )

        SteamLaunchModeCard(
            visible = uiState.showSteamLaunchMode,
            label = steamLaunchLabel,
            modeLabel = steamLaunchModeLabel,
            caption = steamLaunchCaption,
            warning = uiState.steamLaunchWarning,
            onClick = onSteamLaunchMode
        )

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
    profilePreferenceLabel: String,
    profileSummary: NovaLaunchProfileSummary?,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    onPrimaryLaunch: () -> Unit,
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
            profilePreferenceLabel = profilePreferenceLabel,
            profileSummary = profileSummary,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            headlessModeLabel = headlessModeLabel,
            virtualDisplayModeLabel = virtualDisplayModeLabel,
            onPrimaryLaunch = onPrimaryLaunch,
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
    profilePreferenceLabel: String,
    profileSummary: NovaLaunchProfileSummary?,
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    headlessModeLabel: String,
    virtualDisplayModeLabel: String,
    onPrimaryLaunch: () -> Unit,
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

        NovaActionButton(
            text = profilePreferenceLabel,
            onClick = onProfilePreference,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            contentDescription = profilePreferenceLabel,
            minHeight = 42.dp,
            cornerRadius = 10.dp,
            fontSize = 12.sp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        )

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
