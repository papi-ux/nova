package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.os.Build
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.papi.nova.R

object NovaThemeManager {

    private const val PREFS_NAME = "nova_prefs"
    private const val KEY_THEME = "nova_theme"

    const val THEME_POLARIS = "polaris"
    const val THEME_PORTABLE_CHROME = "portable_chrome"
    private const val THEME_PSP = "psp"
    const val THEME_OLED = "oled"
    const val THEME_MIAMI = "miami"
    const val THEME_HIGH_CONTRAST = "high_contrast"
    const val THEME_MATERIAL_YOU = "material_you"

    /** Whether Material You is available on this device (Android 12+) */
    fun isMaterialYouAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun applyTheme(activity: Activity) {
        val theme = getTheme(activity)
        val isSettings = activity is com.papi.nova.preferences.StreamSettings

        when {
            theme == THEME_PORTABLE_CHROME && isSettings -> activity.setTheme(R.style.SettingsTheme_PortableChrome)
            theme == THEME_PORTABLE_CHROME -> activity.setTheme(R.style.AppTheme_PortableChrome)
            theme == THEME_OLED && isSettings -> activity.setTheme(R.style.SettingsTheme_OLED)
            theme == THEME_OLED -> activity.setTheme(R.style.AppTheme_OLED)
            theme == THEME_MIAMI && isSettings -> activity.setTheme(R.style.SettingsTheme_Miami)
            theme == THEME_MIAMI -> activity.setTheme(R.style.AppTheme_Miami)
            theme == THEME_HIGH_CONTRAST && isSettings -> activity.setTheme(R.style.SettingsTheme_HighContrast)
            theme == THEME_HIGH_CONTRAST -> activity.setTheme(R.style.AppTheme_HighContrast)
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
        if (theme != null) return normalizeTheme(theme)

        // Fallback: check legacy nova_prefs location
        return normalizeTheme(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME, THEME_POLARIS)
        )
    }

    private fun normalizeTheme(theme: String?): String {
        return when (theme) {
            THEME_POLARIS, THEME_PORTABLE_CHROME, THEME_OLED, THEME_MIAMI, THEME_HIGH_CONTRAST, THEME_MATERIAL_YOU -> theme
            THEME_PSP -> THEME_PORTABLE_CHROME
            else -> THEME_POLARIS
        }
    }

    fun setTheme(context: Context, theme: String) {
        val normalizedTheme = normalizeTheme(theme)
        // Write to both locations for compatibility
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY_THEME, normalizedTheme).apply()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, normalizedTheme).apply()
    }

    fun isPortableChrome(context: Context): Boolean = getTheme(context) == THEME_PORTABLE_CHROME
    fun isOled(context: Context): Boolean = getTheme(context) == THEME_OLED
    fun isMiami(context: Context): Boolean = getTheme(context) == THEME_MIAMI
    fun isHighContrast(context: Context): Boolean = getTheme(context) == THEME_HIGH_CONTRAST
    fun isMaterialYou(context: Context): Boolean = getTheme(context) == THEME_MATERIAL_YOU

    fun cycleTheme(context: Context): String {
        val next = when (getTheme(context)) {
            THEME_POLARIS -> THEME_PORTABLE_CHROME
            THEME_PORTABLE_CHROME -> THEME_OLED
            THEME_OLED -> THEME_MIAMI
            THEME_MIAMI -> THEME_HIGH_CONTRAST
            THEME_HIGH_CONTRAST -> if (isMaterialYouAvailable()) THEME_MATERIAL_YOU else THEME_POLARIS
            THEME_MATERIAL_YOU -> THEME_POLARIS
            else -> THEME_POLARIS
        }
        setTheme(context, next)
        return next
    }

    fun getThemeLabel(context: Context, theme: String = getTheme(context)): String {
        return when (theme) {
            THEME_PORTABLE_CHROME -> context.getString(R.string.nova_theme_portable_chrome_label)
            THEME_OLED -> context.getString(R.string.nova_theme_oled_label)
            THEME_MIAMI -> context.getString(R.string.nova_theme_miami_label)
            THEME_HIGH_CONTRAST -> context.getString(R.string.nova_theme_high_contrast_label)
            THEME_MATERIAL_YOU -> context.getString(R.string.nova_theme_material_you_label)
            else -> context.getString(R.string.nova_theme_polaris_label)
        }
    }

    private fun getMaterialYouSurfaceColor(context: Context): Int =
        resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(context, R.color.nova_bg_window))

    private fun resolveThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!context.theme.resolveAttribute(attr, typedValue, true)) {
            return fallback
        }

        return when {
            typedValue.resourceId != 0 -> ContextCompat.getColor(context, typedValue.resourceId)
            typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> typedValue.data
            else -> fallback
        }
    }

    /** Returns the correct window background color for the current theme */
    fun getWindowBackgroundColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_bg_window)
            isOled(context) -> Color.BLACK
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_bg_window)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_bg_window)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                getMaterialYouSurfaceColor(context)
            else -> ContextCompat.getColor(context, R.color.nova_bg_window)
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
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_bg_card)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_bg_card)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_bg_card)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_bg_card)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(context, R.color.nova_bg_card))
            else -> ContextCompat.getColor(context, R.color.nova_bg_card)
        }
    }

    /** Returns the correct dialog background color for the current theme */
    fun getDialogBackgroundColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_dialog_bg)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_dialog_bg)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_dialog_bg)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_dialog_bg)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorSurface, ContextCompat.getColor(context, R.color.nova_dialog_bg))
            else -> ContextCompat.getColor(context, R.color.nova_dialog_bg)
        }
    }

    /** Returns the correct accent color for the current theme */
    fun getAccentColor(context: Context): Int {
        val theme = getTheme(context)
        if (theme == THEME_MATERIAL_YOU && isMaterialYouAvailable()) {
            // Use system accent (Material You primary)
            return try {
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary))
                val color = ta.getColor(0, ContextCompat.getColor(context, R.color.nova_accent))
                ta.recycle()
                color
            } catch (_: Exception) {
                ContextCompat.getColor(context, R.color.nova_accent)
            }
        }
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_accent)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_accent)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_accent)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_accent)
            else -> ContextCompat.getColor(context, R.color.nova_polaris_accent)
        }
    }

    /** Returns the correct low-emphasis accent surface for the current theme */
    fun getAccentSurfaceColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_accent_surface)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_accent_surface)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_accent_surface)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_accent_surface)
            else -> ContextCompat.getColor(context, R.color.nova_polaris_accent_surface)
        }
    }

    /** Returns the correct divider color for the current theme */
    fun getDividerColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_divider)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_divider)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_divider)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_divider)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, com.google.android.material.R.attr.colorOutline, ContextCompat.getColor(context, R.color.nova_divider))
            else -> ContextCompat.getColor(context, R.color.nova_divider)
        }
    }

    /** Returns the correct text primary color for the current theme */
    fun getTextPrimaryColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_text_primary)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_text_primary)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_text_primary)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_text_primary)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorPrimary, ContextCompat.getColor(context, R.color.nova_text_primary))
            else -> ContextCompat.getColor(context, R.color.nova_text_primary)
        }
    }

    /** Returns the correct text secondary color for the current theme */
    fun getTextSecondaryColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_text_secondary)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_text_secondary)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_text_secondary)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_text_secondary)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorSecondary, ContextCompat.getColor(context, R.color.nova_text_secondary))
            else -> ContextCompat.getColor(context, R.color.nova_text_secondary)
        }
    }

    /** Returns the correct text muted color for the current theme */
    fun getTextMutedColor(context: Context): Int {
        return when {
            isPortableChrome(context) -> ContextCompat.getColor(context, R.color.nova_portable_text_muted)
            isOled(context) -> ContextCompat.getColor(context, R.color.nova_oled_text_muted)
            isMiami(context) -> ContextCompat.getColor(context, R.color.nova_miami_text_muted)
            isHighContrast(context) -> ContextCompat.getColor(context, R.color.nova_hc_text_muted)
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveThemeColor(context, android.R.attr.textColorSecondary, ContextCompat.getColor(context, R.color.nova_text_muted))
            else -> ContextCompat.getColor(context, R.color.nova_text_muted)
        }
    }
}
