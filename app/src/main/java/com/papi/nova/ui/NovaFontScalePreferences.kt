package com.papi.nova.ui

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.preference.PreferenceManager

object NovaFontScalePreferences {
    const val KEY_SCALE_PERCENT = "nova_ui_font_scale_percent"
    const val DEFAULT_SCALE_PERCENT = 100
    const val MIN_SCALE_PERCENT = 80
    const val MAX_SCALE_PERCENT = 130
    const val SCALE_STEP_PERCENT = 1

    fun readScalePercent(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = runCatching {
            prefs.getInt(KEY_SCALE_PERCENT, DEFAULT_SCALE_PERCENT)
        }.getOrDefault(DEFAULT_SCALE_PERCENT)
        return stored.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)
    }

    fun resolveFontScale(systemFontScale: Float, scalePercent: Int): Float {
        val safeSystemScale = systemFontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safePercent = scalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)
        return safeSystemScale * (safePercent / 100f)
    }

    fun readSystemFontScale(context: Context): Float {
        val fallback = context.applicationContext.resources.configuration.fontScale
        return runCatching {
            Settings.System.getFloat(
                context.contentResolver,
                Settings.System.FONT_SCALE,
                fallback,
            )
        }.getOrDefault(fallback).takeIf { it.isFinite() && it > 0f } ?: 1f
    }

    fun wrapContext(
        context: Context,
        systemFontScale: Float = readSystemFontScale(context),
    ): Context = context.createConfigurationContext(
        fontScaleOverride(resolveFontScale(systemFontScale, readScalePercent(context)))
    )

    /**
     * What a Nova screen overrides: the font scale and nothing else.
     *
     * This was a copy of the whole configuration the screen launched with, font scale changed.
     * An override pins every field it sets, so the orientation and the screen size were pinned
     * too, and a screen that handles its own configuration changes instead of restarting kept
     * resolving resources for the shape it was launched in. The Hosts screen opened upright and
     * then turned on its side inflated its upright layout again, which gave the host list no
     * height at all; launched on its side and turned upright it kept the rail and lost the
     * host's name. Every field left unset follows the device.
     */
    fun fontScaleOverride(fontScale: Float): Configuration =
        Configuration().apply { this.fontScale = fontScale }
}
