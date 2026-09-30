package com.papi.nova.ui.compose

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import com.papi.nova.ui.NovaControlSize
import kotlin.math.roundToInt

private data class NovaUnscaledInterface(
    val density: Density,
    val configuration: Configuration,
    val viewConfiguration: ViewConfiguration,
)

// Nested themes reuse the same device basis rather than multiplying the preference twice.
private val LocalNovaUnscaledInterface = staticCompositionLocalOf<NovaUnscaledInterface?> { null }

/**
 * Resize the modern interface's layout, retaining physical text and minimum touch dimensions.
 * Only composition locals change: device resources, display modes and stream planning keep the
 * original context. Font conversions delegate to the original density to keep Android's nonlinear
 * accessible text scaling, including when a caller supplies its own density.
 */
@Composable
internal fun NovaControlSizeHost(size: NovaControlSize, content: @Composable () -> Unit) {
    val parent = LocalNovaUnscaledInterface.current
    val currentDensity = LocalDensity.current
    val currentConfiguration = LocalConfiguration.current
    val currentViewConfiguration = LocalViewConfiguration.current
    val original = parent ?: remember(currentDensity, currentConfiguration, currentViewConfiguration) {
        NovaUnscaledInterface(currentDensity, currentConfiguration, currentViewConfiguration)
    }
    val scale = size.layoutScale
    val density = remember(original, scale) {
        if (scale == 1f) original.density else NovaControlDensity(original.density, scale)
    }
    val configuration = remember(original, scale) {
        if (scale == 1f) original.configuration else Configuration(original.configuration).apply {
            fun adjusted(value: Int) = if (value > 0) (value / scale).roundToInt().coerceAtLeast(1) else value
            screenWidthDp = adjusted(screenWidthDp)
            screenHeightDp = adjusted(screenHeightDp)
            smallestScreenWidthDp = adjusted(smallestScreenWidthDp)
            if (densityDpi > 0) densityDpi = (densityDpi * scale).roundToInt().coerceAtLeast(1)
            fontScale = density.fontScale
        }
    }
    val viewConfiguration = remember(original, scale) {
        if (scale == 1f) original.viewConfiguration else object : ViewConfiguration by original.viewConfiguration {
            override val minimumTouchTargetSize: DpSize = original.viewConfiguration.minimumTouchTargetSize.let {
                DpSize(it.width / scale, it.height / scale)
            }
        }
    }
    CompositionLocalProvider(
        LocalNovaUnscaledInterface provides original,
        LocalDensity provides density,
        LocalConfiguration provides configuration,
        LocalViewConfiguration provides viewConfiguration,
        content = content,
    )
}

private class NovaControlDensity(private val original: Density, private val scale: Float) : Density {
    override val density = original.density * scale
    override val fontScale = original.fontScale / scale

    override fun TextUnit.toPx(): Float {
        val text = this
        return with(original) { text.toPx() }
    }

    override fun TextUnit.toDp(): Dp {
        val text = this
        return with(original) { text.toDp() } / scale
    }

    override fun Dp.toSp(): TextUnit {
        val dimension = this * scale
        return with(original) { dimension.toSp() }
    }
}
