package com.papi.nova.ui.panel

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
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
 * How tightly panels are drawn. [Compact] is for a landscape handheld whose window is under
 * [NovaPanelMetrics.CompactBelowHeight] tall, such as the RP6 at 833 x 468dp: smaller type, 44dp
 * rows 4dp apart, 12dp padding and a one line page header, so a short screen shows whole pages.
 * [Regular] is the scale for televisions and taller windows.
 */
enum class NovaPanelDensity { Regular, Compact }

/**
 * The density of the panel being drawn, which [novaPanelType] and [NovaPanelMetrics] read. A
 * panel host chooses it through [NovaPanelDensityHost]: the panel frame, a state page and
 * Settings. Outside a host it is [NovaPanelDensity.Regular].
 */
val LocalNovaPanelDensity = staticCompositionLocalOf { NovaPanelDensity.Regular }

/**
 * Draws [content] at the density for this form factor and window, as every panel host does. The
 * height is the window's, from its configuration, so a keyboard coming up never changes the scale;
 * a configuration that leaves the height undefined keeps the regular scale.
 */
@Composable
fun NovaPanelDensityHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNovaPanelDensity provides novaWindowPanelDensity(), content = content)
}

/** The density panels are drawn at in this window, as [NovaPanelDensityHost] chooses it. */
@Composable
@ReadOnlyComposable
internal fun novaWindowPanelDensity(): NovaPanelDensity {
    val height = LocalConfiguration.current.screenHeightDp
    return if (height == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
        NovaPanelDensity.Regular
    } else {
        NovaPanelMetrics.density(LocalNovaFormFactor.current, height.dp)
    }
}

/**
 * Sizes, timings and motion for panels, pages and their components.
 *
 * Everything a panel draws takes its numbers from here, so a component never picks its own. The
 * sizes that follow [LocalNovaPanelDensity] are read in composition, and each has a plain function
 * of the density for code outside it. Corners stay in NovaRadius; `res/values/nova_tokens.xml`
 * mirrors the ones View code needs.
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

    /**
     * A window shorter than this draws its panels [NovaPanelDensity.Compact], unless it is a
     * television's. The RP6 in landscape is 468dp tall.
     */
    val CompactBelowHeight: Dp = 560.dp

    val RowMinHeight: Dp = 52.dp
    val RowMinHeightTv: Dp = 56.dp
    val RowMinHeightCompact: Dp = 44.dp
    val PanelPadding: Dp = 16.dp
    val PanelPaddingTv: Dp = 20.dp
    val PanelPaddingCompact: Dp = 12.dp

    /** The gap between a page's rows: 6dp, or 4dp compact. */
    val RowGap: Dp
        @Composable @ReadOnlyComposable
        get() = rowGap(LocalNovaPanelDensity.current)

    /**
     * A page header at the compact density is one line, the root's title or a pushed page's
     * `‹ Title`, at least this tall, so a pushed page's rows start where the root's did.
     */
    val HeaderHeightCompact: Dp = 40.dp
    /** TV title-safe padding on a panel's outer screen edge, and at top and bottom. */
    val TvSafeHorizontal: Dp = 48.dp
    val TvSafeVertical: Dp = 27.dp

    val FocusRingWidth: Dp = 3.dp
    /**
     * How far a scrolling list's edge fades while more lies past it. Half a compact row: the
     * focused row keeps a whole row of context between it and the edge, so the fade only ever
     * covers the row beyond.
     */
    val EdgeFade: Dp = 24.dp
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
    /**
     * The height of a value row's control: segments, cycler arrows. With 4dp above and below, a
     * value row is as tall as a plain row, 52dp or 44dp compact, and the arrows keep their 48dp
     * wide target at either density.
     */
    val ValueControlHeight: Dp
        @Composable @ReadOnlyComposable
        get() = valueControlHeight(LocalNovaPanelDensity.current)

    /** A segment of a segmented value row, as tall as the control. */
    val SegmentMinHeight: Dp
        @Composable @ReadOnlyComposable
        get() = ValueControlHeight

    /** A button on a panel page or a state page: 44dp, or 40dp compact. */
    val ButtonMinHeight: Dp
        @Composable @ReadOnlyComposable
        get() = buttonMinHeight(LocalNovaPanelDensity.current)

    val TileMinHeight: Dp = 72.dp
    val SwitchTrackWidth: Dp = 40.dp
    val SwitchTrackHeight: Dp = 24.dp
    val SwitchThumb: Dp = 18.dp
    val SliderTrackHeight: Dp = 6.dp
    val ProgressSize: Dp = 36.dp
    val ProgressStroke: Dp = 3.dp
    /** The still busy mark inside a panel: an arc from the top, three quarters round. */
    const val BusyArcStart = -90f
    const val BusyArcSweep = 270f

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

    /** A status chip's fill: its tone at this alpha, under a label in the tone itself. */
    const val ToneChipFillAlpha = 0.20f
    /** An inactive status chip's fill, a step quieter than [ToneChipFillAlpha]. */
    const val QuietChipFillAlpha = 0.16f

    const val ScreenScrimAlpha = 0.56f
    const val StreamScrimAlpha = NovaInGameOverlayAlpha.CommandCenterScrim
    const val StatePageAlpha = 0.94f
    const val DisabledAlpha = 0.45f

    /** Auto picks a segmented control for at most this many options ... */
    const val SegmentedMaxOptions = 4
    /** ... whose labels add up to at most this many characters. */
    const val SegmentedMaxLabelChars = 28

    /**
     * A panel's width for [width] in a host window [windowWidth] wide, drawn at [density]: a Grid
     * page is Wide where it has two columns, on a compact panel, and Standard elsewhere.
     */
    fun panelWidth(width: NovaPanelWidth, windowWidth: Dp, density: NovaPanelDensity = NovaPanelDensity.Regular): Dp = when (width) {
        NovaPanelWidth.Standard -> (windowWidth * StandardWidthFraction).coerceIn(StandardWidthMin, StandardWidthMax)
        NovaPanelWidth.Wide -> (windowWidth * WideWidthFraction).coerceIn(WideWidthMin, WideWidthMax)
        NovaPanelWidth.Grid -> panelWidth(
            if (density == NovaPanelDensity.Compact) NovaPanelWidth.Wide else NovaPanelWidth.Standard,
            windowWidth,
        )
    }

    /** Whether a window this size shows panels as the portrait sheet rather than at an edge. */
    fun usesSheet(windowWidth: Dp, windowHeight: Dp): Boolean =
        windowWidth <= windowHeight || windowWidth < SheetBelowWidth

    /** The density for a window [windowHeight] tall: compact under [CompactBelowHeight], never on a television. */
    fun density(formFactor: NovaFormFactor, windowHeight: Dp): NovaPanelDensity =
        if (formFactor != NovaFormFactor.Television && windowHeight < CompactBelowHeight) {
            NovaPanelDensity.Compact
        } else {
            NovaPanelDensity.Regular
        }

    /** A row's least height on [formFactor] at the density in effect. */
    @Composable
    @ReadOnlyComposable
    fun rowMinHeight(formFactor: NovaFormFactor): Dp = rowMinHeight(formFactor, LocalNovaPanelDensity.current)

    fun rowMinHeight(formFactor: NovaFormFactor, density: NovaPanelDensity): Dp = when {
        formFactor == NovaFormFactor.Television -> RowMinHeightTv
        density == NovaPanelDensity.Compact -> RowMinHeightCompact
        else -> RowMinHeight
    }

    /** A panel's padding on [formFactor] at the density in effect. */
    @Composable
    @ReadOnlyComposable
    fun panelPadding(formFactor: NovaFormFactor): Dp = panelPadding(formFactor, LocalNovaPanelDensity.current)

    fun panelPadding(formFactor: NovaFormFactor, density: NovaPanelDensity): Dp = when {
        formFactor == NovaFormFactor.Television -> PanelPaddingTv
        density == NovaPanelDensity.Compact -> PanelPaddingCompact
        else -> PanelPadding
    }

    fun rowGap(density: NovaPanelDensity): Dp = if (density == NovaPanelDensity.Compact) 4.dp else 6.dp

    fun valueControlHeight(density: NovaPanelDensity): Dp = if (density == NovaPanelDensity.Compact) 36.dp else 44.dp

    fun buttonMinHeight(density: NovaPanelDensity): Dp = if (density == NovaPanelDensity.Compact) 40.dp else 44.dp

    /**
     * Above a page's header: the panel padding, or 4dp at the compact density, where the header's
     * own 40dp line leaves the room above its title.
     */
    fun headerTopPadding(formFactor: NovaFormFactor, density: NovaPanelDensity): Dp =
        if (formFactor != NovaFormFactor.Television && density == NovaPanelDensity.Compact) SpaceXs else panelPadding(formFactor, density)

    /** Above and below the hint bar: the panel padding, or 8dp compact. Its sides keep the panel padding. */
    fun hintBarMargin(formFactor: NovaFormFactor, density: NovaPanelDensity): Dp =
        if (formFactor != NovaFormFactor.Television && density == NovaPanelDensity.Compact) SpaceSm else panelPadding(formFactor, density)
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

/**
 * The panel type scale. On a television every size is 2sp larger. At the compact density the
 * sizes a page's header, rows and hints use step down to 18, 16, 14, 13, 12 and 10sp, and a
 * hint's key reads at 12sp; a state page's title and a pairing code keep their size.
 */
@Immutable
class NovaPanelType private constructor(
    panelTitleSize: TextUnit,
    pageTitleSize: TextUnit,
    rowTitleSize: TextUnit,
    captionSize: TextUnit,
    valueSize: TextUnit,
    sectionLabelSize: TextUnit,
    hintKeySize: TextUnit,
    bump: TextUnit,
) {
    val panelTitle = TextStyle(fontSize = panelTitleSize + bump, fontWeight = FontWeight.SemiBold)
    val pageTitle = TextStyle(fontSize = pageTitleSize + bump, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = rowTitleSize + bump, fontWeight = FontWeight.Medium)
    /** Broken as a paragraph, so a caption's last line is not one word on its own. */
    val caption = TextStyle(fontSize = captionSize + bump, lineBreak = LineBreak.Paragraph)
    val value = TextStyle(fontSize = valueSize + bump, fontWeight = FontWeight.SemiBold)
    val sectionLabel = NovaChromeType.label(fontSize = sectionLabelSize + bump)
    /** A controller hint's key, on its chip in the hint bar. */
    val hintKey = NovaChromeType.label(fontSize = hintKeySize + bump)
    val stateTitle = TextStyle(fontSize = 24.sp + bump, fontWeight = FontWeight.Bold)
    val code = NovaChromeType.code(fontSize = 40.sp + bump)

    companion object {
        internal val Handheld = regular(bump = 0.sp)
        internal val Television = regular(bump = 2.sp)
        internal val Compact = NovaPanelType(
            panelTitleSize = 18.sp,
            pageTitleSize = 16.sp,
            rowTitleSize = 14.sp,
            captionSize = 12.sp,
            valueSize = 13.sp,
            sectionLabelSize = 10.sp,
            hintKeySize = 12.sp,
            bump = 0.sp,
        )

        private fun regular(bump: TextUnit) = NovaPanelType(
            panelTitleSize = 20.sp,
            pageTitleSize = 18.sp,
            rowTitleSize = 16.sp,
            captionSize = 13.sp,
            valueSize = 15.sp,
            sectionLabelSize = 11.sp,
            hintKeySize = 11.sp,
            bump = bump,
        )

        /** The scale on [formFactor] at [density]; a television is never compact. */
        internal fun of(formFactor: NovaFormFactor, density: NovaPanelDensity): NovaPanelType = when {
            formFactor == NovaFormFactor.Television -> Television
            density == NovaPanelDensity.Compact -> Compact
            else -> Handheld
        }
    }
}

private operator fun TextUnit.plus(other: TextUnit): TextUnit = (value + other.value).sp

/** The panel type scale for the current form factor and panel density. */
val novaPanelType: NovaPanelType
    @Composable @ReadOnlyComposable
    get() = NovaPanelType.of(LocalNovaFormFactor.current, LocalNovaPanelDensity.current)

/**
 * The one resting look of a selectable row, in every panel: the theme's tile fill under a 1dp
 * hairline of its tile border, cut to NovaRadius.row. Rows sit [NovaPanelMetrics.RowGap] apart, so
 * a page of rows reads as one stack of tiles, and focus draws the one focus look over it.
 */
@Immutable
class NovaRowRest(val fill: Color, val border: Color, val borderWidth: Dp)

/** True inside a card that is itself a tile, whose rows rest bare so no tile sits in another. */
internal val LocalNovaRowsNested = staticCompositionLocalOf { false }

/** The resting look of a selectable row here: the tile, or nothing inside [NovaNestedRows]. */
val novaRowRest: NovaRowRest
    @Composable @ReadOnlyComposable
    get() = if (LocalNovaRowsNested.current) {
        BareRowRest
    } else {
        LocalNovaLibrarySurfaces.current.let { NovaRowRest(it.tile, it.tileBorder, NovaPanelMetrics.Hairline) }
    }

private val BareRowRest = NovaRowRest(Color.Transparent, Color.Transparent, 0.dp)

/** Draws [content] inside a card that is already the tile: its rows rest bare. */
@Composable
fun NovaNestedRows(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNovaRowsNested provides true, content = content)
}

/** [novaFocusRing] resting as [rest]: the tile and its hairline for a selectable row. */
fun Modifier.novaFocusRing(
    shape: Shape,
    rest: NovaRowRest,
    ring: Color = Color.Unspecified,
    focusedFill: Color = Color.Unspecified,
): Modifier = novaFocusRing(
    shape = shape,
    ring = ring,
    focusedFill = focusedFill,
    restFill = rest.fill,
    restBorder = rest.border,
    restBorderWidth = rest.borderWidth,
)

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
): Modifier = this then NovaFocusRingElement(shape, ring, focusedFill, restFill, restBorder, restBorderWidth) then
    // Everything with the focus look is a row of whatever list it is in, for the list to rest on.
    Modifier.novaTrackedRow()

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
