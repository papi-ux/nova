package com.papi.nova.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import com.papi.nova.ui.compose.LocalNovaFormFactor
import kotlinx.coroutines.launch

/**
 * Fades the top and bottom edges of a scrolling list while more of it lies past that edge, so a
 * row the edge cuts reads as more content rather than as broken (R13, and the RP6 walk's first
 * finding). Put it before the list's own scrolling: it fades the viewport, not the content. Only
 * the edges that have more beyond them fade, and whether they do is read while drawing, so the
 * list never recomposes as it scrolls. Pair it with [NovaRowContextScrolling], which every page
 * already has, so the focused row always keeps a row of context between it and the fade.
 *
 * Inside [NovaRowContextScrolling] the list also comes to rest on whole rows: once a scroll
 * settles, a row the top edge cuts with more of it showing than a faint sliver is either shown
 * whole or scrolled away, so no sliced line of text is left under the edge. Where the list cannot
 * move that far, at its end, the cut remnant is cleared from the edge instead.
 */
fun Modifier.novaScrollEdgeFade(state: ScrollableState, band: Dp = NovaPanelMetrics.EdgeFade): Modifier =
    novaEdgeFade(top = { state.canScrollBackward }, bottom = { state.canScrollForward }, band = band) then
        NovaScrollRestElement(state, band)

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
 * Scrolls a focused element inside [content] into view with a row of context on the side it
 * scrolls toward, as every page in a [NovaPageStackHost] does, so the row after the focused one is
 * never cut at the list's edge and the edge fade never covers the focused row. For a list outside
 * a page stack, such as the Settings rail.
 *
 * The context is sized by what is really there: the row before or after the focused one, with any
 * section label between, as the rows it tracks were last placed. A row not yet composed is taken as
 * the larger of a least row at this font scale and the focused row itself, plus a section label.
 * The tracked rows also let a list in it come to rest on whole rows ([novaScrollEdgeFade]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovaRowContextScrolling(content: @Composable () -> Unit) {
    val formFactor = LocalNovaFormFactor.current
    val density = LocalDensity.current
    val type = novaPanelType
    val gapPx = with(density) { NovaPanelMetrics.RowGap.toPx() }
    val contextPx = with(density) { NovaPanelMetrics.rowMinHeight(formFactor).toPx() * fontScale.coerceAtLeast(1f) } + gapPx
    // A section label: its line and the padding above and below it.
    val labelPx = with(density) {
        type.sectionLabel.fontSize.toPx() * NovaLabelLineFactor + (NovaPanelMetrics.SpaceSm + NovaPanelMetrics.SpaceXs).toPx()
    }
    val tracker = remember { NovaRowTracker() }
    val spec = remember(contextPx, labelPx, gapPx, tracker) { NovaContextBringIntoViewSpec(contextPx, labelPx, gapPx, tracker) }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides spec,
        LocalNovaRowTracker provides tracker,
        content = content,
    )
}

/** A section label's line height against its size. */
private const val NovaLabelLineFactor = 1.5f

/**
 * Scrolls a focused row into view together with a row of context on the side it scrolls toward: the
 * row before or after it as [tracker] last saw them placed, with any label between. Where that row
 * is not known, [contextPx] or the focused row's own height and [gapPx], whichever is more, and
 * [labelPx] for a label that may stand between. Never more than half the room the row leaves.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class NovaContextBringIntoViewSpec(
    private val contextPx: Float,
    private val labelPx: Float = 0f,
    private val gapPx: Float = 0f,
    private val tracker: NovaRowTracker? = null,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val guess = maxOf(contextPx, size + gapPx) + labelPx
        val around = tracker?.contextAround(size)
        val cap = ((containerSize - size) / 2f).coerceAtLeast(0f)
        val leading = offset - minOf(around?.before ?: guess, cap)
        val trailing = offset + size + minOf(around?.after ?: guess, cap)
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f -> leading
            else -> trailing - containerSize
        }
    }
}

/**
 * The rows the lists inside one [NovaRowContextScrolling] hold, where they were last placed in the
 * window, uncut, and which of them has focus. Every element with the one focus look registers
 * itself ([novaFocusRing]), as does a section label, so a list can tell what its top edge cuts.
 */
internal class NovaRowTracker {
    class Entry(var bounds: Rect, var focused: Boolean, val label: Boolean)

    /** How far the row before the focused one reaches above it, and the row after it below. */
    class Context(val before: Float?, val after: Float?)

    val rows = HashMap<Any, Entry>()

    /**
     * The context around the focused row, [size] tall: from the top of the row before it, and to
     * the bottom of the row after it, labels between included. Null where no focused row that tall
     * is tracked, and either side null where no row is placed there yet.
     */
    fun contextAround(size: Float): Context? {
        val focused = rows.values
            .filter { it.focused && !it.label && kotlin.math.abs(it.bounds.height - size) <= 2f }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?.bounds ?: return null
        val beside = rows.values.filter { it.bounds.left < focused.right && it.bounds.right > focused.left }
        val rowsOnly = beside.filter { !it.label }.map { it.bounds }
        val above = rowsOnly.filter { it.bottom <= focused.top + 1f }.maxByOrNull { it.bottom }
        val below = rowsOnly.filter { it.top >= focused.bottom - 1f }.minByOrNull { it.top }
        return Context(
            before = above?.let { focused.top - it.top },
            after = below?.let { it.bottom - focused.bottom },
        )
    }
}

internal val LocalNovaRowTracker = staticCompositionLocalOf<NovaRowTracker?> { null }

/**
 * Registers this element with the [NovaRowTracker] around it, if there is one, as a row of its list,
 * or with [label] as a section label, which belongs to the row after it.
 */
internal fun Modifier.novaTrackedRow(label: Boolean = false): Modifier =
    this then if (label) NovaTrackedLabelElement else NovaTrackedRowElement

private data object NovaTrackedRowElement : ModifierNodeElement<NovaTrackedRowNode>() {
    override fun create() = NovaTrackedRowNode(label = false)

    override fun update(node: NovaTrackedRowNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "novaTrackedRow"
    }
}

private data object NovaTrackedLabelElement : ModifierNodeElement<NovaTrackedRowNode>() {
    override fun create() = NovaTrackedRowNode(label = true)

    override fun update(node: NovaTrackedRowNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "novaTrackedLabel"
    }
}

private class NovaTrackedRowNode(private val label: Boolean) :
    Modifier.Node(), GlobalPositionAwareModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var tracker: NovaRowTracker? = null
    private var focused = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val tracker = tracker ?: currentValueOf(LocalNovaRowTracker)?.also { tracker = it } ?: return
        val bounds = coordinates.uncutBoundsInWindow()
        val entry = tracker.rows[this]
        if (entry == null) tracker.rows[this] = NovaRowTracker.Entry(bounds, focused, label) else entry.bounds = bounds
    }

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
        tracker?.rows?.get(this)?.focused = focused
    }

    override fun onDetach() {
        tracker?.rows?.remove(this)
        tracker = null
    }
}

private fun LayoutCoordinates.uncutBoundsInWindow(): Rect = Rect(positionInWindow(), size.toSize())

/**
 * Brings a list to rest on whole rows once a scroll settles. A row the top edge cuts, with more of
 * it showing than the edge fade hides, is shown whole when the list last moved back and the
 * focused row still fits, and otherwise scrolled away, so the next row starts at the edge.
 */
private data class NovaScrollRestElement(val state: ScrollableState, val band: Dp) :
    ModifierNodeElement<NovaScrollRestNode>() {
    override fun create() = NovaScrollRestNode(state, band)

    override fun update(node: NovaScrollRestNode) {
        node.update(state, band)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "novaScrollRest"
    }
}

private class NovaScrollRestNode(
    private var state: ScrollableState,
    private var band: Dp,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode, DrawModifierNode {
    private var viewport: Rect? = null

    /** What is left at the top edge of a cut row the list could not scroll past, cleared there. */
    private var remnant = 0f
        set(value) {
            if (field == value) return
            field = value
            if (isAttached) invalidateDraw()
        }

    fun update(state: ScrollableState, band: Dp) {
        val changed = state !== this.state
        this.state = state
        this.band = band
        if (changed && isAttached) watch()
    }

    // Inside the edge fade's layer, before its gradients: the remnant is cleared, then faded below.
    override fun ContentDrawScope.draw() {
        drawContent()
        if (remnant > 0f && state.canScrollBackward) {
            drawRect(
                color = Color.Transparent,
                size = Size(size.width, remnant.coerceAtMost(size.height / 2f)),
                blendMode = BlendMode.Clear,
            )
        }
    }

    override fun onAttach() {
        watch()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        viewport = coordinates.uncutBoundsInWindow()
    }

    private var watching: kotlinx.coroutines.Job? = null

    private fun watch() {
        watching?.cancel()
        val watched = state
        watching = coroutineScope.launch {
            var moved = false
            snapshotFlow { watched.isScrollInProgress }.collect { inProgress ->
                if (inProgress) {
                    moved = true
                    // Moving, the edge fades as it always does; what it cuts is judged at rest.
                    remnant = 0f
                } else if (moved) {
                    moved = false
                    // The rows report where they landed on the frame after the scroll.
                    withFrameNanos { }
                    rest(watched)
                }
            }
        }
    }

    private suspend fun rest(watched: ScrollableState) {
        val tracker = currentValueOf(LocalNovaRowTracker) ?: return
        val view = viewport ?: return
        if (!watched.canScrollBackward) return
        val fade = with(currentValueOf(LocalDensity)) { band.toPx() }
        val top = view.top
        val across = { rect: Rect -> rect.left < view.right && rect.right > view.left }
        val cut = mutableListOf<Rect>()
        tracker.rows.values.forEach { entry ->
            val rect = entry.bounds
            if (across(rect) && rect.top < top - Slack && rect.bottom > top + Slack) cut += rect
        }
        // A lazy list knows its items, labels and headers included, which have no focus look.
        (watched as? LazyListState)?.layoutInfo?.let { info ->
            info.visibleItemsInfo.forEach { item ->
                val itemTop = top + (item.offset - info.viewportStartOffset)
                val rect = Rect(view.left, itemTop, view.right, itemTop + item.size)
                if (rect.top < top - Slack && rect.bottom > top + Slack) cut += rect
            }
        }
        if (cut.isEmpty()) return
        // The innermost element holding focus: a card around the focused row holds it too.
        val focus = tracker.rows.values
            .filter { it.focused && !it.label && across(it.bounds) }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?.bounds
        // Never move past the focused row, and leave a cut that holds it alone.
        if (focus != null && cut.any { it.top <= focus.top + Slack && it.bottom >= focus.bottom - Slack }) return
        val showing = cut.maxOf { it.bottom } - top
        // A sliver in the faintest part of the fade reads as the fade; more than that reads as a
        // sliced line of text.
        if (showing <= fade * FaintShare + Slack) return
        val reveal = top - cut.minOf { it.top }
        val revealFits = focus == null || focus.bottom + reveal <= view.bottom - Slack
        val delta = if (watched.lastScrolledBackward && revealFits) -reveal else showing
        val moved = watched.animateScrollBy(delta)
        // At the list's end it cannot scroll the rest of the way: clear what is left of the row.
        if (delta > 0f && moved < delta - Slack) remnant = delta - moved
    }

    private companion object {
        /** A pixel of rounding either way is not a cut. */
        const val Slack = 1f

        /** The share of the edge fade a cut row may show at rest: its faintest quarter. */
        const val FaintShare = 0.25f
    }
}
