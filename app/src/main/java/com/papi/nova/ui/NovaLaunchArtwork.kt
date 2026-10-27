package com.papi.nova.ui

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import kotlin.math.roundToInt

/** The artwork a game's page showed when Play was pressed, and the tint it read from it. */
internal class NovaLaunchArtwork(val kind: Kind, val bitmap: Bitmap, val tint: Int?) {
    /** A hero stands behind the page full-bleed; a poster stands in a card where no hero exists. */
    enum class Kind { HERO, POSTER }
}

/**
 * The one launch whose artwork the next stream overlay may carry. The detail page offers it as
 * Play is pressed and closes; the library then does its own network work and starts Game, whose
 * first overlay takes it. It is one slot, for one host and app, for [LIFETIME_MS]. Taking always
 * empties it, so a later show, a reconnect or another way into a stream finds nothing and keeps
 * today's overlay. It lives in this process only, which Game shares.
 */
internal object NovaLaunchArtworkHandoff {
    /** Long enough for the library's launch work; past it the overlay starts without artwork. */
    const val LIFETIME_MS = 20_000L
    /** The longest side of the copy carried, so the slot never pins a full-size hero. */
    const val MAX_EDGE = 640

    private class Slot(val hostUuid: String, val appId: Int, val artwork: NovaLaunchArtwork, val offeredAtMs: Long)

    private var slot: Slot? = null

    /** Holds [artwork] for this host and app; anything incomplete just empties the slot. */
    @Synchronized
    fun offer(hostUuid: String?, appId: Int, artwork: NovaLaunchArtwork?, nowMs: Long) {
        slot = if (hostUuid.isNullOrBlank() || appId <= 0 || artwork == null) null
            else Slot(hostUuid, appId, artwork, nowMs)
    }

    /** The artwork offered for this host and app within [LIFETIME_MS], once. The slot is emptied either way. */
    @Synchronized
    fun take(hostUuid: String?, appId: Int, nowMs: Long): NovaLaunchArtwork? {
        val current = slot
        slot = null
        if (current == null || hostUuid.isNullOrBlank() || current.hostUuid != hostUuid || current.appId != appId) return null
        return current.artwork.takeIf { nowMs - current.offeredAtMs in 0..LIFETIME_MS }
    }

    /** A copy no longer than [MAX_EDGE] on either side, or the bitmap itself when it is already smaller. */
    fun carriedCopy(bitmap: Bitmap): Bitmap? = runCatching {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (bitmap.isRecycled || longest <= 0) return null
        if (longest <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / longest
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }.getOrNull()
}

/**
 * Whether the person turned animation off (Remove animations, or an animator scale of zero).
 * Compose already scales its own durations by this setting; it is read here only to leave out a
 * step whose first frame would otherwise flash before an instant finish.
 */
internal fun novaAnimationsOff(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
