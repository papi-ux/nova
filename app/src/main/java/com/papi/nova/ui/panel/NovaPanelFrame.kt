package com.papi.nova.ui.panel

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaMenuBackdropBlur
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.overStream
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What lies behind a panel: a screen (scrim plus backdrop blur), the stream (a lighter scrim), or nothing. */
enum class NovaScrim { Screen, Stream, None }

/**
 * The side of the window a panel is attached to, after the layout direction: a landscape panel's
 * edge, or the bottom for the portrait sheet.
 */
enum class NovaPanelSide { Left, Right, Bottom }

/**
 * Where a panel's surface sits, the shape that cuts it and the fill it is drawn in. The frame
 * publishes it in the panel's semantics, so the visual gate checks R6 (attached to its edge,
 * rounded on the inner edge only) against the shape that is drawn rather than against a copy of
 * the rule, and a test reads the floor over the stream from the fill that is drawn.
 */
@Immutable
class NovaPanelPlacement(val side: NovaPanelSide, val shape: Shape, val fill: Color = Color.Unspecified)

/** The [NovaPanelPlacement] of a panel's surface. */
val NovaPanelPlacementKey = SemanticsPropertyKey<NovaPanelPlacement>("NovaPanelPlacement")
var SemanticsPropertyReceiver.novaPanelPlacement by NovaPanelPlacementKey

/**
 * The container every panel is drawn in.
 *
 * In landscape the panel is attached to [edge] at full height, [width] wide, rounded on its inner
 * edge only, with a hairline there and no shadow. When the window is portrait or narrower than
 * 480dp it becomes a full-width sheet attached to the bottom, rounded on top, wrapping its content
 * up to 88% of the height and animating its height as pages change.
 *
 * [open] drives the slide in and out on the Command Center's spring. A tap on the scrim, or a drag
 * toward the edge past [NovaPanelMetrics.DismissFraction], calls [onDismissRequest], except while
 * a state page covers the panel ([LocalNovaPanelCovered]); [onClosed] runs when the exit motion
 * lands. Content is padded by the safe drawing insets, the keyboard and, on a television, the
 * title-safe area, so nothing is cut by the screen. The frame is a panel host: its content is
 * drawn at the panel density for the window ([NovaPanelDensityHost]).
 *
 * [overStream] is for a panel in the stream's own window ([NovaWindowPlacement.overStream]):
 * nothing blurs the stream's video surface, so its fill keeps a floor the game's own text cannot
 * read through, whoever opened it. A panel anywhere else, Play Setup over the game page and the
 * Command Center on a companion display included, keeps the glass Menu Opacity chose, even where
 * its scrim is the light [NovaScrim.Stream].
 */
@Composable
fun NovaPanelFrame(
    edge: NovaEdge,
    width: NovaPanelWidth,
    open: Boolean,
    onDismissRequest: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    scrim: NovaScrim = NovaScrim.Screen,
    overStream: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (scrim == NovaScrim.Screen) NovaMenuBackdropBlur()
    val progress = remember { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    val closed by rememberUpdatedState(onClosed)
    val openNow by rememberUpdatedState(open)
    // A state page above the panel owns every touch, even while a Busy page waits to show.
    val coveredNow by rememberUpdatedState(LocalNovaPanelCovered.current)
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, PanelSpring)
        if (!open) closed()
    }
    // One collector follows the finger, conflated to the frame rate.
    val dragging = remember { mutableStateOf(false) }
    val dragProgress = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { if (dragging.value) dragProgress.floatValue else Float.NaN }
            .collect { target -> if (!target.isNaN()) progress.snapTo(target) }
    }
    val drag = remember(scope) {
        PanelDrag(
            begin = {
                // A finger on a closing panel never stops its exit, which is what lets the window go.
                if (openNow && !coveredNow) {
                    dragProgress.floatValue = progress.value
                    dragging.value = true
                }
            },
            move = { delta ->
                if (dragging.value) dragProgress.floatValue = (dragProgress.floatValue + delta).coerceIn(0f, 1f)
            },
            end = {
                if (dragging.value) {
                    dragging.value = false
                    when {
                        // Closed under the finger (B, Start): the drag stopped the exit, so finish it.
                        !openNow -> scope.launch {
                            progress.animateTo(0f, PanelSpring)
                            closed()
                        }
                        progress.value < NovaPanelMetrics.DismissFraction -> dismiss()
                        else -> scope.launch { progress.animateTo(1f, PanelSpring) }
                    }
                }
            },
        )
    }

    val density = novaWindowPanelDensity()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (scrim != NovaScrim.None) {
            NovaPanelScrim(
                baseAlpha = if (scrim == NovaScrim.Stream) NovaPanelMetrics.StreamScrimAlpha else NovaPanelMetrics.ScreenScrimAlpha,
                progress = { progress.value },
                onTap = { if (!coveredNow) dismiss() },
            )
        }
        val tvSafe = LocalNovaFormFactor.current == NovaFormFactor.Television
        // Over the stream nothing blurs what is behind the panel, so its fill keeps a floor the
        // game's own text cannot read through; the scrim beside it still follows menu opacity.
        // It follows the window, not the scrim: the light scrim alone also meant Play Setup on the
        // game page and the companion display, which kept ignoring Menu Opacity.
        val surfaces = LocalNovaLibrarySurfaces.current
        val panelSurfaces = remember(surfaces, overStream) { if (overStream) surfaces.overStream() else surfaces }
        CompositionLocalProvider(LocalNovaLibrarySurfaces provides panelSurfaces) {
            if (NovaPanelMetrics.usesSheet(maxWidth, maxHeight)) {
                NovaPanelSheet(
                    maxHeight = maxHeight * NovaPanelMetrics.SheetMaxHeightFraction,
                    progress = { progress.value },
                    drag = drag,
                    tvSafe = tvSafe,
                    content = { NovaPanelDensityHost(content) },
                )
            } else {
                NovaEdgePanel(
                    edge = edge,
                    width = NovaPanelMetrics.panelWidth(width, maxWidth, density),
                    progress = { progress.value },
                    drag = drag,
                    tvSafe = tvSafe,
                    content = { NovaPanelDensityHost(content) },
                )
            }
        }
    }
}

private val PanelSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** Drag callbacks in panel progress units: 1 is fully open, 0 fully closed. */
private class PanelDrag(val begin: () -> Unit, val move: (Float) -> Unit, val end: () -> Unit)

@Composable
private fun NovaPanelScrim(baseAlpha: Float, progress: () -> Float, onTap: () -> Unit) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val colors = LocalNovaComposeColors.current
    val alpha = NovaMenuPreferences.readabilityScrimAlpha(
        baseAlpha = baseAlpha,
        opacityScale = LocalNovaMenuOpacityScale.current,
        usesDarkText = colors.textPrimary.luminance() < 0.5f,
    )
    val color = surfaces.backgroundScrim.copy(alpha = alpha)
    val label = stringResource(R.string.nova_panel_close_panel)
    val tap by rememberUpdatedState(onTap)
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            // Read in the draw phase only, so the slide never recomposes.
            .drawBehind { drawRect(color, alpha = progress()) }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    tap()
                    true
                }
            },
    )
}

@Composable
private fun BoxScope.NovaEdgePanel(
    edge: NovaEdge,
    width: Dp,
    progress: () -> Float,
    drag: PanelDrag,
    tvSafe: Boolean,
    content: @Composable () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val animatedWidth by animateDpAsState(width, label = "NovaPanelWidth")
    val widthPx = with(density) { animatedWidth.toPx() }.coerceAtLeast(1f)
    val dragWidthPx by rememberUpdatedState(widthPx)
    val atStart = edge == NovaEdge.Start
    // The side of the screen the panel sits on, after the layout direction.
    val onLeft = atStart != rtl
    val shape = if (atStart) {
        RoundedCornerShape(topEnd = NovaRadius.drawer, bottomEnd = NovaRadius.drawer)
    } else {
        RoundedCornerShape(topStart = NovaRadius.drawer, bottomStart = NovaRadius.drawer)
    }
    val outer = if (atStart) WindowInsetsSides.Start else WindowInsetsSides.End
    val tvPadding = if (tvSafe) {
        Modifier.padding(
            start = if (atStart) NovaPanelMetrics.TvSafeHorizontal else 0.dp,
            end = if (atStart) 0.dp else NovaPanelMetrics.TvSafeHorizontal,
            top = NovaPanelMetrics.TvSafeVertical,
            bottom = NovaPanelMetrics.TvSafeVertical,
        )
    } else {
        Modifier
    }
    val hairline = surfaces.panelBorder
    Box(
        modifier = Modifier
            .align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
            // Absolute: onLeft already accounts for the layout direction.
            .absoluteOffset {
                val shift = ((1f - progress()) * widthPx).roundToInt()
                IntOffset(if (onLeft) -shift else shift, 0)
            }
            .fillMaxHeight()
            .width(animatedWidth)
            .semantics { novaPanelPlacement = NovaPanelPlacement(if (onLeft) NovaPanelSide.Left else NovaPanelSide.Right, shape, surfaces.panel) }
            .clip(shape)
            .background(surfaces.panel)
            .drawWithCache {
                val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                val stroke = Stroke(NovaPanelMetrics.Hairline.toPx() * 2f)
                val corner = NovaRadius.drawer.toPx()
                val left = if (onLeft) size.width - corner else 0f
                onDrawWithContent {
                    drawContent()
                    // The inner edge and its two corners only; the outer edge meets the screen.
                    clipRect(left = left, right = left + corner) { drawPath(path, hairline, style = stroke) }
                }
            }
            .pointerInput(onLeft) {
                detectHorizontalDragGestures(
                    onDragStart = { drag.begin() },
                    onDragCancel = { drag.end() },
                    onDragEnd = { drag.end() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        // Toward the attached edge closes.
                        drag.move((if (onLeft) amount else -amount) / dragWidthPx)
                    },
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(outer + WindowInsetsSides.Vertical))
            .imePadding()
            .then(tvPadding),
    ) {
        content()
    }
}

@Composable
private fun BoxScope.NovaPanelSheet(
    maxHeight: Dp,
    progress: () -> Float,
    drag: PanelDrag,
    tvSafe: Boolean,
    content: @Composable () -> Unit,
) {
    val surfaces = LocalNovaLibrarySurfaces.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = NovaRadius.drawer, topEnd = NovaRadius.drawer)
    val fallbackHeight = with(density) { maxHeight.roundToPx() }
    val heightPx = remember { mutableIntStateOf(0) }
    val hairline = surfaces.panelBorder
    val tvPadding = if (tvSafe) {
        Modifier.padding(horizontal = NovaPanelMetrics.TvSafeHorizontal).padding(bottom = NovaPanelMetrics.TvSafeVertical)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .offset {
                val height = heightPx.intValue.takeIf { it > 0 } ?: fallbackHeight
                IntOffset(0, ((1f - progress()) * height).roundToInt())
            }
            .onSizeChanged { heightPx.intValue = it.height }
            .semantics { novaPanelPlacement = NovaPanelPlacement(NovaPanelSide.Bottom, shape, surfaces.panel) }
            .clip(shape)
            .background(surfaces.panel)
            .drawWithCache {
                val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                val stroke = Stroke(NovaPanelMetrics.Hairline.toPx() * 2f)
                val corner = NovaRadius.drawer.toPx()
                onDrawWithContent {
                    drawContent()
                    clipRect(bottom = corner) { drawPath(path, hairline, style = stroke) }
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag.begin() },
                    onDragCancel = { drag.end() },
                    onDragEnd = { drag.end() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag.move(-amount / heightPx.intValue.coerceAtLeast(1))
                    },
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .imePadding()
            .then(tvPadding)
            .animateContentSize(),
    ) {
        CompositionLocalProvider(LocalNovaPanelFillsHeight provides false) {
            content()
        }
    }
}
