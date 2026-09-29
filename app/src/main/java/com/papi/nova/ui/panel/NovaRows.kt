package com.papi.nova.ui.panel

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import java.util.Locale

/** What a [NovaRow] shows at its end. */
sealed interface NovaRowTrailing {
    data object None : NovaRowTrailing

    /** The row opens a page: `›`. */
    data object Opens : NovaRowTrailing

    /** The row is the current value: [NovaCurrentMark]. */
    data object Current : NovaRowTrailing

    /** The row's value, followed by `›`, because a value shown in a row opens its page. */
    data class Value(val text: String) : NovaRowTrailing
}

/**
 * One row of a panel page: a title, an optional caption and icon, and a [trailing] mark.
 *
 * The row is one focus stop with the one focus look, and acts on release through [novaClickable].
 * At rest it is the one row tile ([novaRowRest]). An [emphasis] row takes the accent fill instead,
 * as a menu's primary action does. A row with a [disabledReason] stays focusable, shows the reason
 * as its caption and swallows A. Titles and captions wrap rather than ellipsize, and a
 * [NovaRowTrailing.Value] too long to sit beside the title goes under it.
 */
@Composable
fun NovaRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    @DrawableRes icon: Int? = null,
    trailing: NovaRowTrailing = NovaRowTrailing.None,
    emphasis: Boolean = false,
    disabledReason: String? = null,
) {
    NovaRowLayout(
        title = title,
        onClick = onClick,
        modifier = modifier,
        caption = caption,
        icon = icon,
        trailing = trailing,
        emphasis = emphasis,
        disabledReason = disabledReason,
    )
}

/**
 * [NovaRow] with an optional [leading] slot, such as a theme swatch on a Choice page. A row with
 * [checked] is one toggle of a MultiChoice page: the same check trails it while it is on, with
 * toggle semantics rather than the Current of the one current value.
 */
@Composable
internal fun NovaRowLayout(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    @DrawableRes icon: Int? = null,
    trailing: NovaRowTrailing = NovaRowTrailing.None,
    emphasis: Boolean = false,
    disabledReason: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    checked: Boolean? = null,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val haptics = LocalHapticFeedback.current
    val enabled = disabledReason == null
    val filled = emphasis && enabled
    val current = trailing == NovaRowTrailing.Current
    val marked = current || checked == true
    val currentLabel = stringResource(R.string.nova_panel_current)
    val shape = RoundedCornerShape(NovaRadius.row)
    val ink = when {
        filled -> colors.onAccent
        enabled -> colors.textPrimary
        else -> colors.textMuted
    }
    val quietInk = if (filled) colors.onAccent else colors.textSecondary
    var focused by remember { mutableStateOf(false) }
    val interactive = onClick != null || !enabled
    val rest = novaRowRest

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(
                shape = shape,
                ring = if (filled) colors.onAccent else Color.Unspecified,
                focusedFill = if (filled) colors.accent else Color.Unspecified,
                restFill = if (filled) colors.accent else rest.fill,
                restBorder = if (filled) Color.Transparent else rest.border,
                restBorderWidth = if (filled) 0.dp else rest.borderWidth,
            )
            .semantics(mergeDescendants = true) {
                if (current) {
                    selected = true
                    stateDescription = currentLabel
                }
                checked?.let { toggleableState = ToggleableState(it) }
            }
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            // A row that cannot act swallows A, so the hint bar does not offer it.
            .novaFocusHint(if (enabled) null else NovaFocusHint.Read)
            .then(
                if (interactive) {
                    val role = if (checked != null) Role.Checkbox else Role.Button
                    Modifier.novaClickable(enabled = enabled, role = role, focusableWhenDisabled = true) {
                        haptics.novaConfirm()
                        onClick?.invoke()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        leading?.invoke(this)
        icon?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(NovaPanelMetrics.IconSize),
            )
        }
        val titleBlock: @Composable () -> Unit = {
            Column {
                Text(
                    text = title,
                    style = type.rowTitle,
                    fontWeight = if (marked) FontWeight.SemiBold else type.rowTitle.fontWeight,
                    color = ink,
                )
                // The two-line caption budget is a copy rule the visual gate checks, not a cut.
                (disabledReason ?: caption)?.let { Text(text = it, style = type.caption, color = quietInk) }
            }
        }
        if (trailing is NovaRowTrailing.Value) {
            Layout(
                contents = listOf(titleBlock, { Text(text = trailing.text, style = type.value, color = quietInk) }),
                modifier = Modifier.weight(1f),
                measurePolicy = RowValueMeasurePolicy,
            )
            NovaChevron(back = false, tint = quietInk)
        } else {
            Box(modifier = Modifier.weight(1f)) { titleBlock() }
            when (checked) {
                null -> NovaRowTrailingMark(trailing, quietInk)
                true -> NovaCheckGlyph()
                // Off keeps the check's room, so the label does not move when it toggles.
                false -> Spacer(Modifier.size(NovaPanelMetrics.CurrentMarkSize))
            }
        }
    }
}

/**
 * Lays out a title block and a value or control: beside it at the end while the title keeps at
 * least [NovaPanelMetrics.TitleShare] of the width, and otherwise under it at the start, so
 * neither is squeezed, cut or ellipsized. [labelInset] and [valueInset] pad each above and below,
 * and [stackGap] separates them when stacked.
 */
internal class NovaTitleAndValueMeasurePolicy(
    private val labelInset: Dp,
    private val valueInset: Dp,
    private val stackGap: Dp,
) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val label = measurables[0].first()
        val value = measurables[1].first()
        val besideGap = NovaPanelMetrics.SpaceMd.roundToPx()
        val labelPad = labelInset.roundToPx()
        val valuePad = valueInset.roundToPx()
        val natural = value.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            label.maxIntrinsicWidth(Constraints.Infinity) + besideGap + natural
        }
        return if (natural + besideGap > width * (1f - NovaPanelMetrics.TitleShare)) {
            val gap = stackGap.roundToPx()
            val labelPlaceable = label.measure(Constraints(maxWidth = width))
            val valuePlaceable = value.measure(Constraints(maxWidth = width))
            val content = labelPad + labelPlaceable.height + gap + valuePlaceable.height + labelPad
            val height = maxOf(content, constraints.minHeight)
            layout(width, height) {
                val top = (height - content) / 2 + labelPad
                labelPlaceable.placeRelative(0, top)
                valuePlaceable.placeRelative(0, top + labelPlaceable.height + gap)
            }
        } else {
            val valuePlaceable = value.measure(Constraints(maxWidth = natural))
            val labelPlaceable = label.measure(Constraints(maxWidth = (width - valuePlaceable.width - besideGap).coerceAtLeast(0)))
            val height = maxOf(
                labelPlaceable.height + 2 * labelPad,
                valuePlaceable.height + 2 * valuePad,
                constraints.minHeight,
            )
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        }
    }
}

// Inside a row, which pads itself.
private val RowValueMeasurePolicy = NovaTitleAndValueMeasurePolicy(
    labelInset = 0.dp,
    valueInset = 0.dp,
    stackGap = NovaPanelMetrics.SpaceXs,
)

@Composable
private fun NovaRowTrailingMark(trailing: NovaRowTrailing, color: Color) {
    val type = novaPanelType
    when (trailing) {
        NovaRowTrailing.None -> Unit
        NovaRowTrailing.Current -> NovaCurrentMark()
        NovaRowTrailing.Opens -> NovaChevron(back = false, tint = color)
        // Laid out with the title by NovaTitleAndValueMeasurePolicy.
        is NovaRowTrailing.Value -> Unit
    }
}

/** A section heading inside a page, in the accent chrome face. */
@Composable
fun NovaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = novaPanelType.sectionLabel,
        color = LocalNovaComposeColors.current.accent,
        modifier = modifier
            .padding(top = NovaPanelMetrics.SpaceSm, bottom = NovaPanelMetrics.SpaceXs)
            .semantics { heading() },
    )
}

/**
 * The one mark for the current value: a trailing accent check. Rows that carry it also set a
 * SemiBold label and `selected` semantics; fills and borders only ever mean focus.
 */
@Composable
fun NovaCurrentMark(modifier: Modifier = Modifier) {
    NovaCheckGlyph(modifier.testTag(NovaCurrentMarkTag))
}

/** The accent check itself, shared by the current mark and a MultiChoice toggle. */
@Composable
private fun NovaCheckGlyph(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        tint = LocalNovaComposeColors.current.accent,
        modifier = modifier.size(NovaPanelMetrics.CurrentMarkSize),
    )
}

/** Test tag of every [NovaCurrentMark], so a test can find where current is marked. */
const val NovaCurrentMarkTag = "nova-current-mark"

/**
 * The one chevron: `›` for a row that opens a page, and with [back] `‹` for a page's way back or a
 * value's previous step. It is drawn, 18dp in the trailing slot's size, so it never grows with the
 * font scale past its slot or sits below the text beside it; the text characters did both.
 */
@Composable
fun NovaChevron(back: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_nova_chevron),
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(NovaPanelMetrics.CurrentMarkSize)
            .then(if (back) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
            .testTag(if (back) NovaChevronBackTag else NovaChevronOpensTag),
    )
}

/** Test tags of [NovaChevron], by direction. */
const val NovaChevronBackTag = "nova-chevron-back"
const val NovaChevronOpensTag = "nova-chevron-opens"
