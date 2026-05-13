package com.papi.nova.ui

import android.graphics.drawable.GradientDrawable
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.format.DateUtils
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGame
import com.papi.nova.utils.DeviceUtils

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.nova_game_detail_sheet, container, false)
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
        val deviceName = DeviceUtils.getModel()
        val launchContract = game.launchMode
        val launchChoice = game.resolveLaunchModeChoice(defaultToVirtualDisplay, clientSettings)
        val preferredMode = launchChoice.preferredMode
        val recommendedMode = launchChoice.recommendedMode
        val headlessAllowed = launchChoice.headlessAllowed
        val virtualAllowed = launchChoice.virtualDisplayAllowed

        // Apply OLED theme to sheet background
        if (NovaThemeManager.isOled(requireContext())) {
            view.setBackgroundResource(R.drawable.nova_sheet_bg_oled)
        }

        view.findViewById<TextView>(R.id.detail_launch_intro).text = buildLaunchIntro(
            preferredMode = preferredMode,
            recommendedMode = recommendedMode,
            serverReason = launchChoice.hostModeReason.takeIf { it.isNotBlank() }
                ?: launchContract?.modeReason,
            virtualDisplayUnavailable = launchChoice.virtualDisplayUnavailable,
            virtualDisplayUnavailableReason = launchChoice.virtualDisplayUnavailableReason
        )

        val launchModeTitle = view.findViewById<TextView>(R.id.detail_default_mode_badge)
        launchModeTitle.text = getString(
            R.string.nova_library_launch_recommended_mode_badge,
            modeBadgeLabel(recommendedMode)
        )

        if (!headlessAllowed && !virtualAllowed) {
            launchModeTitle.visibility = View.GONE
        }

        // Name
        view.findViewById<TextView>(R.id.detail_name).text = game.name

        // Source badge
        val sourceBadge = view.findViewById<TextView>(R.id.detail_source)
        val srcLabel = game.sourceLabel
        if (srcLabel.isNotEmpty()) {
            sourceBadge.text = srcLabel
            sourceBadge.visibility = View.VISIBLE
        } else {
            sourceBadge.visibility = View.GONE
        }

        // Category badge
        val catBadge = view.findViewById<TextView>(R.id.detail_category)
        val catLabel = game.categoryLabel
        if (catLabel.isNotEmpty()) {
            catBadge.text = catLabel
            catBadge.visibility = View.VISIBLE
        } else {
            catBadge.visibility = View.GONE
        }

        // Genre chips
        val genreContainer = view.findViewById<LinearLayout>(R.id.detail_genres)
        if (game.genres.isNotEmpty()) {
            genreContainer.visibility = View.VISIBLE
            for (genre in game.genres) {
                val chip = TextView(requireContext()).apply {
                    text = genre
                    textSize = 11f
                    setTextColor(0xFF9CA3AF.toInt())
                    setPadding(16, 6, 16, 6)
                    val bg = GradientDrawable()
                    bg.cornerRadius = 8f
                    bg.setColor(0x1A6B7280.toInt())
                    background = bg
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (genreContainer.childCount > 0) params.marginStart = 8
                genreContainer.addView(chip, params)
            }
        }

        // Last played
        val lastPlayed = view.findViewById<TextView>(R.id.detail_last_played)
        if (game.lastLaunched > 0) {
            val relative = DateUtils.getRelativeTimeSpanString(
                game.lastLaunched * 1000,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            lastPlayed.text = getString(R.string.nova_library_meta_last_played, relative)
            lastPlayed.visibility = View.VISIBLE
        }

        val coverArt = view.findViewById<ImageView>(R.id.detail_cover)
        apiClient.loadCoverInto(coverArt, game)

        // Auto Quality launch recommendation
        val aiCard = view.findViewById<View>(R.id.detail_ai_card)
        val aiLabel = view.findViewById<TextView>(R.id.detail_ai_label)
        val aiSettings = view.findViewById<TextView>(R.id.detail_ai_settings)
        val aiReasoning = view.findViewById<TextView>(R.id.detail_ai_reasoning)
        val aiSource = view.findViewById<TextView>(R.id.detail_ai_source)
        val stabilityCard = view.findViewById<View>(R.id.detail_stability_card)
        val stabilityLabel = view.findViewById<TextView>(R.id.detail_stability_label)
        val stabilitySettings = view.findViewById<TextView>(R.id.detail_stability_settings)
        val stabilityReasoning = view.findViewById<TextView>(R.id.detail_stability_reasoning)
        val preferenceButton = view.findViewById<MaterialButton>(R.id.detail_profile_preference_btn)
        var profilePreference = loadProfilePreference(game)
        configureProfilePreferenceButton(preferenceButton, profilePreference)
        preferenceButton.setOnClickListener {
            showProfilePreferenceOptions(game, preferenceButton) { selected ->
                profilePreference = selected
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val opt = withContext(Dispatchers.IO) { apiClient.getOptimization(deviceName, game.name, profilePreference) }
                if (opt != null) {
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

                    if (displayMode.isNotEmpty() || codec.isNotEmpty() || profileState != null) {
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

                        aiLabel.text = titleLabel
                        aiLabel.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                if (cacheStatus.equals("invalidated", ignoreCase = true)) {
                                    R.color.nova_warning
                                } else {
                                    R.color.nova_accent
                                }
                            )
                        )
                        aiSettings.text = settingsText
                        val sourceText = listOf(
                            stateLabel.takeIf { it.isNotBlank() },
                            profileState?.optString("preference_label", "")?.takeIf { it.isNotBlank() },
                            lastResultText.takeIf { it.isNotBlank() },
                            sourceLabel.takeIf { it.isNotBlank() && sourceLabel != titleLabel },
                            confidence.takeIf { it.isNotBlank() }?.lowercase()?.plus(" confidence"),
                            generatedLabel.takeIf { it.isNotBlank() }
                        ).filter { !it.isNullOrBlank() }.joinToString(" · ")
                        aiSource.text = sourceText
                        aiSource.visibility = if (sourceText.isBlank()) View.GONE else View.VISIBLE
                        val profileReason = profileState?.optString("reason", "").orEmpty()
                        val preferenceNote = profileState
                            ?.optString("preference_note", "")
                            ?.takeIf { profilePreference != "auto" }
                            .orEmpty()
                        val fullReasoning = listOf(profileReason, preferenceNote, reasoning, normalizationReason)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        if (fullReasoning.isNotEmpty()) {
                            aiReasoning.text = fullReasoning
                            aiReasoning.visibility = View.VISIBLE
                        } else {
                            aiReasoning.visibility = View.GONE
                        }
                        aiCard.visibility = View.VISIBLE
                    }

                    opt.optJSONObject("stability")?.let { stability ->
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
                            stabilityLabel.text = when {
                                isStabilityFirst -> "Recovery Profile"
                                relaunchRequired -> "Recovery Queued"
                                else -> "Safer Fallback"
                            }
                            stabilityLabel.setTextColor(
                                ContextCompat.getColor(
                                    requireContext(),
                                    if (isStabilityFirst) R.color.nova_warning else R.color.nova_accent
                                )
                            )
                            stabilitySettings.text = if (safeProfileParts.isNotEmpty()) {
                                safeProfileParts.joinToString(" · ")
                            } else {
                                "Safer next launch"
                            }
                            stabilityReasoning.text = stabilityDetails
                            stabilityCard.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // MangoHud toggle
        val mangoToggle = view.findViewById<SwitchMaterial>(R.id.detail_mangohud_toggle)
        val mangoCaption = view.findViewById<TextView>(R.id.detail_mangohud_caption)
        val mangoRiskMessageRes = when {
            game.isSteamBigPicture -> R.string.nova_mangohud_warning_big_picture
            game.hasMangoHudCompatibilityRisk -> R.string.nova_mangohud_warning_steam
            else -> null
        }
        mangoCaption.setText(
            when (mangoRiskMessageRes) {
                R.string.nova_mangohud_warning_big_picture -> R.string.nova_mangohud_detail_caption_big_picture
                R.string.nova_mangohud_warning_steam -> R.string.nova_mangohud_detail_caption_risky
                else -> R.string.nova_mangohud_detail_caption
            }
        )
        mangoCaption.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (mangoRiskMessageRes != null) R.color.nova_warning else R.color.nova_text_secondary
            )
        )
        mangoToggle.isChecked = game.mangohud
        mangoToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && mangoRiskMessageRes != null) {
                Toast.makeText(requireContext(), mangoRiskMessageRes, Toast.LENGTH_LONG).show()
            }
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                apiClient.setMangoHud(game.id, isChecked)
            }
        }

        // Launch buttons
        val playButton = view.findViewById<MaterialButton>(R.id.detail_launch_headless_btn)
        val optionsButton = view.findViewById<MaterialButton>(R.id.detail_launch_virtual_btn)
        val resetProfileButton = view.findViewById<MaterialButton>(R.id.detail_reset_profile_btn)
        val playMode = when {
            recommendedMode == "virtual_display" && virtualAllowed -> "virtual_display"
            recommendedMode == "headless" && headlessAllowed -> "headless"
            headlessAllowed -> "headless"
            virtualAllowed -> "virtual_display"
            else -> ""
        }

        configurePrimaryPlayButton(playButton, playMode.isNotBlank())
        configureOptionsButton(optionsButton, headlessAllowed || virtualAllowed)
        configureResetProfileButton(resetProfileButton, false)

        playButton.setOnClickListener {
            if (playMode.isBlank()) {
                return@setOnClickListener
            }
            onLaunch?.invoke(game, playMode == "virtual_display")
            dismiss()
        }
        optionsButton.setOnClickListener {
            showLaunchOptions(game, headlessAllowed, virtualAllowed, recommendedMode)
        }
        resetProfileButton.setOnClickListener {
            configureResetProfileButton(resetProfileButton, true)
            viewLifecycleOwner.lifecycleScope.launch {
                val cleared = withContext(Dispatchers.IO) {
                    apiClient.clearOptimizerProfile(deviceName, game.name)
                }
                val sheetContext = context ?: return@launch
                if (cleared == true) {
                    aiCard.visibility = View.GONE
                    stabilityCard.visibility = View.GONE
                }
                val message = when (cleared) {
                    true -> R.string.nova_library_reset_game_profile_cleared
                    false -> R.string.nova_library_reset_game_profile_empty
                    null -> R.string.nova_library_reset_game_profile_failed
                }
                Toast.makeText(sheetContext, message, Toast.LENGTH_SHORT).show()
                configureResetProfileButton(resetProfileButton, false)
            }
        }
    }

    private fun configurePrimaryPlayButton(button: MaterialButton, isAvailable: Boolean) {
        button.text = getString(R.string.nova_library_play)
        button.isEnabled = isAvailable
        button.alpha = if (isAvailable) 1f else 0.45f
        if (isAvailable) {
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_accent))
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_ice))
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_badge_bg))
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_text_primary))
            button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_divider))
            button.strokeWidth = 2
        }
    }

    private fun configureOptionsButton(button: MaterialButton, isAvailable: Boolean) {
        button.text = getString(R.string.nova_library_launch_options)
        button.isEnabled = isAvailable
        button.alpha = if (isAvailable) 1f else 0.45f
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_badge_bg))
        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_text_primary))
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_divider))
        button.strokeWidth = 2
    }

    private fun configureResetProfileButton(button: MaterialButton, isWorking: Boolean) {
        button.text = getString(
            if (isWorking) {
                R.string.nova_library_reset_game_profile_working
            } else {
                R.string.nova_library_reset_game_profile
            }
        )
        button.isEnabled = !isWorking
        button.alpha = if (isWorking) 0.55f else 0.9f
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.transparent))
        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_text_secondary))
        button.strokeWidth = 0
    }

    private fun loadProfilePreference(game: PolarisGame): String {
        return requireContext()
            .getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
            .getString(profilePreferenceKey(game.name), "auto")
            ?.takeIf { it in profilePreferenceValues() }
            ?: "auto"
    }

    private fun saveProfilePreference(game: PolarisGame, preference: String) {
        requireContext()
            .getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(profilePreferenceKey(game.name), preference)
            .apply()
    }

    private fun profilePreferenceKey(gameName: String): String {
        return "ai_profile_preference_name_$gameName"
    }

    private fun profilePreferenceValues(): Array<String> {
        return arrayOf("auto", "quality", "high_fps", "stability")
    }

    private fun configureProfilePreferenceButton(button: MaterialButton, preference: String) {
        button.text = getString(
            when (preference) {
                "quality" -> R.string.nova_library_profile_preference_quality
                "high_fps" -> R.string.nova_library_profile_preference_high_fps
                "stability" -> R.string.nova_library_profile_preference_stability
                else -> R.string.nova_library_profile_preference_auto
            }
        )
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), android.R.color.transparent))
        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_text_primary))
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_divider))
        button.strokeWidth = 1
    }

    private fun showProfilePreferenceOptions(
        game: PolarisGame,
        button: MaterialButton,
        onChanged: (String) -> Unit
    ) {
        val values = profilePreferenceValues()
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
                configureProfilePreferenceButton(button, selected)
                onChanged(selected)
                dialog.dismiss()
            }
            .show()
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
        val rounded = kotlin.math.round(fps)
        return if (kotlin.math.abs(fps - rounded) < 0.01) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", fps)
        }
    }

    private fun buildLastResultText(lastResult: org.json.JSONObject?): String {
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

    private fun showLaunchOptions(
        game: PolarisGame,
        headlessAllowed: Boolean,
        virtualAllowed: Boolean,
        recommendedMode: String
    ) {
        val options = mutableListOf<Pair<String, Boolean>>()
        if (headlessAllowed) {
            options += optionLabel("headless", recommendedMode) to false
        }
        if (virtualAllowed) {
            options += optionLabel("virtual_display", recommendedMode) to true
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

    private fun buildLaunchIntro(
        preferredMode: String,
        recommendedMode: String,
        serverReason: String?,
        virtualDisplayUnavailable: Boolean,
        virtualDisplayUnavailableReason: String
    ): String {
        val parts = mutableListOf<String>()
        if (preferredMode != recommendedMode) {
            parts += getString(R.string.nova_library_launch_preferred_mode_format, modeLabel(preferredMode))
        }
        parts += when {
            virtualDisplayUnavailable -> virtualDisplayUnavailableReason
                .takeIf { it.isNotBlank() }
                ?: getString(R.string.nova_library_launch_intro_virtual_unavailable)
            !serverReason.isNullOrBlank() -> serverReason
            recommendedMode == "virtual_display" -> getString(R.string.nova_library_launch_intro_virtual_default)
            else -> getString(R.string.nova_library_launch_intro_headless_default)
        }
        return parts.joinToString(" ")
    }

    private fun expandBottomSheet(bottomSheetDialog: BottomSheetDialog?) {
        val sheet = bottomSheetDialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val contentView = view ?: return
        sheet.setBackgroundResource(
            if (NovaThemeManager.isOled(requireContext())) {
                R.drawable.nova_sheet_bg_oled
            } else {
                R.drawable.nova_sheet_bg
            }
        )
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
