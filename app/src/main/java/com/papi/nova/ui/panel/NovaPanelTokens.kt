package com.papi.nova.ui.panel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaInGameOverlayAlpha
import kotlinx.coroutines.launch

/**
 * Sizes, timings and motion for panels, pages and their components.
 *
 * Everything a panel draws takes its numbers from here, so a component never picks its own.
 * Corners stay in NovaRadius; `res/values/nova_tokens.xml` mirrors the ones View code needs.
 */
object NovaPanelMetrics {
    /** The one spacing scale: 4, 8, 12, 16 and 24dp. */
    val SpaceXs: Dp = 4.dp
    val SpaceSm: Dp = 8.dp
    val SpaceMd: Dp = 12.dp
    val SpaceLg: Dp = 16.dp
    val SpaceXl: Dp = 24.dp

    const val StandardWidthFraction = 0.48f
    val StandardWidthMin: Dp = 360.dp
    val StandardWidthMax: Dp = 440.dp
    const val WideWidthFraction = 0.60f
    val WideWidthMin: Dp = 440.dp
    val WideWidthMax: Dp = 560.dp

    /** Below this window width a panel becomes the portrait sheet, whatever the orientation. */
    val SheetBelowWidth: Dp = 480.dp
    const val SheetMaxHeightFraction = 0.88f

    val RowMinHeight: Dp = 52.dp
    val RowMinHeightTv: Dp = 56.dp
    val RowGap: Dp = 6.dp
    val PanelPadding: Dp = 16.dp
    val PanelPaddingTv: Dp = 20.dp
    /** TV title-safe padding on a panel's outer screen edge, and at top and bottom. */
    val TvSafeHorizontal: Dp = 48.dp
    val TvSafeVertical: Dp = 27.dp

    val FocusRingWidth: Dp = 3.dp
    /** The hint bar's tint over the panel, before the menu opacity setting. */
    const val HintBarAlpha = 0.86f
    /** Top and bottom padding of a hint's key chip. */
    val HintChipPadding: Dp = 2.dp
    /**
     * A value beside a row's title leaves the title at least this share of the row; a value
     * that needs more goes under the title instead of squeezing it.
     */
    const val TitleShare = 0.4f
    val Hairline: Dp = 1.dp
    val IconSize: Dp = 20.dp
    val CurrentMarkSize: Dp = 18.dp
    /** The smallest area a cycler arrow answers to. */
    val ArrowTarget: Dp = 48.dp
    val SplitHalfMinWidth: Dp = 96.dp
    val SplitGap: Dp = 6.dp
    val StateColumnMaxWidth: Dp = 480.dp
    /** A segment of a segmented value row; with the track it keeps the row near the 52dp scale. */
    val SegmentMinHeight: Dp = 44.dp
    val ButtonMinHeight: Dp = 44.dp
    val TileMinHeight: Dp = 72.dp
    val SwitchTrackWidth: Dp = 40.dp
    val SwitchTrackHeight: Dp = 24.dp
    val SwitchThumb: Dp = 18.dp
    val SliderTrackHeight: Dp = 6.dp
    val ProgressSize: Dp = 36.dp
    val ProgressStroke: Dp = 3.dp

    const val FocusMillis = 150
    /** Dragging a panel toward its edge past this share of its width dismisses it. */
    const val DismissFraction = 0.58f
    val PagePushOffset: Dp = 24.dp
    const val PageFadeMillis = 180
    const val SplitMillis = 160

    /** The armed destructive half ignores activation for this long. */
    const val SplitGuardMillis = 400L
    const val BusyShowDelayMillis = 300L
    const val BusyMinimumMillis = 500L
    const val HostFocusTimeoutMillis = 500L
    /** Frames a state page keeps asking for focus while whatever it covers settles. */
    const val StateFocusAttempts = 4
    /** A held stepper key moves [StepperAcceleratedSteps] steps at a time after this many repeats. */
    const val StepperAccelerateAfterRepeats = 8
    const val StepperAcceleratedSteps = 5

    const val ScreenScrimAlpha = 0.56f
    const val StreamScrimAlpha = NovaInGameOverlayAlpha.CommandCenterScrim
    const val StatePageAlpha = 0.94f
    const val DisabledAlpha = 0.45f

    /** Auto picks a segmented control for at most this many options ... */
    const val SegmentedMaxOptions = 4
    /** ... whose labels add up to at most this many characters. */
    const val SegmentedMaxLabelChars = 28

    /** A panel's width for [width] in a host window [windowWidth] wide. */
    fun panelWidth(width: NovaPanelWidth, windowWidth: Dp): Dp = when (width) {
        NovaPanelWidth.Standard -> (windowWidth * StandardWidthFraction).coerceIn(StandardWidthMin, StandardWidthMax)
        NovaPanelWidth.Wide -> (windowWidth * WideWidthFraction).coerceIn(WideWidthMin, WideWidthMax)
    }

    /** Whether a window this size shows panels as the portrait sheet rather than at an edge. */
    fun usesSheet(windowWidth: Dp, windowHeight: Dp): Boolean =
        windowWidth <= windowHeight || windowWidth < SheetBelowWidth

    fun rowMinHeight(formFactor: NovaFormFactor): Dp =
        if (formFactor == NovaFormFactor.Television) RowMinHeightTv else RowMinHeight

    fun panelPadding(formFactor: NovaFormFactor): Dp =
        if (formFactor == NovaFormFactor.Television) PanelPaddingTv else PanelPadding
}

/** Geometry of the small theme preview, kept with the other tokens rather than at its call site. */
internal object NovaSwatchMetrics {
    val Width: Dp = 72.dp
    val WindowWidth: Dp = 30.dp
    val WindowHeight: Dp = 22.dp
    val SurfaceWidth: Dp = 17.dp
    val SurfaceHeight: Dp = 10.dp
    val Dot: Dp = 9.dp
    val BarWidth: Dp = 18.dp
    val BarHeight: Dp = 4.dp
    const val BarAlpha = 0.42f
}

/** The panel type scale. On a television every size is 2sp larger. */
@Immutable
class NovaPanelType internal constructor(bump: TextUnit) {
    val panelTitle = TextStyle(fontSize = 20.sp + bump, fontWeight = FontWeight.SemiBold)
    val pageTitle = TextStyle(fontSize = 18.sp + bump, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = 16.sp + bump, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 13.sp + bump)
    val value = TextStyle(fontSize = 15.sp + bump, fontWeight = FontWeight.SemiBold)
    val sectionLabel = NovaChromeType.label(fontSize = 11.sp + bump)
    val stateTitle = TextStyle(fontSize = 24.sp + bump, fontWeight = FontWeight.Bold)
    val code = NovaChromeType.code(fontSize = 40.sp + bump)

    companion object {
        internal val Handheld = NovaPanelType(0.sp)
        internal val Television = NovaPanelType(2.sp)
    }
}

private operator fun TextUnit.plus(other: TextUnit): TextUnit = (value + other.value).sp

/** The panel type scale for the current form factor. */
val novaPanelType: NovaPanelType
    @Composable @ReadOnlyComposable
    get() = if (LocalNovaFormFactor.current == NovaFormFactor.Television) {
        NovaPanelType.Television
    } else {
        NovaPanelType.Handheld
    }

/**
 * The one focus look: [focusedFill] behind the content and a 3dp [ring] drawn inside [shape],
 * animated over 150ms, with no scale and no halo.
 *
 * It follows the focus of whatever focus target comes after it in the chain, so it goes before
 * `focusable()` or [novaClickable]. At rest it draws [restFill] and an optional hairline.
 * Unspecified colours come from the theme: `focusRing` and `selectedControl`.
 */
fun Modifier.novaFocusRing(
    shape: Shape,
    ring: Color = Color.Unspecified,
    focusedFill: Color = Color.Unspecified,
    restFill: Color = Color.Transparent,
    restBorder: Color = Color.Transparent,
    restBorderWidth: Dp = 0.dp,
): Modifier = this then NovaFocusRingElement(shape, ring, focusedFill, restFill, restBorder, restBorderWidth)

private data class NovaFocusRingElement(
    val shape: Shape,
    val ring: Color,
    val focusedFill: Color,
    val restFill: Color,
    val restBorder: Color,
    val restBorderWidth: Dp,
) : ModifierNodeElement<NovaFocusRingNode>() {
    override fun create() = NovaFocusRingNode(shape, ring, focusedFill, restFill, restBorder, restBorderWidth)

    override fun update(node: NovaFocusRingNode) {
        node.update(shape, ring, focusedFill, restFill, restBorder, restBorderWidth)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "novaFocusRing"
    }
}

private class NovaFocusRingNode(
    private var shape: Shape,
    private var ring: Color,
    private var focusedFill: Color,
    private var restFill: Color,
    private var restBorder: Color,
    private var restBorderWidth: Dp,
) : Modifier.Node(), DrawModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private val progress = Animatable(0f)
    private var focused = false
    private var cachedSize = Size.Unspecified
    private var cachedOutline: Outline? = null
    private var cachedPath: Path? = null

    fun update(shape: Shape, ring: Color, focusedFill: Color, restFill: Color, restBorder: Color, restBorderWidth: Dp) {
        if (shape != this.shape) {
            this.shape = shape
            cachedOutline = null
        }
        this.ring = ring
        this.focusedFill = focusedFill
        this.restFill = restFill
        this.restBorder = restBorder
        this.restBorderWidth = restBorderWidth
        invalidateDraw()
    }

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.hasFocus
        if (now == focused) return
        focused = now
        coroutineScope.launch {
            progress.animateTo(if (now) 1f else 0f, tween(NovaPanelMetrics.FocusMillis))
        }
    }

    override fun ContentDrawScope.draw() {
        val surfaces = currentValueOf(LocalNovaLibrarySurfaces)
        val amount = progress.value
        if (cachedOutline == null || cachedSize != size) {
            val outline = shape.createOutline(size, layoutDirection, this)
            cachedOutline = outline
            cachedPath = Path().apply { addOutline(outline) }
            cachedSize = size
        }
        val outline = cachedOutline!!
        val fill = lerp(restFill, focusedFill.takeOrElse { surfaces.selectedControl }, amount)
        if (fill.alpha > 0f) drawOutline(outline, fill)
        drawContent()
        val width = lerp(restBorderWidth, NovaPanelMetrics.FocusRingWidth, amount).toPx()
        val color = lerp(restBorder, ring.takeOrElse { surfaces.focusRing }, amount)
        if (width > 0f && color.alpha > 0f) {
            val path = cachedPath!!
            // A stroke twice as wide, clipped to the shape, leaves exactly [width] inside it and
            // follows the corners the content is clipped to.
            clipPath(path) { drawPath(path, color, style = Stroke(width * 2f)) }
        }
    }
}
