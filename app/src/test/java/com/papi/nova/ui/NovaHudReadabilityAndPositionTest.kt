package com.papi.nova.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.compose.novaComposeColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHudReadabilityAndPositionTest {
    @Test fun textContrastSurvivesEveryThemeAndPanelOpacity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (theme in listOf("polaris", "portable_chrome", "oled", "miami", "high_contrast", "material_you")) {
            context.getSharedPreferences("nova_prefs", 0).edit().putString("nova_theme", theme).commit()
            val colors = novaComposeColors(context)
            val text = listOf(colors.textPrimary, colors.textSecondary, colors.textMuted, colors.accent,
                Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFF87171))
            for (opacity in listOf(0f, 0.25f, 0.64f, 1f)) {
                for (game in listOf(Color.White, Color.Black)) {
                    val glass = colors.card.copy(alpha = opacity).compositeOver(game)
                    val backing = NovaHudReadability.backing.compositeOver(glass)
                    for (color in text) assertTrue("$theme opacity=$opacity color=$color",
                        NovaHudReadability.contrast(NovaHudReadability.foreground(color), backing) >= 4.5f)
                }
            }
        }
    }

    @Test fun cornersIncludeCutoutBarsAndTvSafeMargins() {
        val bounds = NovaHudPositionBounds.forSurface(1920, 1080, 460, 220,
            48f, 27f, insetLeft = 80, insetTop = 24, insetRight = 12, insetBottom = 32)
        assertEquals(128f to 51f, bounds.fromFractions(0f, 0f))
        assertEquals(1400f to 801f, bounds.fromFractions(1f, 1f))
        assertEquals(0.5f to 0.5f, bounds.toFractions(764f, 426f))
    }

    @Test fun invalidAndOversizedCoordinatesStayFiniteAndBounded() {
        val bounds = NovaHudPositionBounds.forSurface(320, 200, 400, 300, 12f, 12f)
        assertEquals(12f to 12f, bounds.clamp(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(12f to 12f, bounds.fromFractions(1f, 1f))
    }

    @Test fun sampleMinimumUsesZerosAndOnlyTheCurrentWindow() {
        val buffer = NovaHudSparklineBuffer()
        buffer.add(0f)
        repeat(59) { buffer.add(60f) }
        assertEquals(0.0, buffer.lowOnePercent(), 0.0)
        buffer.add(50f)
        assertEquals(50.0, buffer.lowOnePercent(), 0.0)
        buffer.add(Float.NaN)
        assertEquals(60, buffer.snapshot().size)
    }
}
