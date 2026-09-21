package com.papi.nova.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * under the cursor or the current choice, text that does not fit moves up a line at a time inside
 * the room it already has, holds each line long enough to read, rests at its end, and returns.
 * A line at a time because a steady crawl through a one line room shows two half lines for most
 * of the trip. Nothing moves when it fits, and the tile keeps its height either way.
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
    fontWeight: FontWeight? = null,
    /** A face, tracking or figure style the size and weight above do not carry. */
    style: TextStyle? = null,
    /**
     * Its turn is over: it played its [passes], or it had nothing hidden to play, which is what
     * the argument says. For a caller that highlights several texts one after another and needs
     * to know when to move on, and whether anything was worth the turn.
     */
    onPlayed: ((revealed: Boolean) -> Unit)? = null,
) {
    // Whether the cut text lost anything. Only the cut text can say; it is kept across the swap.
    var overflows by remember(text, maxLines) { mutableStateOf(false) }
    val textStyle = style ?: LocalTextStyle.current
    // Unbounded text has nothing cut, and no height to scroll inside.
    if (!highlighted || !overflows || maxLines == Int.MAX_VALUE) {
        if (highlighted && onPlayed != null) {
            // Long enough for the layout above to have said whether anything is cut.
            LaunchedEffect(text, maxLines) {
                delay(NOVA_REVEAL_SETTLE_MS)
                if (!overflows || maxLines == Int.MAX_VALUE) onPlayed(false)
            }
        }
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            minLines = minLines,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflows = it.hasVisualOverflow },
            style = textStyle,
            modifier = modifier,
        )
        return
    }

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // A line at a time, then counted. Above a font scale of one Android scales large sizes
    // less than small ones, so two lines converted as one 32sp figure came out shorter than
    // two 16sp lines: the room shrank under the text and everything below it moved.
    val room = with(density) { novaRevealRoom(lineHeight.toDp(), maxLines) }
    val linePx = with(density) { lineHeight.toPx() }
    LaunchedEffect(text, maxLines) {
        // The distance is known once the full text has been laid out.
        val distance = snapshotFlow { scroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
        val stops = novaRevealStops(distance, linePx)
        var played = 0
        while (played < passes) {
            delay(NOVA_REVEAL_REST_MS)
            for (stop in stops) {
                scroll.animateScrollTo(stop, tween(NOVA_REVEAL_STEP_MS, easing = LinearEasing))
                delay(NOVA_REVEAL_LINE_MS)
            }
            scroll.animateScrollTo(0, tween(NOVA_REVEAL_RETURN_MS))
            played++
        }
        onPlayed?.invoke(true)
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
                // Only while it moves. At rest a line sits exactly in the room, and a fade
                // across the top of it would dim the text it was stopped to show.
                if (!scroll.isScrollInProgress) return@drawWithContent
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
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            style = textStyle,
        )
    }
}

/** The height [maxLines] lines stand in: the height of one, that many times. */
internal fun novaRevealRoom(line: Dp, maxLines: Int): Dp = line * maxLines

/**
 * Where the text stops on its way to [distancePx], one line of [linePx] at a time, ending exactly
 * at the end. A last step shorter than a third of a line is folded into the one before it, so the
 * text does not twitch a few pixels to finish.
 */
internal fun novaRevealStops(distancePx: Int, linePx: Float): List<Int> {
    if (distancePx <= 0) return emptyList()
    if (linePx <= 0f || distancePx <= linePx) return listOf(distancePx)
    val stops = mutableListOf<Int>()
    var next = linePx
    while (next < distancePx - linePx / 3f) {
        stops += next.toInt()
        next += linePx
    }
    stops += distancePx
    return stops
}

/** A line slides in quickly and then holds still to be read. */
private const val NOVA_REVEAL_STEP_MS = 320
private const val NOVA_REVEAL_LINE_MS = 1500L
private const val NOVA_REVEAL_REST_MS = 1600L
private const val NOVA_REVEAL_RETURN_MS = 420
private const val NOVA_REVEAL_EDGE_DP = 5
private const val NOVA_REVEAL_SETTLE_MS = 120L
