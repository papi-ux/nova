package com.papi.nova.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour a game's artwork lends its detail page, read once from the bitmap the page has
 * already decoded, so nothing is fetched or decoded again. It is the Polaris dashboard's rule
 * (artwork-tint.js): colourful pixels are grouped by hue and the strongest run of neighbouring
 * hues gives the tint, so a blue cover with an orange title tints blue rather than a muddy mean of
 * the two. A cover with too little colour has none, and the page keeps its theme colours.
 */
internal object NovaArtworkTint {
    const val SIZE = 24
    private const val HUES = 12
    // Pixels nearer grey than this carry no hue worth tinting with.
    private const val MIN_CHROMA = 24
    // Under one opaque pixel in twenty with colour, a cover counts as grey. A double, as in the
    // dashboard, so the same cover gives the same answer on both.
    private const val MIN_COLOURFUL = 0.05
    // Every tint has the same brightest channel, so a pale cover and a dark one tint alike. No
    // channel above it means a wash in this colour can only darken a light pixel under text.
    const val PEAK = 150
    // Most recently used last. Covers with no tint are kept too, so one is not read again each
    // time its page opens.
    const val LIMIT = 32
    /** Remembered for a cover with no tint. Every real tint is opaque, so it never collides. */
    const val NO_TINT = 0

    // One total 32-entry bound across hosts. Relative artwork routes and host-local
    // revisions can match on different hosts; an unknown owner is never memoized.
    // Lazy, so the pure rule below needs no Android class at all.
    private data class Key(val hostUuid: String, val presentation: String)
    private val memo by lazy { LruCache<Key, Int>(LIMIT) }

    /** The tint of these ARGB pixels as an opaque colour, or null for none. */
    fun fromPixels(pixels: IntArray): Int? {
        val weight = DoubleArray(HUES)
        val red = DoubleArray(HUES)
        val green = DoubleArray(HUES)
        val blue = DoubleArray(HUES)
        var opaque = 0
        var colourful = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            opaque++
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b)
            val chroma = max - minOf(r, g, b)
            if (chroma < MIN_CHROMA) continue
            colourful++
            val hue = when (max) {
                r -> (g - b).toDouble() / chroma
                g -> 2.0 + (b - r).toDouble() / chroma
                else -> 4.0 + (r - g).toDouble() / chroma
            }
            // Vivid pixels count for more than dull ones.
            val group = floor(((hue + 6.0) % 6.0) * HUES / 6.0).toInt() % HUES
            weight[group] += chroma.toDouble()
            red[group] += r.toDouble() * chroma
            green[group] += g.toDouble() * chroma
            blue[group] += b.toDouble() * chroma
        }
        if (opaque == 0 || colourful < opaque * MIN_COLOURFUL) return null
        var best = 0
        var bestWeight = -1.0
        for (at in 0 until HUES) {
            val runWeight = weight[(at + HUES - 1) % HUES] + weight[at] + weight[(at + 1) % HUES]
            if (runWeight > bestWeight) {
                best = at
                bestWeight = runWeight
            }
        }
        fun mean(channel: DoubleArray): Double =
            (channel[(best + HUES - 1) % HUES] + channel[best] + channel[(best + 1) % HUES]) / bestWeight
        val mean = doubleArrayOf(mean(red), mean(green), mean(blue))
        val peak = mean.max()
        val scaled = mean.map { (Math.round(it * PEAK / peak).toInt()).coerceIn(0, 255) }
        return (0xFF shl 24) or (scaled[0] shl 16) or (scaled[1] shl 8) or scaled[2]
    }

    /**
     * The tint of a decoded bitmap, or null when it has none or cannot be read. Two filtered
     * steps keep a 1080p hero from being sampled at a handful of its pixels.
     */
    fun fromBitmap(bitmap: Bitmap): Int? = runCatching {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val coarse = if (bitmap.width > SIZE * 4 || bitmap.height > SIZE * 4) {
            Bitmap.createScaledBitmap(bitmap, SIZE * 4, SIZE * 4, true)
        } else {
            bitmap
        }
        val sample = Bitmap.createScaledBitmap(coarse, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        sample.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        fromPixels(pixels)
    }.getOrNull()

    /** What was read for [key] before: null when it never was, [NO_TINT] for a cover with none. */
    fun remembered(hostUuid: String?, key: String): Int? {
        if (hostUuid.isNullOrBlank()) return null
        return synchronized(memo) { memo.get(Key(hostUuid, key)) }
    }

    fun remember(hostUuid: String?, key: String, tint: Int?) {
        if (hostUuid.isNullOrBlank()) return
        synchronized(memo) { memo.put(Key(hostUuid, key), tint ?: NO_TINT) }
    }
}

/**
 * The tint for the artwork shown under [key], read off the main thread from [bitmap] the first
 * time and remembered after. A new key starts from what was remembered for it, never from the
 * previous artwork's tint.
 */
@Composable
internal fun rememberNovaArtworkTint(hostUuid: String?, key: String, bitmap: Bitmap?): Int? {
    val tint = remember(hostUuid, key) {
        mutableStateOf(NovaArtworkTint.remembered(hostUuid, key)?.takeUnless { it == NovaArtworkTint.NO_TINT })
    }
    LaunchedEffect(hostUuid, key, bitmap) {
        val shown = bitmap ?: return@LaunchedEffect
        var read = NovaArtworkTint.remembered(hostUuid, key)
        if (read == null) {
            val fresh = withContext(Dispatchers.Default) { NovaArtworkTint.fromBitmap(shown) }
            NovaArtworkTint.remember(hostUuid, key, fresh)
            read = fresh ?: NovaArtworkTint.NO_TINT
        }
        tint.value = read.takeUnless { it == NovaArtworkTint.NO_TINT }
    }
    return tint.value
}

/**
 * An artwork view that says which bitmap it shows whenever its drawable changes, so its page can
 * read a tint from pixels it already has. Real artwork arrives through setImageBitmap, which
 * hands a BitmapDrawable to setImageDrawable; a title card or a cleared view reports null.
 *
 * It stays a framework ImageView, like the views it replaces. AppCompat's would also route
 * setImageResource through setImageDrawable, and Big Picture's bundled Steam mark is a PNG
 * resource that must not be read as a game's artwork.
 */
@SuppressLint("AppCompatCustomView")
internal class NovaArtworkImageView(context: Context) : ImageView(context) {
    // Nullable and unset at construction: ImageView's constructor may set a drawable before this
    // class's own fields exist.
    var onArtwork: ((Bitmap?) -> Unit)? = null

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        onArtwork?.invoke((drawable as? BitmapDrawable)?.bitmap)
    }
}
