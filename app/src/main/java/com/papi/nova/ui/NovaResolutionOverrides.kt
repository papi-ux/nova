package com.papi.nova.ui

import android.content.Context
import com.papi.nova.shared.polaris.model.PolarisGame

/**
 * Where the resolution chosen for a game in Play Setup is remembered.
 *
 * [NovaDisplayResolutionChoice] is rebuilt fresh from the planner on every screen open, so
 * only the choice's stable [NovaDisplayResolutionChoice.id] is persisted here — the id is
 * resolved back against that game's current [NovaDisplayResolutionPlanner.visibleChoices]
 * at load time, the same way [NovaLaunchModeOverrides] resolves a saved mode id.
 */
object NovaResolutionOverrides {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_PREFIX = "resolution_override_"

    private fun key(game: PolarisGame): String =
        KEY_PREFIX + game.id.ifBlank { game.appId.toString() }

    fun load(context: Context, game: PolarisGame): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(game), null)
            ?.takeIf { it.isNotBlank() }

    fun save(context: Context, game: PolarisGame, choiceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(game), choiceId)
            .apply()
    }

    /** Remove the per-game choice so the game follows the planner's recommendation again. */
    fun clear(context: Context, game: PolarisGame) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(game))
            .apply()
    }
}
