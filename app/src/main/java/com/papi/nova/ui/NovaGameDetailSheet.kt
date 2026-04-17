package com.papi.nova.ui

import android.graphics.drawable.GradientDrawable
import android.app.Dialog
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
import com.papi.nova.api.PolarisGame

/**
 * Bottom sheet showing game details, tuning, and explicit launch modes.
 * Triggered when opening a game from the Polaris library.
 */
class NovaGameDetailSheet : BottomSheetDialogFragment() {

    private var game: PolarisGame? = null
    private var apiClient: PolarisApiClient? = null
    private var defaultToVirtualDisplay: Boolean = false
    private var onLaunch: ((PolarisGame, Boolean) -> Unit)? = null

    companion object {
        fun newInstance(
            game: PolarisGame,
            apiClient: PolarisApiClient,
            defaultToVirtualDisplay: Boolean,
            onLaunch: (PolarisGame, Boolean) -> Unit
        ): NovaGameDetailSheet {
            return NovaGameDetailSheet().apply {
                this.game = game
                this.apiClient = apiClient
                this.defaultToVirtualDisplay = defaultToVirtualDisplay
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
        val launchContract = game.launchMode
        val fallbackMode = if (defaultToVirtualDisplay) "virtual_display" else "headless"
        val preferredMode = launchContract?.preferredMode?.takeIf { it.isNotBlank() } ?: fallbackMode
        val recommendedMode = launchContract?.recommendedMode?.takeIf { it.isNotBlank() } ?: preferredMode
        val headlessAllowed = launchContract?.allows("headless") ?: true
        val virtualAllowed = launchContract?.allows("virtual_display") ?: true

        // Apply OLED theme to sheet background
        if (NovaThemeManager.isOled(requireContext())) {
            view.setBackgroundResource(R.drawable.nova_sheet_bg_oled)
        }

        view.findViewById<TextView>(R.id.detail_launch_intro).text = buildLaunchIntro(
            preferredMode = preferredMode,
            recommendedMode = recommendedMode,
            serverReason = launchContract?.modeReason
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

        // AI optimization recommendation
        val aiCard = view.findViewById<View>(R.id.detail_ai_card)
        val aiLabel = view.findViewById<TextView>(R.id.detail_ai_label)
        val aiSettings = view.findViewById<TextView>(R.id.detail_ai_settings)
        val aiReasoning = view.findViewById<TextView>(R.id.detail_ai_reasoning)
        val aiSource = view.findViewById<TextView>(R.id.detail_ai_source)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val deviceName = android.os.Build.MODEL
                val opt = withContext(Dispatchers.IO) { apiClient.getOptimization(deviceName, game.name) }
                if (opt != null) {
                    val source = opt.optString("source", "")
                    val confidence = opt.optString("confidence", "")
                    val cacheStatus = opt.optString("cache_status", "")
                    val displayMode = opt.optString("display_mode", "")
                    val bitrate = opt.optInt("target_bitrate_kbps", 0)
                    val codec = opt.optString("preferred_codec", "")
                    val reasoning = opt.optString("reasoning", "")
                    val normalizationReason = opt.optString("normalization_reason", "")
                    val generatedAt = opt.optLong("generated_at", 0L)

                    if (displayMode.isNotEmpty() || codec.isNotEmpty()) {
                        val parts = mutableListOf<String>()
                        if (codec.isNotEmpty()) parts.add(codec.uppercase())
                        if (bitrate > 0) parts.add("${bitrate / 1000} Mbps")
                        if (displayMode.isNotEmpty()) parts.add(displayMode)
                        val settingsText = parts.joinToString(" · ")

                        val titleLabel = when {
                            source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                                getString(R.string.nova_library_ai_recovery_label)
                            source.contains("ai_cached") -> getString(R.string.nova_library_ai_cached_label)
                            source.contains("ai_live") -> getString(R.string.nova_library_ai_live_label)
                            source.contains("device_db") -> getString(R.string.nova_library_ai_baseline_label)
                            else -> getString(R.string.nova_library_ai_recommended_label)
                        }
                        val sourceLabel = when {
                            source.contains("ai_live") && cacheStatus.equals("invalidated", ignoreCase = true) ->
                                getString(R.string.nova_library_ai_recovery_label)
                            source.contains("ai_cached") -> getString(R.string.nova_library_ai_cached_label)
                            source.contains("ai_live") -> getString(R.string.nova_library_ai_live_label)
                            source.contains("device_db") -> getString(R.string.nova_library_ai_baseline_source_label)
                            else -> source
                        }
                        val stateLabel = when {
                            normalizationReason.isNotBlank() -> getString(R.string.nova_optimization_host_adjusted)
                            cacheStatus.equals("hit", ignoreCase = true) -> getString(R.string.nova_optimization_cached)
                            cacheStatus.equals("invalidated", ignoreCase = true) -> getString(R.string.nova_optimization_recovery)
                            cacheStatus.equals("miss", ignoreCase = true) -> getString(R.string.nova_optimization_fresh)
                            source.contains("device_db") -> getString(R.string.nova_optimization_device_tune)
                            else -> ""
                        }
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
                        aiSettings.text = settingsText
                        aiSource.text = listOf(
                            stateLabel.takeIf { it.isNotBlank() },
                            sourceLabel.takeIf { it.isNotBlank() && sourceLabel != titleLabel },
                            confidence.takeIf { it.isNotBlank() }?.lowercase()?.plus(" confidence"),
                            generatedLabel.takeIf { it.isNotBlank() }
                        ).filter { !it.isNullOrBlank() }.joinToString(" · ")
                        val fullReasoning = listOf(reasoning, normalizationReason)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        if (fullReasoning.isNotEmpty()) {
                            aiReasoning.text = fullReasoning
                            aiReasoning.visibility = View.VISIBLE
                        }
                        aiCard.visibility = View.VISIBLE
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
        val headlessButton = view.findViewById<MaterialButton>(R.id.detail_launch_headless_btn)
        val virtualButton = view.findViewById<MaterialButton>(R.id.detail_launch_virtual_btn)

        configureLaunchButton(
            button = headlessButton,
            isRecommended = recommendedMode == "headless",
            isAvailable = headlessAllowed,
            labelRes = R.string.nova_library_launch_headless
        )
        configureLaunchButton(
            button = virtualButton,
            isRecommended = recommendedMode == "virtual_display",
            isAvailable = virtualAllowed,
            labelRes = R.string.nova_library_launch_virtual_display
        )

        headlessButton.setOnClickListener {
            onLaunch?.invoke(game, false)
            dismiss()
        }
        virtualButton.setOnClickListener {
            onLaunch?.invoke(game, true)
            dismiss()
        }
    }

    private fun configureLaunchButton(
        button: MaterialButton,
        isRecommended: Boolean,
        isAvailable: Boolean,
        labelRes: Int
    ) {
        val label = if (!isAvailable) {
            getString(R.string.nova_library_launch_mode_unavailable_format, getString(labelRes))
        } else {
            getString(labelRes)
        }
        button.text = label
        button.isEnabled = isAvailable
        button.alpha = if (isAvailable) 1f else 0.45f
        if (isRecommended && isAvailable) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_accent))
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_ice))
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_badge_bg))
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.nova_text_primary))
            button.strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.nova_divider))
            button.strokeWidth = 2
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
        serverReason: String?
    ): String {
        val parts = mutableListOf<String>()
        if (preferredMode != recommendedMode) {
            parts += getString(R.string.nova_library_launch_preferred_mode_format, modeLabel(preferredMode))
        }
        parts += when {
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
