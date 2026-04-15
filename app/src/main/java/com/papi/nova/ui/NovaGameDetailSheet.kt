package com.papi.nova.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
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
 * Bottom sheet showing game details — cover art, metadata, genres, and launch button.
 * Triggered by long-pressing a game card in the library.
 */
class NovaGameDetailSheet : BottomSheetDialogFragment() {

    private var game: PolarisGame? = null
    private var apiClient: PolarisApiClient? = null
    private var onLaunch: ((PolarisGame) -> Unit)? = null

    companion object {
        fun newInstance(game: PolarisGame, apiClient: PolarisApiClient, onLaunch: (PolarisGame) -> Unit): NovaGameDetailSheet {
            return NovaGameDetailSheet().apply {
                this.game = game
                this.apiClient = apiClient
                this.onLaunch = onLaunch
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.nova_game_detail_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val game = this.game ?: return
        val apiClient = this.apiClient ?: return

        // Apply OLED theme to sheet background
        if (NovaThemeManager.isOled(requireContext())) {
            view.setBackgroundResource(R.drawable.nova_sheet_bg_oled)
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

        // Cover art via Coil
        val coverArt = view.findViewById<ImageView>(R.id.detail_cover)
        coverArt.load(apiClient.getCoverUrl(game.id)) {
            crossfade(200)
            error(R.color.nova_deep)
        }

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

        // Play button
        view.findViewById<MaterialButton>(R.id.detail_play_btn).setOnClickListener {
            onLaunch?.invoke(game)
            dismiss()
        }
    }
}
