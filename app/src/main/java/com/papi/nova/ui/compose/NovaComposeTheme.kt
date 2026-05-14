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

val LocalNovaComposeColors = staticCompositionLocalOf {
    NovaComposeColors(
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

@Composable
fun NovaComposeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = NovaComposeColors(
        window = Color(NovaThemeManager.getWindowBackgroundColor(context)),
        card = Color(NovaThemeManager.getCardBackgroundColor(context)),
        dialog = Color(NovaThemeManager.getDialogBackgroundColor(context)),
        badge = Color(ContextCompat.getColor(context, R.color.nova_badge_bg)),
        divider = Color(NovaThemeManager.getDividerColor(context)),
        accent = Color(NovaThemeManager.getAccentColor(context)),
        accentSurface = Color(ContextCompat.getColor(context, R.color.nova_accent_surface)),
        warning = Color(ContextCompat.getColor(context, R.color.nova_warning)),
        textPrimary = Color(NovaThemeManager.getTextPrimaryColor(context)),
        textSecondary = Color(NovaThemeManager.getTextSecondaryColor(context)),
        textMuted = Color(NovaThemeManager.getTextMutedColor(context)),
        onAccent = Color(ContextCompat.getColor(context, R.color.nova_ice))
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalNovaComposeColors provides colors) {
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
