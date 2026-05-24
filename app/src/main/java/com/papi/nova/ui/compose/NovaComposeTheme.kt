package com.papi.nova.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.papi.nova.R
import com.papi.nova.ui.NovaThemeManager

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
    val onAccent: Color
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
        accent = Color(0xFF7C73FF),
        accentSurface = Color(0x1A7C73FF),
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

fun NovaComposeColors.librarySurfaces(theme: String): NovaLibrarySurfaces {
    val isOled = theme == NovaThemeManager.THEME_OLED
    val isMiami = theme == NovaThemeManager.THEME_MIAMI
    val isHighContrast = theme == NovaThemeManager.THEME_HIGH_CONTRAST
    val isMaterialYou = theme == NovaThemeManager.THEME_MATERIAL_YOU
    return NovaLibrarySurfaces(
        backgroundScrim = when {
            isOled -> Color.Transparent
            isMiami -> window.copy(alpha = 0.60f)
            isHighContrast -> Color.Black.copy(alpha = 0.72f)
            isMaterialYou -> window.copy(alpha = 0.28f)
            else -> window.copy(alpha = 0.56f)
        },
        panel = when {
            isOled -> dialog.copy(alpha = 0.88f)
            isMiami -> dialog.copy(alpha = 0.82f)
            isHighContrast -> dialog.copy(alpha = 0.96f)
            isMaterialYou -> card.copy(alpha = 0.76f)
            else -> dialog.copy(alpha = 0.64f)
        },
        panelBorder = when {
            isOled -> divider.copy(alpha = 0.78f)
            isMiami -> accent.copy(alpha = 0.18f)
            isHighContrast -> divider.copy(alpha = 0.92f)
            isMaterialYou -> divider.copy(alpha = 0.46f)
            else -> divider.copy(alpha = 0.44f)
        },
        tile = when {
            isOled -> card.copy(alpha = 0.90f)
            isMiami -> card.copy(alpha = 0.82f)
            isHighContrast -> card.copy(alpha = 0.98f)
            isMaterialYou -> card.copy(alpha = 0.78f)
            else -> card.copy(alpha = 0.74f)
        },
        tileBorder = when {
            isOled -> divider.copy(alpha = 0.78f)
            isMiami -> divider.copy(alpha = 0.58f)
            isHighContrast -> divider.copy(alpha = 0.90f)
            else -> divider.copy(alpha = 0.50f)
        },
        control = when {
            isOled -> card.copy(alpha = 0.78f)
            isMiami -> card.copy(alpha = 0.76f)
            isHighContrast -> card.copy(alpha = 1f)
            isMaterialYou -> card.copy(alpha = 0.70f)
            else -> card.copy(alpha = 0.72f)
        },
        selectedControl = accent.copy(alpha = when {
            isHighContrast -> 0.34f
            isMiami -> 0.22f
            isOled -> 0.22f
            else -> 0.18f
        }),
        focusRing = accent,
        focusHalo = accent.copy(alpha = when {
            isHighContrast -> 0.36f
            isMiami -> 0.28f
            isOled -> 0.24f
            else -> 0.18f
        }),
        mediaPlaceholder = when {
            isOled -> Color(0xFF08080C)
            isMiami -> Color(0xFF2C1734)
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
            isMaterialYou -> 0.18f
            else -> 0.24f
        },
        focusedArtworkScrim = Color.Black.copy(alpha = when {
            isOled -> 0.82f
            isMiami -> 0.76f
            else -> 0.72f
        }),
        particlesEnabled = !isOled,
        particleAlpha = when {
            isOled -> 0f
            isHighContrast -> 0.28f
            isMiami -> 0.68f
            isMaterialYou -> 0.42f
            else -> 1f
        }
    )
}

@Composable
fun NovaComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val theme = NovaThemeManager.getTheme(context)
    val colors = NovaComposeColors(
        window = Color(NovaThemeManager.getWindowBackgroundColor(context)),
        card = Color(NovaThemeManager.getCardBackgroundColor(context)),
        dialog = Color(NovaThemeManager.getDialogBackgroundColor(context)),
        badge = Color(ContextCompat.getColor(context, R.color.nova_badge_bg)),
        divider = Color(NovaThemeManager.getDividerColor(context)),
        accent = Color(NovaThemeManager.getAccentColor(context)),
        accentSurface = Color(NovaThemeManager.getAccentSurfaceColor(context)),
        warning = Color(ContextCompat.getColor(context, R.color.nova_warning)),
        textPrimary = Color(NovaThemeManager.getTextPrimaryColor(context)),
        textSecondary = Color(NovaThemeManager.getTextSecondaryColor(context)),
        textMuted = Color(NovaThemeManager.getTextMutedColor(context)),
        onAccent = Color(
            ContextCompat.getColor(
                context,
                if (theme == NovaThemeManager.THEME_MIAMI) R.color.nova_miami_void else R.color.nova_ice
            )
        )
    )
    val librarySurfaces = colors.librarySurfaces(theme)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalNovaComposeColors provides colors,
        LocalNovaLibrarySurfaces provides librarySurfaces
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                background = colors.window,
                onBackground = colors.textPrimary,
                surface = colors.card,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.badge,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.divider,
                error = colors.warning
            ),
            content = content
        )
    }
}
