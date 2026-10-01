package com.papi.nova.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import com.papi.nova.ui.compose.novaControlDimension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 *
 * The row keeps its size as the value changes: a cycler reserves its widest label and a segment
 * reserves room for the check. Segments that cannot fit the row even under the title draw as a
 * cycler instead of breaking their labels. With [wrapUnderTitle] false, segments that cannot sit
 * beside the title draw as a cycler too, so the row stays one line tall where height is short.
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
    wrapUnderTitle: Boolean = true,
    onActivateChoice: (() -> Unit)? = null,
) {
    val resolved = remember(options, style) { resolveNovaValueStyle(options, style) }
    val labelWidths = rememberNovaLabelWidths(remember(options) { options.map { it.label } })
    val widest = labelWidths.maxOrNull() ?: 0.dp
    val segmentsWidth = novaSegmentsWidth(labelWidths)
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
        onPrevious = { step(-1, wrap = !ordered) },
        onNext = { step(1, wrap = !ordered) },
        // A on a switch flips it, as its hint says: stepping forward only ever turned it on, so
        // HDR and every other switch could be turned off with Left alone.
        onActivate = {
            if (onActivateChoice != null) {
                onActivateChoice()
            } else if (isSwitch) {
                step(if (options.getOrNull(latestIndex)?.value == true) -1 else 1, wrap = false)
            } else {
                step(1, wrap = true)
            }
        },
        hint = if (isSwitch) NovaFocusHint.Toggle else NovaFocusHint.Next,
        modifier = modifier,
    ) { available, focused ->
        when {
            resolved == NovaValueStyle.Switch -> NovaSwitchControl(
                on = options.getOrNull(index)?.value == true,
                onToggle = { step(if (options.getOrNull(latestIndex)?.value == true) -1 else 1, wrap = false) },
            )
            resolved == NovaValueStyle.Segmented && segmentsWidth <= novaSegmentsRoom(available, wrapUnderTitle) ->
                NovaSegmentedControl(options, index, onSelect = ::select)
            else -> NovaCyclerControl(
                label = options.getOrNull(index)?.label.orEmpty(),
                widest = widest,
                focused = focused,
                onPrevious = { step(-1, wrap = !ordered) },
                onNext = { step(1, wrap = !ordered) },
                onValueTap = onOpenList,
            )
        }
    }
}

/** The single-line width of each label in the panel's value type, which is SemiBold, its widest. */
@Composable
private fun rememberNovaLabelWidths(labels: List<String>): List<Dp> {
    val measurer = rememberTextMeasurer()
    val style = novaPanelType.value
    val density = LocalDensity.current
    return remember(labels, style, density, measurer) {
        labels.map { label ->
            with(density) { measurer.measure(label, style, softWrap = false, maxLines = 1).size.width.toDp() }
        }
    }
}

/**
 * The widest segmented control a row [available] wide can draw: the whole row, under the title,
 * or with [wrapUnderTitle] false only what sits beside the title while it keeps its share.
 */
internal fun novaSegmentsRoom(available: Dp, wrapUnderTitle: Boolean): Dp =
    if (wrapUnderTitle) available else available * (1f - NovaPanelMetrics.TitleShare) - NovaPanelMetrics.SpaceMd

/** The natural width of a segmented control whose labels are [labelWidths] wide. */
private fun novaSegmentsWidth(labelWidths: List<Dp>): Dp {
    val segment = NovaPanelMetrics.SpaceSm * 2 + NovaPanelMetrics.SpaceXs + NovaPanelMetrics.CurrentMarkSize
    val gaps = NovaPanelMetrics.SpaceXs * (labelWidths.size - 1).coerceAtLeast(0)
    return labelWidths.fold(gaps + NovaPanelMetrics.SpaceXs * 2) { total, width -> total + width + segment }
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
    /** A scoped live control delegates arithmetic to its own codec-aware engine. */
    onStep: ((Int) -> Unit)? = null,
) {
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val exact by rememberUpdatedState(onExact)
    val haptics = LocalHapticFeedback.current
    // The ends are the widest labels a range usually has; the current one covers the rest.
    val widest = rememberNovaLabelWidths(listOf(format(range.first), format(range.last), format(value))).max()

    fun move(direction: Int, repeats: Int) {
        if (!enabled) return
        if (onStep != null) { haptics.novaFocusTick(); onStep(direction); return }
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
        onPrevious = { repeats -> move(-1, repeats) },
        onNext = { repeats -> move(1, repeats) },
        onActivate = { exact?.invoke() },
        hint = if (onExact != null) NovaFocusHint.TypeValue else NovaFocusHint.Change,
        modifier = modifier,
    ) { _, focused ->
        NovaCyclerControl(
            label = format(value),
            widest = widest,
            focused = focused,
            onPrevious = { move(-1, 0) },
            onNext = { move(1, 0) },
            onValueTap = onExact,
        )
    }
}

/**
 * The shared frame of value and stepper rows: one focus stop that owns Left, Right and A, with the
 * control beside the title while the title keeps 40% of the row, and under it otherwise. It rests
 * as the one row tile and sits on the row scale: the control gets 4dp above and below.
 * [onPrevious] and [onNext] get the key's repeat count, and [control] the width it may take.
 */
@Composable
private fun NovaValueRowFrame(
    title: String,
    caption: String?,
    enabled: Boolean,
    stateLabel: String,
    role: Role?,
    onPrevious: (repeats: Int) -> Unit,
    onNext: (repeats: Int) -> Unit,
    onActivate: () -> Unit,
    hint: NovaFocusHint,
    modifier: Modifier = Modifier,
    control: @Composable (available: Dp, focused: Boolean) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val previousLabel = stringResource(R.string.nova_panel_previous)
    val nextLabel = stringResource(R.string.nova_panel_next)
    // Previous and Next follow the options' order. The D-pad follows the screen: a right to left
    // layout lays the options out from the right, so Left moves on to the next one there.
    val leftKey = if (rtl) next else previous
    val rightKey = if (rtl) previous else next
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            // A stacked row is already taller than the 48dp touch floor. A graphics-layer
            // clip would then stop its compact control's expanded target at the row edge.
            // Clip the same rounded visuals while leaving hit testing to the controls.
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                onDrawWithContent {
                    clipPath(path) { this@onDrawWithContent.drawContent() }
                }
            }
            .novaFocusRing(shape, rest = novaRowRest)
            .novaFocusHint(if (enabled) hint else NovaFocusHint.Read)
            .onFocusChanged { focused = it.hasFocus }
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
                // A row that cannot change has nothing for Left and Right to do, so they move focus
                // on, as they would past any other row. Kept, they trapped the cursor: Left from a
                // disabled row in Settings never reached the rail beside it.
                if (!enabled) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                val direction = when (event.key) {
                    Key.DirectionLeft -> leftKey
                    Key.DirectionRight -> rightKey
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) direction(native.repeatCount)
                // Both halves of the key are the row's; focus never leaves it sideways.
                true
            }
            .novaClickable(enabled = enabled, focusableWhenDisabled = true, onClick = onActivate)
            .alpha(if (enabled) 1f else NovaPanelMetrics.DisabledAlpha)
            .padding(horizontal = novaControlDimension(NovaPanelMetrics.SpaceMd)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BoxWithConstraints(propagateMinConstraints = true) {
            val available = maxWidth
            Layout(
                contents = listOf(
                    {
                        Column {
                            Text(text = title, style = type.rowTitle, color = colors.textPrimary)
                            caption?.let { Text(text = it, style = type.caption, color = colors.textSecondary) }
                        }
                    },
                    { control(available, focused) },
                ),
                measurePolicy = novaValueRowMeasurePolicy(),
            )
        }
    }
}

// The title keeps a row's usual inset; the control, 4dp in from the top and bottom, keeps a plain row's height.
@Composable
private fun novaValueRowMeasurePolicy(): NovaTitleAndValueMeasurePolicy {
    val labelInset = novaControlDimension(NovaPanelMetrics.SpaceSm)
    val valueInset = novaControlDimension(NovaPanelMetrics.SpaceXs)
    val stackGap = novaControlDimension(NovaPanelMetrics.SpaceSm)
    return remember(labelInset, valueInset, stackGap) {
        NovaTitleAndValueMeasurePolicy(labelInset, valueInset, stackGap)
    }
}

/**
 * The segments, drawn one way wherever they sit: the labels on the row itself with no box of their
 * own, the current one SemiBold with its check. Beside the title they take their natural widths;
 * under it they share the row's whole width equally. An inner box stopped short of the row under
 * the title, and its fill vanished into the focus fill, so the same control had three looks.
 */
@Composable
private fun <T> NovaSegmentedControl(options: List<NovaOption<T>>, index: Int, onSelect: (Int) -> Unit) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val select by rememberUpdatedState(onSelect)
    Layout(
        measurePolicy = SegmentsMeasurePolicy,
        content = {
            options.forEachIndexed { i, option ->
                val isCurrent = i == index
                Row(
                    modifier = Modifier
                        .heightIn(min = NovaPanelMetrics.SegmentMinHeight)
                        .clip(RoundedCornerShape(NovaRadius.chip))
                        .pointerInput(i) { detectTapGestures(onTap = { select(i) }) }
                        .padding(horizontal = novaControlDimension(NovaPanelMetrics.SpaceSm)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs, Alignment.CenterHorizontally),
                ) {
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
                    // The trailing check of R9. Every segment keeps its room, so none moves when the value does.
                    if (isCurrent) {
                        NovaCurrentMark()
                    } else {
                        Spacer(Modifier.size(NovaPanelMetrics.CurrentMarkSize))
                    }
                }
            }
        },
    )
}

/**
 * Segments take their natural widths where that is all the room they are given, beside a title.
 * Given more, under a title, they share it: equally while every label fits its share, otherwise
 * each its natural width and an equal part of what is left. NovaValueRow draws a cycler when they
 * would not fit at all, so the proportional shrink here is only a guard against rounding, never a
 * place labels break.
 */
private val SegmentsMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val gap = NovaPanelMetrics.SpaceXs.roundToPx()
    val naturals = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
    val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
    val total = naturals.sum()
    val available = constraints.maxWidth - gaps
    val count = measurables.size.coerceAtLeast(1)
    val widths = when {
        !constraints.hasBoundedWidth || total <= 0 -> naturals
        total > available -> naturals.map { (it.toLong() * available / total).toInt() }
        available / count >= (naturals.maxOrNull() ?: 0) -> List(measurables.size) { available / count }
        else -> naturals.map { it + (available - total) / count }
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

/** `‹ label ›`, reserving the [widest] label's width so the arrows never move as the value does. */
@Composable
private fun NovaCyclerControl(
    label: String,
    widest: Dp,
    focused: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onValueTap: (() -> Unit)?,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val valueTap by rememberUpdatedState(onValueTap)
    Row(verticalAlignment = Alignment.CenterVertically) {
        NovaArrow(back = true, focused = focused) { previous() }
        Text(
            text = label,
            style = type.value,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(min = widest)
                .pointerInput(Unit) { detectTapGestures(onTap = { valueTap?.invoke() }) },
        )
        NovaArrow(back = false, focused = focused) { next() }
    }
}

/**
 * A cycler's arrow: a 48dp target around the 18dp chevron, in accent while the row has focus and
 * not drawn at rest, where the row shows only its value as Play Setup's rows do. It keeps its room
 * at rest, so the value never moves as focus comes and goes.
 */
@Composable
private fun NovaArrow(back: Boolean, focused: Boolean, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(width = NovaPanelMetrics.ArrowTarget, height = NovaPanelMetrics.ValueControlHeight)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        NovaChevron(
            back = back,
            tint = if (focused) LocalNovaComposeColors.current.accent else androidx.compose.ui.graphics.Color.Transparent,
        )
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
