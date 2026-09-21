package com.papi.nova.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * A description that is cut to [maxLines], and shown in full while its tile is highlighted.
 *
 * Cards and rows hold their text to a line or two so a screen of them lines up, and an ellipsis
 * says there is more. With a controller there was then no way to read the more: nothing opens, and
 * nothing scrolls. A description nobody can finish is not worth printing. So while [highlighted],
 * under the cursor or the current choice, text that does not fit scrolls up slowly inside the
 * room it already has, rests at its end, and returns. Nothing moves when it fits, and the tile
 * keeps its height either way.
 *
 * @param passes how many times it plays while highlighted. A tile under the cursor keeps going
 *   for as long as it is looked at; a card that is only the current choice plays twice and rests.
 */
@Composable
fun NovaRevealingText(
    text: String,
    highlighted: Boolean,
    maxLines: Int,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    passes: Int = Int.MAX_VALUE,
) {
    // Whether the cut text lost anything. Only the cut text can say; it is kept across the swap.
    var overflows by remember(text, maxLines) { mutableStateOf(false) }
    if (!highlighted || !overflows) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            minLines = minLines,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflows = it.hasVisualOverflow },
            modifier = modifier,
        )
        return
    }

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val room = with(density) { (lineHeight * maxLines).toDp() }
    val pixelsPerSecond = with(density) { NOVA_REVEAL_DP_PER_SECOND * this.density }
    LaunchedEffect(text, maxLines) {
        // The distance is known once the full text has been laid out.
        val distance = snapshotFlow { scroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
        var played = 0
        while (played < passes) {
            delay(NOVA_REVEAL_REST_MS)
            scroll.animateScrollTo(distance, tween(novaRevealMillis(distance, pixelsPerSecond), easing = LinearEasing))
            delay(NOVA_REVEAL_REST_MS)
            scroll.animateScrollTo(0, tween(NOVA_REVEAL_RETURN_MS))
            played++
        }
    }
    val edge = with(density) { NOVA_REVEAL_EDGE_DP.dp.toPx() }
    Box(
        modifier = modifier
            .height(room)
            // Lines dissolve at the edge they leave by and the one they arrive at. A hard clip cut
            // letters in half, and left the tops of the next line showing under the last.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (scroll.value > 0) {
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge),
                        size = Size(size.width, edge),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (scroll.value < scroll.maxValue) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startY = size.height - edge,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - edge),
                        size = Size(size.width, edge),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .clipToBounds()
            // Driven from here only: a finger on the text still belongs to whatever the tile is in.
            .verticalScroll(scroll, enabled = false),
    ) {
        Text(text = text, color = color, fontSize = fontSize, lineHeight = lineHeight)
    }
}

/** How long one reveal takes: a reading pace, never so brief that a single hidden line flicks past. */
internal fun novaRevealMillis(distancePx: Int, pixelsPerSecond: Float): Int {
    if (distancePx <= 0 || pixelsPerSecond <= 0f) return NOVA_REVEAL_MIN_MS
    return ((distancePx / pixelsPerSecond) * 1000f).toInt().coerceAtLeast(NOVA_REVEAL_MIN_MS)
}

/** About a line of small text every second and a half. */
private const val NOVA_REVEAL_DP_PER_SECOND = 9f
private const val NOVA_REVEAL_MIN_MS = 1200
private const val NOVA_REVEAL_REST_MS = 1600L
private const val NOVA_REVEAL_RETURN_MS = 420
private const val NOVA_REVEAL_EDGE_DP = 5
