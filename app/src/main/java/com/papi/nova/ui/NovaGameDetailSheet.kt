package com.papi.nova.ui

import android.graphics.drawable.GradientDrawable
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // Apply OLED theme to sheet background
        if (NovaThemeManager.isOled(requireContext())) {
            view.setBackgroundResource(R.drawable.nova_sheet_bg_oled)
        }

        view.findViewById<TextView>(R.id.detail_launch_intro).text = if (defaultToVirtualDisplay) {
            getString(R.string.nova_library_launch_intro_virtual_default)
        } else {
            getString(R.string.nova_library_launch_intro_headless_default)
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
            val date = Date(game.lastLaunched * 1000)
            val fmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
            lastPlayed.text = "Last played: ${fmt.format(date)}"
            lastPlayed.visibility = View.VISIBLE
        }

        val coverArt = view.findViewById<ImageView>(R.id.detail_cover)
        apiClient.loadCoverInto(coverArt, game)

        // AI optimization recommendation
        val aiCard = view.findViewById<View>(R.id.detail_ai_card)
        val aiSettings = view.findViewById<TextView>(R.id.detail_ai_settings)
        val aiReasoning = view.findViewById<TextView>(R.id.detail_ai_reasoning)
        val aiSource = view.findViewById<TextView>(R.id.detail_ai_source)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val deviceName = android.os.Build.MODEL
                val opt = withContext(Dispatchers.IO) { apiClient.getOptimization(deviceName, game.name) }
                if (opt != null) {
                    val source = opt.optString("source", "")
                    val displayMode = opt.optString("display_mode", "")
                    val bitrate = opt.optInt("target_bitrate_kbps", 0)
                    val codec = opt.optString("preferred_codec", "")
                    val reasoning = opt.optString("reasoning", "")

                    if (displayMode.isNotEmpty() || codec.isNotEmpty()) {
                        val parts = mutableListOf<String>()
                        if (codec.isNotEmpty()) parts.add(codec.uppercase())
                        if (bitrate > 0) parts.add("${bitrate / 1000} Mbps")
                        if (displayMode.isNotEmpty()) parts.add(displayMode)
                        val settingsText = parts.joinToString(" · ")

                        val sourceLabel = when {
                            source.contains("ai_cached") -> "cached"
                            source.contains("ai_live") -> "live"
                            source.contains("device_db") -> "device profile"
                            else -> source
                        }

                        aiSettings.text = settingsText
                        aiSource.text = sourceLabel
                        if (reasoning.isNotEmpty()) {
                            aiReasoning.text = reasoning
                            aiReasoning.visibility = View.VISIBLE
                        }
                        aiCard.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {}
        }

        // MangoHud toggle
        val mangoToggle = view.findViewById<SwitchMaterial>(R.id.detail_mangohud_toggle)
        val mangoNote = view.findViewById<TextView>(R.id.detail_mangohud_note)
        val mangoRiskMessageRes = when {
            game.isSteamBigPicture -> R.string.nova_mangohud_warning_big_picture
            game.hasMangoHudCompatibilityRisk -> R.string.nova_mangohud_warning_steam
            else -> null
        }
        if (mangoRiskMessageRes != null) {
            mangoNote.setText(mangoRiskMessageRes)
            mangoNote.visibility = View.VISIBLE
        } else {
            mangoNote.visibility = View.GONE
        }
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
        val defaultBadge = view.findViewById<TextView>(R.id.detail_default_mode_badge)

        configureLaunchButton(
            button = headlessButton,
            isDefault = !defaultToVirtualDisplay,
            labelRes = R.string.nova_library_launch_headless
        )
        configureLaunchButton(
            button = virtualButton,
            isDefault = defaultToVirtualDisplay,
            labelRes = R.string.nova_library_launch_virtual_display
        )

        defaultBadge.text = if (defaultToVirtualDisplay) {
            getString(R.string.nova_library_launch_default_mode_badge, getString(R.string.nova_library_launch_virtual_display))
        } else {
            getString(R.string.nova_library_launch_default_mode_badge, getString(R.string.nova_library_launch_headless))
        }

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
        isDefault: Boolean,
        labelRes: Int
    ) {
        val label = if (isDefault) {
            getString(R.string.nova_library_launch_mode_default_format, getString(labelRes))
        } else {
            getString(labelRes)
        }
        button.text = label
        if (isDefault) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.nova_accent))
            button.setTextColor(requireContext().getColor(R.color.nova_ice))
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.nova_badge_bg))
            button.setTextColor(requireContext().getColor(R.color.nova_text_primary))
            button.strokeColor = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.nova_divider))
            button.strokeWidth = 2
        }
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
            val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).toInt()
            val contentHeight = contentView.measuredHeight.takeIf { it > 0 } ?: return@post
            val desiredHeight = contentHeight.coerceAtMost(maxHeight)

            contentView.layoutParams = contentView.layoutParams.apply {
                height = if (contentHeight > maxHeight) desiredHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            sheet.layoutParams = sheet.layoutParams.apply {
                height = desiredHeight
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
