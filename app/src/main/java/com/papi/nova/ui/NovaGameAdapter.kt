package com.papi.nova.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisGame

class NovaGameAdapter(
    private val apiClient: PolarisApiClient,
    private val onGameClick: (PolarisGame) -> Unit,
    private val onGameLongClick: ((PolarisGame) -> Unit)? = null
) : RecyclerView.Adapter<NovaGameAdapter.ViewHolder>() {
    companion object {
        private const val PAYLOAD_REFRESH_COVER = "refresh_cover"
    }

    private var games = listOf<PolarisGame>()

    init {
        setHasStableIds(true)
    }

    fun updateGames(newGames: List<PolarisGame>) {
        val diffResult = DiffUtil.calculateDiff(GameDiffCallback(games, newGames))
        games = newGames
        diffResult.dispatchUpdatesTo(this)
    }

    fun reloadAllCovers() {
        if (games.isNotEmpty()) {
            notifyItemRangeChanged(0, games.size, PAYLOAD_REFRESH_COVER)
        }
    }

    private class GameDiffCallback(
        private val old: List<PolarisGame>,
        private val new: List<PolarisGame>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.nova_game_card, parent, false)
        return ViewHolder(view)
    }

    override fun getItemId(position: Int): Long = games[position].id.hashCode().toLong()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val game = games[position]
        holder.bind(game)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_REFRESH_COVER)) {
            holder.refreshCover(games[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = games.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverArt: ImageView = itemView.findViewById(R.id.nova_cover_art)
        private val gameName: TextView = itemView.findViewById(R.id.nova_game_name)
        private val gameMeta: TextView = itemView.findViewById(R.id.nova_game_meta)
        private val categoryBadge: TextView = itemView.findViewById(R.id.nova_category_badge)
        private val sourceBadge: TextView = itemView.findViewById(R.id.nova_source_badge)
        private val hdrBadge: TextView = itemView.findViewById(R.id.nova_hdr_badge)

        fun bind(game: PolarisGame) {
            gameName.text = game.name
            itemView.contentDescription = game.name
            coverArt.contentDescription = "${game.name} cover art"

            // Source badge (Steam, Lutris, Heroic)
            val srcLabel = game.sourceLabel
            if (srcLabel.isNotEmpty()) {
                sourceBadge.text = srcLabel
                sourceBadge.visibility = View.VISIBLE
                // Color-code by source
                val bgColor = when (game.source) {
                    "steam" -> 0x1A3B82F6.toInt()  // blue/10
                    "lutris" -> 0x1AF97316.toInt()  // orange/10
                    "heroic" -> 0x1AA855F7.toInt()  // purple/10
                    else -> 0x1A6B7280.toInt()
                }
                val textColor = when (game.source) {
                    "steam" -> 0xFF60A5FA.toInt()    // blue-400
                    "lutris" -> 0xFFFB923C.toInt()   // orange-400
                    "heroic" -> 0xFFC084FC.toInt()   // purple-400
                    else -> 0xFF9CA3AF.toInt()
                }
                sourceBadge.setTextColor(textColor)
                val bg = GradientDrawable()
                bg.cornerRadius = 8f
                bg.setColor(bgColor)
                sourceBadge.background = bg
            } else {
                sourceBadge.visibility = View.GONE
            }

            // Category badge
            val catLabel = game.categoryLabel
            if (catLabel.isNotEmpty()) {
                categoryBadge.text = catLabel
                categoryBadge.visibility = View.VISIBLE
            } else {
                categoryBadge.visibility = View.GONE
            }

            if (game.hdrSupported) {
                hdrBadge.visibility = View.VISIBLE
            } else {
                hdrBadge.visibility = View.GONE
            }

            gameMeta.text = buildMetaLine(game)

            refreshCover(game)

            itemView.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onGameClick(game)
            }

            // Long-press for detail sheet
            itemView.setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onGameLongClick?.invoke(game)
                true
            }

            // Touch response animation — scale down on press, overshoot back on release
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 0.96f),
                                ObjectAnimator.ofFloat(v, "scaleY", 0.96f)
                            )
                            duration = 100
                            interpolator = DecelerateInterpolator()
                            start()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1.0f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1.0f)
                            )
                            duration = 200
                            interpolator = OvershootInterpolator(2f)
                            start()
                        }
                    }
                }
                false // don't consume — let click/long-click still fire
            }

            // D-pad focus styling — glow border on focused card
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val card = v as? androidx.cardview.widget.CardView ?: return@setOnFocusChangeListener
                if (hasFocus) {
                    card.cardElevation = 8f
                    card.setCardBackgroundColor(0xFF4c5265.toInt())
                    v.scaleX = 1.03f
                    v.scaleY = 1.03f
                } else {
                    card.cardElevation = 2f
                    card.setCardBackgroundColor(
                        v.context.resources.getColor(R.color.nova_bg_card, v.context.theme)
                    )
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                }
            }
        }

        fun refreshCover(game: PolarisGame) {
            apiClient.loadCoverInto(coverArt, game)
        }

        private fun buildMetaLine(game: PolarisGame): String {
            if (game.lastLaunched > 0) {
                val relative = DateUtils.getRelativeTimeSpanString(
                    game.lastLaunched * 1000,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                return itemView.context.getString(R.string.nova_library_meta_last_played, relative)
            }

            val parts = mutableListOf<String>()
            if (game.genres.isNotEmpty()) {
                parts += game.genres.take(2)
            } else {
                if (game.sourceLabel.isNotEmpty()) parts += game.sourceLabel
                if (game.categoryLabel.isNotEmpty()) parts += game.categoryLabel
            }

            return if (parts.isEmpty()) {
                itemView.context.getString(R.string.nova_library_meta_ready_to_play)
            } else {
                parts.joinToString(" · ")
            }
        }
    }
}
