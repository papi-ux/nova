package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.os.Build
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.papi.nova.R

object NovaThemeManager {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_THEME = "nova_theme"

    const val THEME_POLARIS = "polaris"
    const val THEME_OLED = "oled"
    const val THEME_MATERIAL_YOU = "material_you"

    /** Whether Material You is available on this device (Android 12+) */
    fun isMaterialYouAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun applyTheme(activity: Activity) {
        val theme = getTheme(activity)
        val isSettings = activity is com.papi.nova.preferences.StreamSettings

        when {
            theme == THEME_OLED && isSettings -> activity.setTheme(R.style.SettingsTheme_OLED)
            theme == THEME_OLED -> activity.setTheme(R.style.AppTheme_OLED)
            theme == THEME_MATERIAL_YOU && isSettings -> activity.setTheme(R.style.SettingsTheme_MaterialYou)
            theme == THEME_MATERIAL_YOU -> activity.setTheme(R.style.AppTheme_MaterialYou)
            isSettings -> activity.setTheme(R.style.SettingsTheme)
            else -> activity.setTheme(R.style.AppTheme)
        }

        if (theme == THEME_MATERIAL_YOU && isMaterialYouAvailable()) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity)
        }
        applyEdgeToEdge(activity.window)
    }

    @Suppress("DEPRECATION")
    fun applyEdgeToEdge(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    fun getTheme(context: Context): String {
        // Read from default SharedPreferences (where PreferenceFragment writes)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = defaultPrefs.getString(KEY_THEME, null)
        if (theme != null) return theme

        // Fallback: check legacy nova_prefs location
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, THEME_POLARIS) ?: THEME_POLARIS
    }

    fun setTheme(context: Context, theme: String) {
        // Write to both locations for compatibility
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY_THEME, theme).apply()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme).apply()
    }

    fun isOled(context: Context): Boolean = getTheme(context) == THEME_OLED
    fun isMaterialYou(context: Context): Boolean = getTheme(context) == THEME_MATERIAL_YOU

    fun cycleTheme(context: Context): String {
        val next = when (getTheme(context)) {
            THEME_POLARIS -> if (isMaterialYouAvailable()) THEME_MATERIAL_YOU else THEME_OLED
            THEME_MATERIAL_YOU -> THEME_OLED
            else -> THEME_POLARIS
        }
        setTheme(context, next)
        return next
    }

    fun getThemeLabel(context: Context, theme: String = getTheme(context)): String {
        return when (theme) {
            THEME_OLED -> context.getString(R.string.nova_theme_oled_label)
            THEME_MATERIAL_YOU -> context.getString(R.string.nova_theme_material_you_label)
            else -> context.getString(R.string.nova_theme_polaris_label)
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!context.theme.resolveAttribute(attr, typedValue, true)) {
            return fallback
        }

        return when {
            typedValue.resourceId != 0 -> context.getColor(typedValue.resourceId)
            typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> typedValue.data
            else -> fallback
        }
    }

    /** Returns the correct window background color for the current theme */
    fun getWindowBackgroundColor(context: Context): Int {
        return when {
            isOled(context) -> Color.BLACK
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.colorBackground, context.getColor(R.color.nova_bg_window))
            else -> context.getColor(R.color.nova_bg_window)
        }
    }

    /** Apply forward navigation transition (slide in from right) */
    @Suppress("DEPRECATION")
    fun applyForwardTransition(activity: Activity) {
        activity.overridePendingTransition(R.anim.nova_slide_in_right, R.anim.nova_slide_out_left)
    }

    /** Apply back navigation transition (slide in from left) */
    @Suppress("DEPRECATION")
    fun applyBackTransition(activity: Activity) {
        activity.overridePendingTransition(R.anim.nova_slide_in_left, R.anim.nova_slide_out_right)
    }

    /** Apply fade transition (for settings/modals) */
    @Suppress("DEPRECATION")
    fun applyFadeTransition(activity: Activity) {
        activity.overridePendingTransition(R.anim.nova_fade_in, R.anim.nova_fade_out)
    }

    /** Returns the correct card background color for the current theme */
    fun getCardBackgroundColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_bg_card)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, context.getColor(R.color.nova_bg_card))
            else -> context.getColor(R.color.nova_bg_card)
        }
    }

    /** Returns the correct dialog background color for the current theme */
    fun getDialogBackgroundColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_dialog_bg)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, context.getColor(R.color.nova_dialog_bg))
            else -> context.getColor(R.color.nova_dialog_bg)
        }
    }

    /** Returns the correct accent color for the current theme */
    fun getAccentColor(context: Context): Int {
        val theme = getTheme(context)
        if (theme == THEME_MATERIAL_YOU && isMaterialYouAvailable()) {
            // Use system accent (Material You primary)
            return try {
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary))
                val color = ta.getColor(0, context.getColor(R.color.nova_accent))
                ta.recycle()
                color
            } catch (_: Exception) {
                context.getColor(R.color.nova_accent)
            }
        }
        return if (isOled(context)) {
            context.getColor(R.color.nova_oled_accent)
        } else {
            context.getColor(R.color.nova_accent)
        }
    }

    /** Returns the correct divider color for the current theme */
    fun getDividerColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_divider)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorOutline, context.getColor(R.color.nova_divider))
            else -> context.getColor(R.color.nova_divider)
        }
    }

    /** Returns the correct text primary color for the current theme */
    fun getTextPrimaryColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_text_primary)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorPrimary, context.getColor(R.color.nova_text_primary))
            else -> context.getColor(R.color.nova_text_primary)
        }
    }

    /** Returns the correct text secondary color for the current theme */
    fun getTextSecondaryColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_text_secondary)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorSecondary, context.getColor(R.color.nova_text_secondary))
            else -> context.getColor(R.color.nova_text_secondary)
        }
    }

    /** Returns the correct text muted color for the current theme */
    fun getTextMutedColor(context: Context): Int {
        return when {
            isOled(context) -> context.getColor(R.color.nova_oled_text_muted)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorSecondary, context.getColor(R.color.nova_text_muted))
            else -> context.getColor(R.color.nova_text_muted)
        }
    }
}
