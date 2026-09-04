package com.papi.nova.ui

import android.content.Context
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame

/** Remembers an explicit per-game encoder choice without changing Polaris' host setting. */
object NovaEncoderBackendOverrides {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_PREFIX = "encoder_backend_override_"

    private fun key(game: PolarisGame): String =
        KEY_PREFIX + game.id.ifBlank { game.appId.toString() }

    fun load(context: Context, game: PolarisGame): String? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = preferences.getString(key(game), null) ?: return null
        val normalized = PolarisClientSettings.normalizeEncoderBackend(raw)
        if (normalized == null) {
            preferences.edit().remove(key(game)).apply()
            return null
        }
        if (normalized != raw) {
            preferences.edit().putString(key(game), normalized).apply()
        }
        return normalized
    }

    /**
     * Resolve the saved choice against this host's typed, build-specific catalog.
     * A choice the current host can no longer select is retired so it cannot return
     * later as invisible launch authority.
     */
    fun loadAvailable(
        context: Context,
        game: PolarisGame,
        settings: PolarisClientSettings,
    ): String? {
        val saved = load(context, game) ?: return null
        val supported = settings.capabilities.sessionEncoderOverride &&
            settings.capabilities.encoders.any { option ->
                option.available &&
                    PolarisClientSettings.normalizeEncoderBackend(option.value) == saved
            }
        if (!supported) {
            clear(context, game)
            return null
        }
        return saved
    }

    fun save(context: Context, game: PolarisGame, backend: String) {
        val normalized = PolarisClientSettings.normalizeEncoderBackend(backend)
        if (normalized == null) {
            clear(context, game)
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(game), normalized)
            .apply()
    }

    /** Remove the explicit choice so this game follows Polaris' configured encoder again. */
    fun clear(context: Context, game: PolarisGame) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(game))
            .apply()
    }
}
