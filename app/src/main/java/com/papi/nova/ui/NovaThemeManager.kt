package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
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
        val isSettings =
            activity is com.papi.nova.preferences.StreamSettings ||
                activity is com.papi.nova.ProfilesActivity ||
                activity is com.papi.nova.EditProfileActivity

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
            DynamicColors.applyToActivityIfAvailable(activity)
        }
        configureSystemBars(activity)
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars(activity: Activity) {
        val window = activity.window
        val surfaceColor = getActivityWindowSurfaceColor(activity)
        val useDarkIcons =
            ColorUtils.calculateContrast(Color.BLACK, surfaceColor) >=
                ColorUtils.calculateContrast(Color.WHITE, surfaceColor)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(surfaceColor)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = surfaceColor
            window.navigationBarColor = surfaceColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
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
        resolveMaterialYouColor(
            context,
            android.R.color.system_neutral1_10,
            android.R.color.system_neutral1_900,
            com.google.android.material.R.attr.colorSurface,
            ContextCompat.getColor(context, R.color.nova_bg_window)
        )

    private fun getMaterialYouCardColor(context: Context): Int =
        resolveMaterialYouColor(
            context,
            android.R.color.system_neutral2_50,
            android.R.color.system_neutral2_800,
            com.google.android.material.R.attr.colorSurfaceVariant,
            ContextCompat.getColor(context, R.color.nova_bg_card)
        )

    private fun getMaterialYouTextPrimaryColor(context: Context): Int =
        resolveMaterialYouColor(
            context,
            android.R.color.system_neutral1_900,
            android.R.color.system_neutral1_50,
            com.google.android.material.R.attr.colorOnSurface,
            ContextCompat.getColor(context, R.color.nova_text_primary)
        )

    private fun getMaterialYouTextSecondaryColor(context: Context): Int =
        resolveMaterialYouColor(
            context,
            android.R.color.system_neutral2_600,
            android.R.color.system_neutral2_200,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(context, R.color.nova_text_secondary)
        )

    private fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

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

    private fun resolveSystemColor(context: Context, colorRes: Int, fallback: Int): Int {
        if (!isMaterialYouAvailable()) {
            return fallback
        }
        return try {
            ContextCompat.getColor(context, colorRes)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun resolveMaterialYouColor(
        context: Context,
        lightColorRes: Int,
        darkColorRes: Int,
        attr: Int,
        fallback: Int,
    ): Int {
        val systemFallback = resolveSystemColor(
            context,
            if (isNightMode(context)) darkColorRes else lightColorRes,
            fallback,
        )
        val dynamicContext = try {
            DynamicColors.wrapContextIfAvailable(context)
        } catch (_: Exception) {
            context
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return systemFallback
        }
        return resolveThemeColor(dynamicContext, attr, systemFallback)
    }

    /** Returns the semantic error/destructive color for the active Android/Nova theme. */
    fun getErrorColor(context: Context): Int {
        val candidate =
            if (isPortableChrome(context)) {
                ContextCompat.getColor(context, R.color.nova_portable_error)
            } else {
                resolveThemeColor(
                    context,
                    android.R.attr.colorError,
                    ContextCompat.getColor(context, R.color.nova_error),
                )
            }
        val window = getWindowBackgroundColor(context)
        val card = ColorUtils.compositeColors(getCardBackgroundColor(context), window)
        val focused = ColorUtils.compositeColors(getAccentSurfaceColor(context), card)
        val readable = ColorUtils.calculateContrast(candidate, card) >= 4.5 &&
            ColorUtils.calculateContrast(candidate, focused) >= 4.5
        return if (readable) candidate else getTextPrimaryColor(context)
    }

    /** Returns the semantic Activity surface used behind system bars and custom window backdrops. */
    fun getActivityWindowSurfaceColor(context: Context): Int =
        resolveThemeColor(
            context,
            android.R.attr.colorBackground,
            getWindowBackgroundColor(context),
        )

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
                getMaterialYouCardColor(context)
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
                getMaterialYouCardColor(context)
            else -> ContextCompat.getColor(context, R.color.nova_dialog_bg)
        }
    }

    /** Returns the correct accent color for the current theme */
    fun getAccentColor(context: Context): Int {
        val theme = getTheme(context)
        if (theme == THEME_MATERIAL_YOU && isMaterialYouAvailable()) {
            return resolveMaterialYouColor(
                context,
                android.R.color.system_accent1_600,
                android.R.color.system_accent1_200,
                android.R.attr.colorPrimary,
                ContextCompat.getColor(context, R.color.nova_accent)
            )
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
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveMaterialYouColor(
                    context,
                    android.R.color.system_accent1_100,
                    android.R.color.system_accent1_700,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    ColorUtils.setAlphaComponent(getAccentColor(context), 0x1A)
                )
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
                resolveMaterialYouColor(
                    context,
                    android.R.color.system_neutral2_200,
                    android.R.color.system_neutral2_600,
                    com.google.android.material.R.attr.colorOutline,
                    ContextCompat.getColor(context, R.color.nova_divider)
                )
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
                getMaterialYouTextPrimaryColor(context)
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
                getMaterialYouTextSecondaryColor(context)
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
                resolveMaterialYouColor(
                    context,
                    android.R.color.system_neutral2_500,
                    android.R.color.system_neutral2_400,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(context, R.color.nova_text_muted)
                )
            else -> ContextCompat.getColor(context, R.color.nova_text_muted)
        }
    }

    /** Returns the correct badge/surface-variant color for the current theme. */
    fun getBadgeBackgroundColor(context: Context): Int {
        return if (isMaterialYou(context) && isMaterialYouAvailable()) {
            getMaterialYouCardColor(context)
        } else {
            ContextCompat.getColor(context, R.color.nova_badge_bg)
        }
    }

    /** Returns a readable foreground for primary/accent controls. */
    fun getOnAccentColor(context: Context): Int {
        return when {
            isMaterialYou(context) && isMaterialYouAvailable() ->
                resolveMaterialYouColor(
                    context,
                    android.R.color.system_neutral1_10,
                    android.R.color.system_neutral1_900,
                    com.google.android.material.R.attr.colorOnPrimary,
                    ContextCompat.getColor(context, R.color.nova_bg_window)
                )
            else -> resolveThemeColor(
                context,
                com.google.android.material.R.attr.colorOnPrimary,
                ContextCompat.getColor(context, R.color.nova_ice),
            )
        }
    }
}
