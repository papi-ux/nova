package com.papi.nova.ui.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.papi.nova.R
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaSwatchMetrics

/** The four colours a theme swatch draws. */
@Immutable
data class NovaThemeSwatchPalette(
    val window: Color,
    val surface: Color,
    val accent: Color,
    val border: Color,
)

/**
 * A small picture of [themeValue]: its window with a card on it, its accent dot and an accent bar.
 *
 * Shared by the host screen's theme page and the Settings theme page, so both show the colours
 * the app actually draws.
 */
@Composable
fun NovaThemeSwatch(themeValue: String, modifier: Modifier = Modifier) {
    val palette = novaThemeSwatchPalette(themeValue)
    val windowShape = RoundedCornerShape(NovaRadius.row)
    Row(
        modifier = modifier.width(NovaSwatchMetrics.Width),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
    ) {
        Box(
            modifier = Modifier
                .size(width = NovaSwatchMetrics.WindowWidth, height = NovaSwatchMetrics.WindowHeight)
                .clip(windowShape)
                .background(palette.window)
                .border(NovaPanelMetrics.Hairline, palette.border, windowShape),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = NovaSwatchMetrics.SurfaceWidth, height = NovaSwatchMetrics.SurfaceHeight)
                    .clip(RoundedCornerShape(NovaRadius.chip))
                    .background(palette.surface),
            )
        }
        Box(
            modifier = Modifier
                .size(NovaSwatchMetrics.Dot)
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(palette.accent),
        )
        Box(
            modifier = Modifier
                .size(width = NovaSwatchMetrics.BarWidth, height = NovaSwatchMetrics.BarHeight)
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(palette.accent.copy(alpha = NovaSwatchMetrics.BarAlpha)),
        )
    }
}

/**
 * The palette [themeValue] draws, read from its resources so the swatch cannot drift from the
 * theme. Material You reads the system palette on Android 12 and later; before that the theme
 * cannot be chosen, and the swatch shows a fixed illustration of it.
 */
@Composable
fun novaThemeSwatchPalette(themeValue: String): NovaThemeSwatchPalette = when (themeValue) {
    NovaThemeManager.THEME_PORTABLE_CHROME -> NovaThemeSwatchPalette(
        window = colorResource(R.color.nova_portable_bg_window),
        surface = colorResource(R.color.nova_portable_bg_card),
        accent = colorResource(R.color.nova_portable_accent),
        border = colorResource(R.color.nova_portable_divider),
    )
    NovaThemeManager.THEME_OLED -> NovaThemeSwatchPalette(
        window = colorResource(R.color.nova_oled_bg_window),
        surface = colorResource(R.color.nova_oled_bg_card),
        accent = colorResource(R.color.nova_oled_accent),
        border = colorResource(R.color.nova_oled_divider),
    )
    NovaThemeManager.THEME_MIAMI -> NovaThemeSwatchPalette(
        window = colorResource(R.color.nova_miami_bg_window),
        surface = colorResource(R.color.nova_miami_bg_card),
        accent = colorResource(R.color.nova_miami_accent),
        border = colorResource(R.color.nova_miami_divider),
    )
    NovaThemeManager.THEME_HIGH_CONTRAST -> NovaThemeSwatchPalette(
        window = colorResource(R.color.nova_hc_bg_window),
        surface = colorResource(R.color.nova_hc_bg_card),
        accent = colorResource(R.color.nova_hc_accent),
        border = colorResource(R.color.nova_hc_divider),
    )
    NovaThemeManager.THEME_MATERIAL_YOU -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        NovaThemeSwatchPalette(
            window = colorResource(android.R.color.system_neutral1_900),
            surface = colorResource(android.R.color.system_neutral1_800),
            accent = colorResource(android.R.color.system_accent1_200),
            border = colorResource(android.R.color.system_neutral2_500),
        )
    } else {
        NovaThemeSwatchPalette(
            window = colorResource(R.color.nova_swatch_material_you_window),
            surface = colorResource(R.color.nova_swatch_material_you_surface),
            accent = colorResource(R.color.nova_swatch_material_you_accent),
            border = colorResource(R.color.nova_swatch_material_you_border),
        )
    }
    else -> NovaThemeSwatchPalette(
        window = colorResource(R.color.nova_bg_window),
        surface = colorResource(R.color.nova_bg_card),
        accent = colorResource(R.color.nova_polaris_accent),
        border = colorResource(R.color.nova_divider),
    )
}
