package com.papi.nova.ui

import android.content.Context
import android.content.SharedPreferences
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsRepository
import com.papi.nova.preferences.NovaSettingsStore

object NovaHudPreferences {
    const val KEY_OPACITY = "nova_polaris_hud_opacity"
    const val DEFAULT_OPACITY_PERCENT = 64
    const val MIN_OPACITY_PERCENT = 0
    const val MAX_OPACITY_PERCENT = 100

    val OPACITY_PRESETS = listOf(0, 25, 64, 90, 100)

    fun coerceOpacityPercent(percent: Int): Int {
        return percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
    }

    fun readOpacityPercent(prefs: SharedPreferences): Int {
        return coerceOpacityPercent(prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY_PERCENT))
    }

    fun writeOpacityPercent(prefs: SharedPreferences, percent: Int) {
        prefs.edit()
            .putInt(KEY_OPACITY, coerceOpacityPercent(percent))
            .apply()
    }

    suspend fun writeOpacityPercent(context: Context, percent: Int) {
        val definitions = NovaSettingDefinitions.load(context)
        writeOpacityPercent(
            store = NovaSettingsRepository.create(context),
            definition = definitions.require(KEY_OPACITY),
            percent = percent
        )
    }

    suspend fun writeOpacityPercent(
        store: NovaSettingsStore,
        definition: NovaSettingDefinition,
        percent: Int
    ) {
        store.set(definition, NovaSettingValue.IntValue(coerceOpacityPercent(percent)))
    }

    fun opacityScale(percent: Int): Float {
        return coerceOpacityPercent(percent) / 100f
    }
}
