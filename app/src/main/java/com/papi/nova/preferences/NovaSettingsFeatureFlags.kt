package com.papi.nova.preferences

import android.content.Context
import androidx.preference.PreferenceManager

object NovaSettingsFeatureFlags {
    const val COMPOSE_SETTINGS_KEY = "nova_compose_settings_beta"
    const val EXTRA_FORCE_LEGACY = "nova_force_legacy_settings"

    fun isComposeSettingsEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(COMPOSE_SETTINGS_KEY, true)
    }

    fun setComposeSettingsEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(COMPOSE_SETTINGS_KEY, enabled)
            .apply()
    }
}
