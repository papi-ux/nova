package com.papi.nova.ui.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.papi.nova.R
import com.papi.nova.ui.NovaMenuOpacityPreview
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.NovaSheetChrome
import com.papi.nova.utils.UiHelper

/**
 * Nova's colour roles for Compose.
 *
 * [destructive], [onDestructive] and [positive] come last and default to [textPrimary], [window]
 * and [textPrimary], so a palette built by hand before they existed still compiles.
 * [novaComposeColors] fills them from the theme.
 *
 * [destructive] is for words and hairlines, and falls back to the text colour where red text
 * would not read. [destructiveFill] is the armed destructive action's fill, always a red that
 * stands out from the panel, with [onDestructiveFill] for its label; they default to the text
 * roles for the same reason as the others.
 */
@Immutable
data class NovaComposeColors(
    val window: Color,
    val card: Color,
    val dialog: Color,
    val badge: Color,
    val divider: Color,
    val accent: Color,
    val accentSurface: Color,
    val warning: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val onAccent: Color,
    val destructive: Color = textPrimary,
    val onDestructive: Color = window,
    /** Something on or healthy, such as an active status chip; checked for contrast like [destructive]. */
    val positive: Color = textPrimary,
    val destructiveFill: Color = destructive,
    val onDestructiveFill: Color = onDestructive,
    /**
     * The accent for words on a tile or the panel, such as a primary button's label at rest:
     * the accent, lifted toward the text colour where it would read under 4.5:1.
     */
    val accentText: Color = accent,
)

/** Handhelds, phones and tablets share one scale; a television reads from further away. */
enum class NovaFormFactor { Handheld, Television }

/** Which form factor the UI is drawn for. Panel sizes and the type scale grow on a television. */
val LocalNovaFormFactor = staticCompositionLocalOf { NovaFormFactor.Handheld }

/**
 * The theme's colour roles, read from [context] outside composition.
 *
 * [NovaComposeTheme] builds its palette here, and so does View code that draws the same look,
 * so the two cannot drift. Destructive is the theme's error colour for text, which falls back to
 * the text colour where it would not read against the card and focused surfaces; the destructive
 * fill is the theme's red whatever that check says, with a label chosen to read on it.
 */
fun novaComposeColors(context: Context): NovaComposeColors {
    val destructive = Color(NovaThemeManager.getErrorColor(context))
    val destructiveFill = Color(NovaThemeManager.getDestructiveFillColor(context))
    return NovaComposeColors(
        window = Color(NovaThemeManager.getWindowBackgroundColor(context)),
        card = Color(NovaThemeManager.getCardBackgroundColor(context)),
        dialog = Color(NovaThemeManager.getDialogBackgroundColor(context)),
        badge = Color(NovaThemeManager.getBadgeBackgroundColor(context)),
        divider = Color(NovaThemeManager.getDividerColor(context)),
        accent = Color(NovaThemeManager.getAccentColor(context)),
        accentSurface = Color(NovaThemeManager.getAccentSurfaceColor(context)),
        warning = Color(ContextCompat.getColor(context, R.color.nova_warning)),
        textPrimary = Color(NovaThemeManager.getTextPrimaryColor(context)),
        textSecondary = Color(NovaThemeManager.getTextSecondaryColor(context)),
        textMuted = Color(NovaThemeManager.getTextMutedColor(context)),
        onAccent = Color(NovaThemeManager.getOnAccentColor(context)),
        destructive = destructive,
        onDestructive = readableOn(destructive),
        positive = Color(NovaThemeManager.getPositiveColor(context)),
        destructiveFill = destructiveFill,
        onDestructiveFill = readableOn(destructiveFill),
        accentText = Color(NovaThemeManager.getAccentTextColor(context)),
    )
}

/**
 * Black or white, whichever contrasts more with [background]. At a relative luminance of 0.179
 * the contrast against black and against white is equal.
 */
private fun readableOn(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(NovaRadius.chip),
    small = RoundedCornerShape(NovaRadius.row),
    medium = RoundedCornerShape(NovaRadius.hero),
    large = RoundedCornerShape(NovaRadius.drawer),
    extraLarge = RoundedCornerShape(NovaRadius.drawer),
)

@Immutable
data class NovaLibrarySurfaces(
    val backgroundScrim: Color,
    val panel: Color,
    val panelBorder: Color,
    val tile: Color,
    val tileBorder: Color,
    val control: Color,
    val selectedControl: Color,
    val focusRing: Color,
    val focusHalo: Color,
    val mediaPlaceholder: Color,
    val mediaScrimTop: Color,
    val mediaScrimBottom: Color,
    val onMedia: Color,
    val onMediaSecondary: Color,
    val focusedArtworkAlpha: Float,
    val focusedArtworkScrim: Color,
    val particlesEnabled: Boolean,
    val particleAlpha: Float
)

private fun defaultNovaComposeColors(): NovaComposeColors {
    return NovaComposeColors(
        window = Color(0xFF1A1A2E),
        card = Color(0xCC232340),
        dialog = Color(0xFF232340),
        badge = Color(0x33687B81),
        divider = Color(0xFF393C51),
        accent = Color(0xFF78A6FF),
        accentSurface = Color(0x1A78A6FF),
        warning = Color(0xFFFBBF24),
        textPrimary = Color(0xFFD4DDE8),
        textSecondary = Color(0xFFA8B0B8),
        textMuted = Color(0xFF7A8E95),
        onAccent = Color(0xFFD4DDE8)
    )
}

val LocalNovaComposeColors = staticCompositionLocalOf {
    defaultNovaComposeColors()
}

val LocalNovaLibrarySurfaces = staticCompositionLocalOf {
    defaultNovaComposeColors().librarySurfaces(NovaThemeManager.THEME_POLARIS)
}

val LocalNovaMenuOpacityScale = staticCompositionLocalOf { 1f }

fun NovaComposeColors.librarySurfaces(
    theme: String,
    menuOpacityScale: Float = 1f
): NovaLibrarySurfaces {
    val opacityScale = menuOpacityScale.coerceIn(0f, 1f)
    val isOled = theme == NovaThemeManager.THEME_OLED
    val isMiami = theme == NovaThemeManager.THEME_MIAMI
    val isPortableChrome = theme == NovaThemeManager.THEME_PORTABLE_CHROME
    val isHighContrast = theme == NovaThemeManager.THEME_HIGH_CONTRAST
    val isMaterialYou = theme == NovaThemeManager.THEME_MATERIAL_YOU
    return NovaLibrarySurfaces(
        backgroundScrim = when {
            isOled -> Color.Transparent
            isMiami -> window.copy(alpha = 0.60f)
            isPortableChrome -> window.copy(alpha = 0.62f)
            isHighContrast -> Color.Black.copy(alpha = 0.72f)
            isMaterialYou -> window.copy(alpha = 0.28f)
            else -> window.copy(alpha = 0.56f)
        },
        panel = when {
            isOled -> dialog.copy(alpha = NovaSheetChrome.OLED_SHEET_GLASS_ALPHA)
            isMiami -> dialog.copy(alpha = NovaSheetChrome.MIAMI_SHEET_GLASS_ALPHA)
            isPortableChrome -> dialog.copy(alpha = NovaSheetChrome.PORTABLE_CHROME_SHEET_GLASS_ALPHA)
            isHighContrast -> dialog.copy(alpha = NovaSheetChrome.HIGH_CONTRAST_SHEET_GLASS_ALPHA)
            isMaterialYou -> card.copy(alpha = NovaSheetChrome.MATERIAL_YOU_SHEET_GLASS_ALPHA)
            else -> dialog.copy(alpha = NovaSheetChrome.SHEET_GLASS_ALPHA)
        },
        panelBorder = when {
            isOled -> divider.copy(alpha = 0.78f)
            isMiami -> accent.copy(alpha = 0.18f)
            isPortableChrome -> divider.copy(alpha = 0.46f)
            isHighContrast -> divider.copy(alpha = 0.92f)
            isMaterialYou -> divider.copy(alpha = 0.46f)
            else -> divider.copy(alpha = 0.44f)
        },
        tile = when {
            isOled -> card.copy(alpha = 0.82f)
            isMiami -> card.copy(alpha = 0.70f)
            isPortableChrome -> card.copy(alpha = 0.62f)
            isHighContrast -> card.copy(alpha = 0.98f)
            isMaterialYou -> card.copy(alpha = 0.68f)
            else -> card.copy(alpha = 0.66f)
        },
        tileBorder = when {
            isOled -> divider.copy(alpha = 0.78f)
            isMiami -> divider.copy(alpha = 0.58f)
            isPortableChrome -> divider.copy(alpha = 0.46f)
            isHighContrast -> divider.copy(alpha = 0.90f)
            else -> divider.copy(alpha = 0.50f)
        },
        control = when {
            isOled -> card.copy(alpha = 0.74f)
            isMiami -> card.copy(alpha = 0.64f)
            isPortableChrome -> card.copy(alpha = 0.58f)
            isHighContrast -> card.copy(alpha = 1f)
            isMaterialYou -> card.copy(alpha = 0.62f)
            else -> card.copy(alpha = 0.60f)
        },
        selectedControl = accent.copy(alpha = when {
            isHighContrast -> 0.34f
            isMiami -> 0.22f
            isPortableChrome -> 0.22f
            isOled -> 0.22f
            else -> 0.18f
        }),
        focusRing = accent,
        focusHalo = accent.copy(alpha = when {
            isHighContrast -> 0.36f
            isMiami -> 0.28f
            isPortableChrome -> 0.24f
            isOled -> 0.24f
            else -> 0.18f
        }),
        mediaPlaceholder = when {
            isOled -> Color(0xFF08080C)
            isMiami -> Color(0xFF2C1734)
            isPortableChrome -> Color(0xFF101216)
            isHighContrast -> Color(0xFF111827)
            isMaterialYou -> card.copy(alpha = 1f)
            else -> divider.copy(alpha = 1f)
        },
        mediaScrimTop = Color.Transparent,
        mediaScrimBottom = Color.Black.copy(alpha = when {
            isOled -> 0.88f
            isHighContrast -> 0.92f
            else -> 0.84f
        }),
        onMedia = Color.White,
        onMediaSecondary = Color.White.copy(alpha = 0.86f),
        focusedArtworkAlpha = when {
            isOled -> 0.10f
            isMiami -> 0.26f
            isPortableChrome -> 0.24f
            isMaterialYou -> 0.18f
            else -> 0.24f
        },
        focusedArtworkScrim = Color.Black.copy(alpha = when {
            isOled -> 0.82f
            isMiami -> 0.76f
            isPortableChrome -> 0.78f
            else -> 0.72f
        }),
        particlesEnabled = !isOled,
        particleAlpha = when {
            isOled -> 0f
            isHighContrast -> 0.28f
            isMiami -> 0.68f
            isPortableChrome -> 0.44f
            isMaterialYou -> 0.42f
            else -> 1f
        }
    ).let { surfaces ->
        val readabilityBackdrop = if (textPrimary.luminance() >= 0.5f) Color.Black else Color.White
        val readableScrimColor = lerp(
            surfaces.backgroundScrim,
            readabilityBackdrop,
            1f - opacityScale
        )
        surfaces.copy(
            backgroundScrim = readableScrimColor.copy(
                alpha = NovaMenuPreferences.readabilityScrimAlpha(
                    baseAlpha = surfaces.backgroundScrim.alpha,
                    opacityScale = opacityScale,
                    usesDarkText = textPrimary.luminance() < 0.5f
                )
            ),
            panel = surfaces.panel.copy(
                alpha = NovaMenuPreferences.outerSurfaceAlpha(
                    opacityScale = opacityScale,
                    usesDarkText = textPrimary.luminance() < 0.5f
                )
            ),
            panelBorder = surfaces.panelBorder.copy(alpha = surfaces.panelBorder.alpha * opacityScale),
            tile = surfaces.tile.copy(alpha = surfaces.tile.alpha * opacityScale),
            tileBorder = surfaces.tileBorder.copy(alpha = surfaces.tileBorder.alpha * opacityScale),
            control = surfaces.control.copy(alpha = surfaces.control.alpha * opacityScale),
            selectedControl = surfaces.selectedControl.copy(alpha = surfaces.selectedControl.alpha * opacityScale)
        )
    }
}

@Composable
fun NovaComposeTheme(
    menuOpacityPercent: Int? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val theme = NovaThemeManager.getTheme(context)
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var observedMenuOpacityPercent by remember(prefs) {
        mutableIntStateOf(NovaMenuPreferences.readOpacityPercent(prefs))
    }
    DisposableEffect(prefs, menuOpacityPercent) {
        if (menuOpacityPercent != null) {
            onDispose { }
        } else {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == NovaMenuPreferences.KEY_OPACITY) {
                    observedMenuOpacityPercent = NovaMenuPreferences.readOpacityPercent(prefs)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
    val previewMenuOpacityPercent by NovaMenuOpacityPreview.opacityPercent.collectAsState()
    val resolvedMenuOpacityPercent = menuOpacityPercent
        ?: previewMenuOpacityPercent
        ?: observedMenuOpacityPercent
    val menuOpacityScale = NovaMenuPreferences.opacityScale(resolvedMenuOpacityPercent)
    // The palette reads resources and runs contrast checks, so it is built once per theme and
    // night mode rather than on every recomposition of the tree below.
    val uiMode = LocalConfiguration.current.uiMode
    val colors = remember(context, theme, uiMode) { novaComposeColors(context) }
    val formFactor = remember(context, uiMode) {
        if (UiHelper.isTvDevice(context)) NovaFormFactor.Television else NovaFormFactor.Handheld
    }
    val librarySurfaces = colors.librarySurfaces(theme, menuOpacityScale)

    // Portable Chrome used to be pinned light here -- the only hardcoded light Material
    // scheme in the app. Its shell is graphite now, so the exception is gone and only
    // Material You still follows the system.
    val useDarkColorScheme = when (theme) {
        NovaThemeManager.THEME_MATERIAL_YOU -> isSystemInDarkTheme()
        else -> true
    }
    val colorScheme = (if (useDarkColorScheme) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        background = colors.window,
        onBackground = colors.textPrimary,
        surface = colors.card,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.badge,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.divider,
        error = colors.destructive,
        onError = colors.onDestructive,
        // Every container role is mapped, so no stock M3 piece falls back to the baseline grey.
        surfaceContainerLowest = colors.window,
        surfaceContainerLow = colors.card.compositeOver(colors.window),
        surfaceContainer = colors.dialog.compositeOver(colors.window),
        surfaceContainerHigh = colors.badge.compositeOver(colors.dialog.compositeOver(colors.window)),
        surfaceContainerHighest = colors.badge.compositeOver(
            colors.badge.compositeOver(colors.dialog.compositeOver(colors.window))
        ),
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalNovaComposeColors provides colors,
        LocalNovaLibrarySurfaces provides librarySurfaces,
        LocalNovaMenuOpacityScale provides menuOpacityScale,
        LocalNovaFormFactor provides formFactor,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = NovaShapes,
            content = content
        )
    }
}
