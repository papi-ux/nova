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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGame
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaBadge
import com.papi.nova.ui.compose.NovaComposeTheme
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
    private var onLaunch: ((PolarisGame, Boolean) -> Unit)? = null

    companion object {
        fun newInstance(
            game: PolarisGame,
            apiClient: PolarisApiClient,
            defaultToVirtualDisplay: Boolean,
            clientSettings: PolarisClientSettings?,
            onLaunch: (PolarisGame, Boolean) -> Unit
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

        fun loadOptimization(preference: String) {
            viewLifecycleOwner.lifecycleScope.launch {
                optimizationState = try {
                    val opt = withContext(Dispatchers.IO) {
                        apiClient.getOptimization(deviceName, currentGame.name, preference)
                    }
                    buildOptimizationState(opt, preference)
                } catch (_: Exception) {
                    NovaGameDetailOptimizationState()
                }
            }
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
                    mangoHudLabel = getString(R.string.nova_mangohud_detail_label),
                    mangoHudCaption = getString(mangoHudCaptionRes(uiState.mangoHudRisk)),
                    mangoHudWarning = uiState.mangoHudRisk != NovaGameDetailUiState.MangoHudRisk.NONE,
                    steamLaunchLabel = getString(R.string.nova_steam_launch_detail_label),
                    steamLaunchModeLabel = steamLaunchModeLabel(uiState.steamLaunchMode),
                    steamLaunchCaption = steamLaunchCaption(uiState),
                    optimizationState = optimizationState,
                    playLabel = getString(R.string.nova_library_play),
                    launchOptionsLabel = getString(R.string.nova_library_launch_options),
                    launchModeTitle = getString(R.string.nova_library_launch_mode_title),
                    coverContentDescription = getString(R.string.nova_a11y_game_cover),
                    onPrimaryLaunch = {
                        if (!uiState.playEnabled) return@NovaGameDetailSheetContent
                        onLaunch?.invoke(currentGame, uiState.playUsesVirtualDisplay)
                        dismiss()
                    },
                    onLaunchOptions = {
                        showLaunchOptions(currentGame, uiState)
                    },
                    onProfilePreference = {
                        showProfilePreferenceOptions(currentGame) { selected ->
                            profilePreference = selected
                            refreshUiState(selected)
                            optimizationState = NovaGameDetailOptimizationState()
                            loadOptimization(selected)
                        }
                    },
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
                    onMangoHudChanged = { enabled ->
                        mangoHudEnabled = enabled
                        if (enabled) {
                            mangoHudWarningRes(uiState.mangoHudRisk)?.let { warning ->
                                Toast.makeText(requireContext(), warning, Toast.LENGTH_LONG).show()
                            }
                        }
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            apiClient.setMangoHud(currentGame.id, enabled)
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

    private fun showLaunchOptions(
        game: PolarisGame,
        uiState: NovaGameDetailUiState
    ) {
        val options = mutableListOf<Pair<String, Boolean>>()
        if (uiState.headlessAllowed) {
            options += optionLabel("headless", uiState.recommendedMode) to false
        }
        if (uiState.virtualDisplayAllowed) {
            options += optionLabel("virtual_display", uiState.recommendedMode) to true
        }

        if (options.isEmpty()) {
            Toast.makeText(requireContext(), R.string.nova_library_no_launch_modes, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.nova_library_launch_options_title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                onLaunch?.invoke(game, options[which].second)
                dismiss()
            }
            .show()
    }

    private fun optionLabel(mode: String, recommendedMode: String): String {
        val label = modeLabel(mode)
        return if (mode == recommendedMode) {
            getString(R.string.nova_library_launch_recommended_format, label)
        } else {
            label
        }
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

    private fun mangoHudCaptionRes(risk: NovaGameDetailUiState.MangoHudRisk): Int {
        return when (risk) {
            NovaGameDetailUiState.MangoHudRisk.BIG_PICTURE -> R.string.nova_mangohud_detail_caption_big_picture
            NovaGameDetailUiState.MangoHudRisk.STEAM -> R.string.nova_mangohud_detail_caption_risky
            NovaGameDetailUiState.MangoHudRisk.NONE -> R.string.nova_mangohud_detail_caption
        }
    }

    private fun mangoHudWarningRes(risk: NovaGameDetailUiState.MangoHudRisk): Int? {
        return when (risk) {
            NovaGameDetailUiState.MangoHudRisk.BIG_PICTURE -> R.string.nova_mangohud_warning_big_picture
            NovaGameDetailUiState.MangoHudRisk.STEAM -> R.string.nova_mangohud_warning_steam
            NovaGameDetailUiState.MangoHudRisk.NONE -> null
        }
    }

    private fun buildOptimizationState(
        opt: JSONObject?,
        profilePreference: String
    ): NovaGameDetailOptimizationState {
        if (opt == null) return NovaGameDetailOptimizationState()

        val profileState = opt.optJSONObject("profile_state")
        val currentProfile = profileState?.optJSONObject("current_profile")
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
            val fullReasoning = listOf(profileReason, preferenceNote, reasoning, normalizationReason)
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

        return NovaGameDetailOptimizationState(ai = aiCard, stability = stabilityCard)
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
    val stability: NovaGameDetailInsightCard? = null
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
    mangoHudLabel: String,
    mangoHudCaption: String,
    mangoHudWarning: Boolean,
    steamLaunchLabel: String,
    steamLaunchModeLabel: String,
    steamLaunchCaption: String,
    optimizationState: NovaGameDetailOptimizationState,
    playLabel: String,
    launchOptionsLabel: String,
    launchModeTitle: String,
    coverContentDescription: String,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onProfilePreference: () -> Unit,
    onResetProfile: () -> Unit,
    onMangoHudChanged: (Boolean) -> Unit,
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
            .padding(bottom = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, bottom = 10.dp)
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
            launchOptionsLabel = launchOptionsLabel,
            profilePreferenceLabel = profilePreferenceLabel,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            onPrimaryLaunch = onPrimaryLaunch,
            onLaunchOptions = onLaunchOptions,
            onProfilePreference = onProfilePreference,
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

        MangoHudCard(
            enabled = mangoHudEnabled,
            label = mangoHudLabel,
            caption = mangoHudCaption,
            warning = mangoHudWarning,
            onChanged = onMangoHudChanged
        )

        optimizationState.ai?.let {
            InsightCard(card = it)
        }

        optimizationState.stability?.let {
            InsightCard(card = it)
        }
    }
}

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
    val game = uiState.game

    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp),
        contentDescription = "Game details",
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
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
                    .width(88.dp)
                    .aspectRatio(88f / 118f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.window)
                    .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = coverContentDescription }
            )

            Column(
                modifier = Modifier
                    .padding(start = 13.dp)
                    .weight(1f)
            ) {
                Text(
                    text = game.name,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                MetadataBadges(game)
                GenresRow(game.genres)

                if (lastPlayedText != null) {
                    Text(
                        text = lastPlayedText,
                        modifier = Modifier.padding(top = 7.dp),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onProfilePreference: () -> Unit,
    onResetProfile: () -> Unit
) {
    NovaDetailPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp),
        contentDescription = "Launch controls",
        accent = true,
        contentPadding = PaddingValues(13.dp)
    ) {
        LaunchControls(
            uiState = uiState,
            launchIntro = launchIntro,
            launchModeTitle = launchModeTitle,
            recommendedBadge = recommendedBadge,
            playLabel = playLabel,
            launchOptionsLabel = launchOptionsLabel,
            profilePreferenceLabel = profilePreferenceLabel,
            resetProfileLabel = resetProfileLabel,
            resetProfileWorking = resetProfileWorking,
            onPrimaryLaunch = onPrimaryLaunch,
            onLaunchOptions = onLaunchOptions,
            onProfilePreference = onProfilePreference,
            onResetProfile = onResetProfile
        )
    }
}

@Composable
private fun MetadataBadges(game: PolarisGame) {
    val horizontalScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
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
            .padding(top = 7.dp)
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
    resetProfileLabel: String,
    resetProfileWorking: Boolean,
    onPrimaryLaunch: () -> Unit,
    onLaunchOptions: () -> Unit,
    onProfilePreference: () -> Unit,
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
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (uiState.showRecommendedModeBadge) {
                Spacer(modifier = Modifier.width(8.dp))
                NovaBadge(text = recommendedBadge, color = colors.textSecondary)
            }
        }

        Text(
            text = launchIntro,
            modifier = Modifier.padding(top = 6.dp),
            color = colors.textMuted,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        NovaActionButton(
            text = playLabel,
            onClick = onPrimaryLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(playFocusRequester)
                .padding(top = 11.dp),
            enabled = uiState.playEnabled,
            primary = true,
            contentDescription = playLabel,
            minHeight = 44.dp,
            cornerRadius = 8.dp,
            fontSize = 13.sp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NovaActionButton(
                text = launchOptionsLabel,
                onClick = onLaunchOptions,
                modifier = Modifier.weight(1f),
                enabled = uiState.launchOptionsEnabled,
                contentDescription = launchOptionsLabel,
                minHeight = 40.dp,
                cornerRadius = 8.dp,
                fontSize = 12.sp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            )
            NovaActionButton(
                text = profilePreferenceLabel,
                onClick = onProfilePreference,
                modifier = Modifier.weight(1f),
                contentDescription = profilePreferenceLabel,
                minHeight = 40.dp,
                cornerRadius = 8.dp,
                fontSize = 12.sp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
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
            cornerRadius = 8.dp,
            fontSize = 11.sp,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp)
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
private fun MangoHudCard(
    enabled: Boolean,
    label: String,
    caption: String,
    warning: Boolean,
    onChanged: (Boolean) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    NovaFocusableCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp)
            .heightIn(min = 54.dp),
        onClick = { onChanged(!enabled) },
        contentDescription = "$label. $caption",
        contentPadding = PaddingValues(start = 12.dp, top = 9.dp, end = 8.dp, bottom = 9.dp)
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
            Switch(
                checked = enabled,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
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
