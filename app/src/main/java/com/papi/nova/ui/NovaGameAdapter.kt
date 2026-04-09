package com.papi.nova.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisGame
import okhttp3.Request
import java.util.concurrent.Executors

class NovaGameAdapter(
    private val apiClient: PolarisApiClient,
    private val onGameClick: (PolarisGame) -> Unit,
    private val onGameLongClick: ((PolarisGame) -> Unit)? = null
) : RecyclerView.Adapter<NovaGameAdapter.ViewHolder>() {

    private var games = listOf<PolarisGame>()

    // Shared image loader: 3 threads + LRU bitmap cache (8MB)
    private val imageExecutor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "Nova-Cover").apply { isDaemon = true }
    }
    private val coverCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt() // 1/8 max heap in KB
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun updateGames(newGames: List<PolarisGame>) {
        games = newGames
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.nova_game_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val game = games[position]
        holder.bind(game)
    }

    override fun getItemCount() = games.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverArt: ImageView = itemView.findViewById(R.id.nova_cover_art)
        private val gameName: TextView = itemView.findViewById(R.id.nova_game_name)
        private val categoryBadge: TextView = itemView.findViewById(R.id.nova_category_badge)
        private val sourceBadge: TextView = itemView.findViewById(R.id.nova_source_badge)
        private val genreChips: LinearLayout = itemView.findViewById(R.id.nova_genre_chips)

        fun bind(game: PolarisGame) {
            gameName.text = game.name

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

            // Genre chips (show up to 2)
            genreChips.removeAllViews()
            val genres = game.genres.take(2)
            if (genres.isNotEmpty()) {
                genreChips.visibility = View.VISIBLE
                for (genre in genres) {
                    val chip = TextView(itemView.context).apply {
                        text = genre
                        textSize = 9f
                        setTextColor(0xFF9CA3AF.toInt())
                        setPadding(12, 3, 12, 3)
                        val chipBg = GradientDrawable()
                        chipBg.cornerRadius = 6f
                        chipBg.setColor(0x1A6B7280.toInt())
                        background = chipBg
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    if (genreChips.childCount > 0) params.marginStart = 6
                    genreChips.addView(chip, params)
                }
            } else {
                genreChips.visibility = View.GONE
            }

            // Load cover art via shared OkHttp client + LRU cache
            coverArt.setImageResource(0)
            coverArt.setBackgroundColor(0xFF393c51.toInt())
            coverArt.tag = game.id  // tag to detect recycled views

            val cached = coverCache.get(game.id)
            if (cached != null) {
                coverArt.setImageBitmap(cached)
            } else {
                imageExecutor.execute {
                    try {
                        val url = apiClient.getCoverUrl(game.id)
                        val request = Request.Builder().url(url).build()
                        val response = apiClient.client.newCall(request).execute()
                        val bitmap = BitmapFactory.decodeStream(response.body?.byteStream())
                        response.close()
                        if (bitmap != null) {
                            coverCache.put(game.id, bitmap)
                            itemView.post {
                                // Only update if the view hasn't been recycled for a different game
                                if (coverArt.tag == game.id) {
                                    coverArt.setImageBitmap(bitmap)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // Click to launch
            itemView.setOnClickListener { onGameClick(game) }

            // Long-press for detail sheet
            itemView.setOnLongClickListener {
                onGameLongClick?.invoke(game)
                true
            }

            // D-pad focus styling — glow border on focused card
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = false
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val card = v as? androidx.cardview.widget.CardView ?: return@setOnFocusChangeListener
                if (hasFocus) {
                    card.cardElevation = 8f
                    card.setCardBackgroundColor(0xFF4c5265.toInt())
                    // Accent glow border
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
    }
}
