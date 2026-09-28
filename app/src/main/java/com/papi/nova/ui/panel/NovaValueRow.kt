package com.papi.nova.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaFocusTick

/** How a [NovaMenuItem.Value] or value row draws its choice. */
enum class NovaValueStyle { Auto, Segmented, Cycler, Switch }

/**
 * What [NovaValueStyle.Auto] means for [options]: a switch for two Boolean values; segments for up
 * to four options whose labels total 28 characters or fewer; a cycler for everything else.
 */
fun <T> resolveNovaValueStyle(options: List<NovaOption<T>>, style: NovaValueStyle = NovaValueStyle.Auto): NovaValueStyle =
    when {
        style != NovaValueStyle.Auto -> style
        options.size == 2 && options.all { it.value is Boolean } -> NovaValueStyle.Switch
        options.size <= NovaPanelMetrics.SegmentedMaxOptions &&
            options.sumOf { it.label.length } <= NovaPanelMetrics.SegmentedMaxLabelChars -> NovaValueStyle.Segmented
        else -> NovaValueStyle.Cycler
    }

/**
 * The index one step from [index] in [delta]'s direction among the enabled options, or null when
 * an ordered row is already at that end. Unordered rows wrap.
 */
internal fun <T> novaValueStep(options: List<NovaOption<T>>, index: Int, delta: Int, wrap: Boolean): Int? {
    if (options.isEmpty()) return null
    var next = index
    repeat(options.size) {
        next += delta
        if (next !in options.indices) {
            if (!wrap) return null
            next = Math.floorMod(next, options.size)
        }
        if (options[next].enabled) return next.takeIf { it != index }
    }
    return null
}

/**
 * A choice that changes in its own row: segmented, cycler or switch, by [style].
 *
 * The row is one focus stop. Left and Right change the value on key down, repeats included, and
 * never move focus; [ordered] rows stop at their ends with a firmer haptic, unordered rows wrap.
 * A steps forward and wraps. On a switch Left means Off and Right means On. Touch taps a segment,
 * a cycler arrow or the switch; tapping a cycler's value runs [onOpenList]. Every change applies
 * at once through [onChange].
 */
@Composable
fun <T> NovaValueRow(
    title: String,
    options: List<NovaOption<T>>,
    current: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    style: NovaValueStyle = NovaValueStyle.Auto,
    ordered: Boolean = false,
    enabled: Boolean = true,
    onOpenList: (() -> Unit)? = null,
) {
    val resolved = remember(options, style) { resolveNovaValueStyle(options, style) }
    val index = options.indexOfFirst { it.value == current }.coerceAtLeast(0)
    val latestIndex by rememberUpdatedState(index)
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    val isSwitch = resolved == NovaValueStyle.Switch

    fun select(target: Int) {
        if (!enabled || target == latestIndex || !options[target].enabled) return
        haptics.novaFocusTick()
        change(options[target].value)
    }

    fun step(delta: Int, wrap: Boolean) {
        if (!enabled) return
        val target = if (isSwitch) {
            options.indexOfFirst { it.value == (delta > 0) }.takeIf { it >= 0 && it != latestIndex }
        } else {
            novaValueStep(options, latestIndex, delta, wrap)
        }
        if (target == null) {
            if (!wrap) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        } else {
            select(target)
        }
    }

    NovaValueRowFrame(
        title = title,
        caption = caption,
        enabled = enabled,
        stateLabel = options.getOrNull(index)?.label.orEmpty(),
        role = if (isSwitch) Role.Switch else null,
        onLeft = { step(-1, wrap = !ordered) },
        onRight = { step(1, wrap = !ordered) },
        onActivate = { step(1, wrap = true) },
        modifier = modifier,
    ) {
        when (resolved) {
            NovaValueStyle.Switch -> NovaSwitchControl(
                on = options.getOrNull(index)?.value == true,
                onToggle = { step(if (options.getOrNull(latestIndex)?.value == true) -1 else 1, wrap = false) },
            )
            NovaValueStyle.Segmented -> NovaSegmentedControl(options, index, onSelect = ::select)
            else -> NovaCyclerControl(
                label = options.getOrNull(index)?.label.orEmpty(),
                onPrevious = { step(-1, wrap = !ordered) },
                onNext = { step(1, wrap = !ordered) },
                onValueTap = onOpenList,
            )
        }
    }
}

/**
 * A number that steps in its own row, shown as `‹ value ›`.
 *
 * Left and Right step by [step] on key down and stop at the ends of [range]; held, they repeat,
 * and after eight repeats each press moves five steps. A runs [onExact], which pushes a Slider
 * page for an exact value.
 */
@Composable
fun NovaStepperRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
    onExact: (() -> Unit)? = null,
) {
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val exact by rememberUpdatedState(onExact)
    val haptics = LocalHapticFeedback.current

    fun move(direction: Int, repeats: Int) {
        if (!enabled) return
        val steps = if (repeats > NovaPanelMetrics.StepperAccelerateAfterRepeats) NovaPanelMetrics.StepperAcceleratedSteps else 1
        val target = (latest + direction * step * steps).coerceIn(range)
        if (target == latest) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
        } else {
            haptics.novaFocusTick()
            change(target)
        }
    }

    NovaValueRowFrame(
        title = title,
        caption = caption,
        enabled = enabled,
        stateLabel = format(value),
        role = null,
        onLeft = { repeats -> move(-1, repeats) },
        onRight = { repeats -> move(1, repeats) },
        onActivate = { exact?.invoke() },
        modifier = modifier,
    ) {
        NovaCyclerControl(
            label = format(value),
            onPrevious = { move(-1, 0) },
            onNext = { move(1, 0) },
            onValueTap = onExact,
        )
    }
}

/**
 * The shared frame of value and stepper rows: one focus stop that owns Left, Right and A, with the
 * control beside the title, or under it when the row is narrower than 360dp. [onLeft] and
 * [onRight] get the key's repeat count.
 */
@Composable
private fun NovaValueRowFrame(
    title: String,
    caption: String?,
    enabled: Boolean,
    stateLabel: String,
    role: Role?,
    onLeft: (repeats: Int) -> Unit,
    onRight: (repeats: Int) -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val previousLabel = stringResource(R.string.nova_panel_previous)
    val nextLabel = stringResource(R.string.nova_panel_next)
    // Left moves toward the start of the options in either layout direction's visual order.
    val previous = if (rtl) right else left
    val next = if (rtl) left else right

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape)
            .semantics(mergeDescendants = true) {
                stateDescription = stateLabel
                role?.let { this.role = it }
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) {
                        previous(0)
                        true
                    },
                    CustomAccessibilityAction(nextLabel) {
                        next(0)
                        true
                    },
                )
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val direction = when (event.key) {
                    Key.DirectionLeft -> left
                    Key.DirectionRight -> right
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) direction(native.repeatCount)
                // Both halves of the key are the row's; focus never leaves it sideways.
                true
            }
            .novaClickable(enabled = enabled, focusableWhenDisabled = true, onClick = onActivate)
            .alpha(if (enabled) 1f else NovaPanelMetrics.DisabledAlpha)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        contentAlignment = Alignment.CenterStart,
    ) {
        Layout(
            contents = listOf(
                {
                    Column {
                        Text(text = title, style = type.rowTitle, color = colors.textPrimary)
                        caption?.let {
                            Text(text = it, style = type.caption, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                control,
            ),
            measurePolicy = ValueRowMeasurePolicy,
        )
    }
}

/**
 * The control sits beside the title while both fit with the title keeping at least
 * [TitleShare] of the row; otherwise it wraps under the title, so neither is ever squeezed
 * or cut.
 */
private val ValueRowMeasurePolicy = MultiContentMeasurePolicy { (labels, controls), constraints ->
    val label = labels.first()
    val control = controls.first()
    val width = constraints.maxWidth
    val besideGap = NovaPanelMetrics.SpaceMd.roundToPx()
    val stackGap = NovaPanelMetrics.SpaceSm.roundToPx()
    val natural = control.maxIntrinsicWidth(Constraints.Infinity)
    val stacked = constraints.hasBoundedWidth && natural + besideGap > width * (1f - TitleShare)
    if (stacked) {
        val labelPlaceable = label.measure(Constraints(maxWidth = width))
        val controlPlaceable = control.measure(Constraints(maxWidth = width))
        val height = maxOf(labelPlaceable.height + stackGap + controlPlaceable.height, constraints.minHeight)
        layout(width, height) {
            val top = (height - labelPlaceable.height - stackGap - controlPlaceable.height) / 2
            labelPlaceable.placeRelative(0, top)
            controlPlaceable.placeRelative(0, top + labelPlaceable.height + stackGap)
        }
    } else {
        val controlPlaceable = control.measure(Constraints(maxWidth = natural))
        val labelPlaceable = label.measure(Constraints(maxWidth = (width - controlPlaceable.width - besideGap).coerceAtLeast(0)))
        val height = maxOf(labelPlaceable.height, controlPlaceable.height, constraints.minHeight)
        layout(width, height) {
            labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
            controlPlaceable.placeRelative(width - controlPlaceable.width, (height - controlPlaceable.height) / 2)
        }
    }
}

private const val TitleShare = 0.4f

@Composable
private fun <T> NovaSegmentedControl(options: List<NovaOption<T>>, index: Int, onSelect: (Int) -> Unit) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val select by rememberUpdatedState(onSelect)
    Layout(
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.hero))
            .background(surfaces.control)
            .padding(NovaPanelMetrics.SpaceXs),
        measurePolicy = SegmentsMeasurePolicy,
        content = {
            options.forEachIndexed { i, option ->
                val isCurrent = i == index
                Row(
                    modifier = Modifier
                        .heightIn(min = NovaPanelMetrics.SegmentMinHeight)
                        .clip(RoundedCornerShape(NovaRadius.row))
                        .pointerInput(i) { detectTapGestures(onTap = { select(i) }) }
                        .padding(horizontal = NovaPanelMetrics.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs, Alignment.CenterHorizontally),
                ) {
                    if (isCurrent) NovaCurrentMark()
                    Text(
                        text = option.label,
                        style = type.value,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            !option.enabled -> colors.textMuted
                            isCurrent -> colors.textPrimary
                            else -> colors.textSecondary
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    )
}

/**
 * Segments take their natural widths when they fit; when they do not, each shrinks in proportion
 * to its natural width and its label wraps, so the last segment is never cut at the row's edge.
 */
private val SegmentsMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val gap = NovaPanelMetrics.SpaceXs.roundToPx()
    val naturals = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
    val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
    val total = naturals.sum()
    val available = constraints.maxWidth - gaps
    val widths = if (constraints.hasBoundedWidth && total > available && total > 0) {
        naturals.map { (it.toLong() * available / total).toInt() }
    } else {
        naturals
    }
    val placeables = measurables.mapIndexed { i, measurable ->
        measurable.measure(Constraints.fixedWidth(widths[i].coerceAtLeast(0)))
    }
    val height = placeables.maxOfOrNull { it.height } ?: 0
    layout(widths.sum() + gaps, height) {
        var x = 0
        placeables.forEach {
            it.placeRelative(x, (height - it.height) / 2)
            x += it.width + gap
        }
    }
}

@Composable
private fun NovaCyclerControl(label: String, onPrevious: () -> Unit, onNext: () -> Unit, onValueTap: (() -> Unit)?) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val valueTap by rememberUpdatedState(onValueTap)
    Row(verticalAlignment = Alignment.CenterVertically) {
        NovaArrow(BackGlyph) { previous() }
        Text(
            text = label,
            style = type.value,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f, fill = false)
                .pointerInput(Unit) { detectTapGestures(onTap = { valueTap?.invoke() }) },
        )
        NovaArrow(OpensGlyph) { next() }
    }
}

@Composable
private fun NovaArrow(glyph: String, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(NovaPanelMetrics.ArrowTarget)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = novaPanelType.value, color = LocalNovaComposeColors.current.textSecondary)
    }
}

@Composable
private fun NovaSwitchControl(on: Boolean, onToggle: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val toggle by rememberUpdatedState(onToggle)
    val track = RoundedCornerShape(NovaRadius.pill)
    val inset = (NovaPanelMetrics.SwitchTrackHeight - NovaPanelMetrics.SwitchThumb) / 2
    Box(
        modifier = Modifier
            .size(width = NovaPanelMetrics.SwitchTrackWidth, height = NovaPanelMetrics.SwitchTrackHeight)
            .clip(track)
            .background(if (on) colors.accent else surfaces.control)
            .border(NovaPanelMetrics.Hairline, if (on) colors.accent else surfaces.tileBorder, track)
            .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) }
            .padding(inset),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(NovaPanelMetrics.SwitchThumb)
                .clip(track)
                .background(if (on) colors.onAccent else colors.textSecondary),
        )
    }
}
