package com.papi.nova.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.papi.nova.ui.compose.LocalNovaFormFactor

/**
 * Fades the top and bottom edges of a scrolling list while more of it lies past that edge, so a
 * row the edge cuts reads as more content rather than as broken (R13, and the RP6 walk's first
 * finding). Put it before the list's own scrolling: it fades the viewport, not the content. Only
 * the edges that have more beyond them fade, and whether they do is read while drawing, so the
 * list never recomposes as it scrolls. Pair it with [NovaRowContextScrolling], which every page
 * already has, so the focused row always keeps a row of context between it and the fade.
 */
fun Modifier.novaScrollEdgeFade(state: ScrollableState, band: Dp = NovaPanelMetrics.EdgeFade): Modifier =
    novaEdgeFade(top = { state.canScrollBackward }, bottom = { state.canScrollForward }, band = band)

/**
 * Fades the top [band] of what this draws while [top] says so, and the bottom band while [bottom]
 * does. It erases alpha rather than painting a ground: a panel is translucent, and a solid band
 * would stripe window colour across whatever shows through it.
 */
fun Modifier.novaEdgeFade(top: () -> Boolean, bottom: () -> Boolean, band: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = band.toPx().coerceAtMost(size.height / 2f)
        if (fade <= 0f) return@drawWithContent
        if (top()) {
            drawRect(
                brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - fade,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Scrolls a focused element inside [content] into view with one row of context on the side it
 * scrolls toward, as every page in a [NovaPageStackHost] does, so the row after the focused one
 * is never cut at the list's edge and the edge fade never covers the focused row. For a list
 * outside a page stack, such as the Settings rail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovaRowContextScrolling(content: @Composable () -> Unit) {
    val formFactor = LocalNovaFormFactor.current
    val contextPx = with(LocalDensity.current) {
        (NovaPanelMetrics.rowMinHeight(formFactor) + NovaPanelMetrics.RowGap).toPx()
    }
    val spec = remember(contextPx) { NovaContextBringIntoViewSpec(contextPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Scrolls a focused row into view together with one row of context on the side it scrolls
 * toward, so the row after the focused one is never cut at the list's edge.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class NovaContextBringIntoViewSpec(private val contextPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = minOf(contextPx, ((containerSize - size) / 2f).coerceAtLeast(0f))
        val leading = offset - margin
        val trailing = offset + size + margin
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f -> leading
            else -> trailing - containerSize
        }
    }
}
