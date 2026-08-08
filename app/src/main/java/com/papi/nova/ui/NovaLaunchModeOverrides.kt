package com.papi.nova.ui

import android.content.Context
import com.papi.nova.shared.polaris.model.PolarisGame

/**
 * Where the launch mode you choose for a game is remembered.
 *
 * It cannot live in the game's launch contract: preferredMode there means the app's own
 * default and is deliberately outranked by the host's configured display mode, so a
 * choice written into it is resolved away. This is a separate, higher answer to the same
 * question — what this client should do for this game — and it is only ever written by
 * choosing in the Launch Mode destination.
 */
object NovaLaunchModeOverrides {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_PREFIX = "launch_mode_override_"

    private fun key(game: PolarisGame): String =
        KEY_PREFIX + game.id.ifBlank { game.appId.toString() }

    // Values written before the canonical-id vocabulary ("headless",
    // "virtual_display") map forward at read time, forever — no bulk migration,
    // no stranded prefs.
    fun load(context: Context, game: PolarisGame): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(game), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { PolarisGame.normalizeLaunchMode(it) }

    fun save(context: Context, game: PolarisGame, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(game), PolarisGame.normalizeLaunchMode(mode))
            .apply()
    }

    /** Remove the per-game choice so the game follows the host default again. */
    fun clear(context: Context, game: PolarisGame) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(game))
            .apply()
    }
}
