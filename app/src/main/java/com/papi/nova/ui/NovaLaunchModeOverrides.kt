package com.papi.nova.ui

import android.content.Context
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.isLaunchModeAvailable
import com.papi.nova.api.isLaunchModeSessionOverridable
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
    fun load(context: Context, game: PolarisGame): String? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val normalized = preferences
            .getString(key(game), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { PolarisGame.normalizeLaunchMode(it) }
        if (normalized == PolarisClientSettings.MODE_HEADLESS_DONGLE) {
            // v1.3.x briefly allowed this physical, host-wide swap to be saved per game.
            // Retire that one stale value at read time so an upgraded client cannot send
            // a launch override its own 1.4 picker correctly labels host-default-only.
            preferences.edit().remove(key(game)).apply()
            return null
        }
        return normalized
    }

    /**
     * Load a saved choice only while the current host and game still authorize it.
     *
     * A hidden invalid preference must not wake back up just because a later catalog
     * happens to advertise the same id again. Once current typed authority rejects a
     * saved per-game mode, retire that answer and let the player choose it again if it
     * becomes available in the future.
     */
    fun loadAvailable(
        context: Context,
        game: PolarisGame,
        clientSettings: PolarisClientSettings?,
    ): String? {
        val mode = load(context, game) ?: return null
        if (
            !game.isLaunchModeAvailable(mode, clientSettings) ||
            !clientSettings.isLaunchModeSessionOverridable(mode)
        ) {
            clear(context, game)
            return null
        }
        return mode
    }

    fun save(context: Context, game: PolarisGame, mode: String) {
        val normalized = PolarisGame.normalizeLaunchMode(mode)
        if (normalized == PolarisClientSettings.MODE_HEADLESS_DONGLE) {
            clear(context, game)
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(game), normalized)
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
