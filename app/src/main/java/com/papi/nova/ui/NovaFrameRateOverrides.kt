package com.papi.nova.ui

import android.content.Context
import com.papi.nova.shared.polaris.model.PolarisGame

/**
 * Where the frame rate chosen for a game in Play Setup is remembered.
 *
 * Mirrors [NovaResolutionOverrides] and [NovaLaunchModeOverrides]: a plain per-game
 * SharedPreferences entry, absent when the game has never had an explicit pin and
 * should keep following the resolution choice's own paired rate (or the host's plan).
 */
object NovaFrameRateOverrides {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_PREFIX = "frame_rate_override_"

    private fun key(game: PolarisGame): String =
        KEY_PREFIX + game.id.ifBlank { game.appId.toString() }

    fun load(context: Context, game: PolarisGame): Int? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key(game), 0)
        return stored.takeIf { it > 0 }
    }

    fun save(context: Context, game: PolarisGame, fps: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(key(game), fps)
            .apply()
    }

    /** Remove the per-game pin so the game follows the resolution/host rate again. */
    fun clear(context: Context, game: PolarisGame) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(game))
            .apply()
    }
}
