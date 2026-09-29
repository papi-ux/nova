package com.papi.nova.ui.panel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.preference.PreferenceManager
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.librarySurfaces
import com.papi.nova.ui.compose.novaComposeColors

/**
 * The Compose look for the View code that remains: cards and buttons that are still Views get
 * the same fill, ring and corners as the Compose components, from the same colour roles.
 */
object NovaViewBridge {
    /** [radius] in pixels for [context]'s display. */
    fun radiusPx(context: Context, radius: Dp): Float = radius.value * context.resources.displayMetrics.density

    /**
     * A card background: the tile fill with a hairline at rest, the focused control fill under
     * focus. Pair it with [focusRing] as the foreground.
     */
    fun cardBackground(context: Context, radius: Dp = NovaRadius.row): Drawable {
        val surfaces = surfaces(context)
        val corner = radiusPx(context, radius)
        val hairline = strokePx(context, NovaPanelMetrics.Hairline)
        return StateListDrawable().apply {
            addState(FOCUSED, rounded(corner, surfaces.selectedControl))
            addState(ANY, rounded(corner, surfaces.tile).apply { setStroke(hairline, surfaces.tileBorder.toArgb()) })
            fadeLikeCompose()
        }
    }

    /** The 3dp focus ring, drawn inside the bounds, and nothing at rest. */
    fun focusRing(context: Context, radius: Dp = NovaRadius.row): Drawable {
        val surfaces = surfaces(context)
        val corner = radiusPx(context, radius)
        val ring = strokePx(context, NovaPanelMetrics.FocusRingWidth)
        return StateListDrawable().apply {
            addState(FOCUSED, rounded(corner, Color.Transparent).apply { setStroke(ring, surfaces.focusRing.toArgb()) })
            addState(ANY, rounded(corner, Color.Transparent))
            fadeLikeCompose()
        }
    }

    /**
     * A View primary button, as NovaActionSurface draws a Compose primary: a tile with a hairline
     * at rest, and the accent fill while what it stands for is about to be pressed, which a host
     * card marks by selecting it while the card holds focus. A host card's Open Library and Wake
     * were flat accent whether or not the card had focus, so nothing showed what A would press.
     */
    fun primaryButton(context: Context, radius: Dp = NovaRadius.hero): Drawable {
        val surfaces = surfaces(context)
        val corner = radiusPx(context, radius)
        val hairline = strokePx(context, NovaPanelMetrics.Hairline)
        val accent = Color(NovaThemeManager.getAccentColor(context))
        return StateListDrawable().apply {
            addState(SELECTED, rounded(corner, accent))
            addState(FOCUSED, rounded(corner, accent))
            addState(ANY, rounded(corner, surfaces.tile).apply { setStroke(hairline, surfaces.tileBorder.toArgb()) })
            fadeLikeCompose()
        }
    }

    /** The label colours for [primaryButton]: the readable accent at rest, onAccent on the fill. */
    fun primaryButtonText(context: Context): ColorStateList {
        val onAccent = NovaThemeManager.getOnAccentColor(context)
        return ColorStateList(
            arrayOf(SELECTED, FOCUSED, ANY),
            intArrayOf(onAccent, onAccent, NovaThemeManager.getAccentTextColor(context)),
        )
    }

    /** Fades between states over the same 150ms as the Compose focus look. */
    private fun StateListDrawable.fadeLikeCompose() {
        setEnterFadeDuration(NovaPanelMetrics.FocusMillis)
        setExitFadeDuration(NovaPanelMetrics.FocusMillis)
    }

    private fun surfaces(context: Context): NovaLibrarySurfaces {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val opacity = NovaMenuPreferences.opacityScale(NovaMenuPreferences.readOpacityPercent(prefs))
        return novaComposeColors(context).librarySurfaces(NovaThemeManager.getTheme(context), opacity)
    }

    private fun rounded(corner: Float, fill: Color) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = corner
        setColor(fill.toArgb())
    }

    private fun strokePx(context: Context, width: Dp): Int =
        radiusPx(context, width).toInt().coerceAtLeast(1)

    private val FOCUSED = intArrayOf(android.R.attr.state_focused)
    private val SELECTED = intArrayOf(android.R.attr.state_selected)
    private val ANY = intArrayOf()
}

/**
 * Sets [content] under [NovaComposeTheme], disposed with the view tree's lifecycle, for a
 * ComposeView that replaces a View inside a View layout.
 */
fun ComposeView.setNovaContent(content: @Composable () -> Unit) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent { NovaComposeTheme(content = content) }
}
