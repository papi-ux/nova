package com.papi.nova.ui.panel

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius

/**
 * A button on a panel page or a state page: the shared action surface, with its label in the
 * panel's value type, centred and wrapping onto more lines rather than cut at any size or font
 * scale. An [icon] leads the label, as the destructive half of a split carries its own.
 */
@Composable
internal fun NovaPanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    NovaActionSurface(
        onClick = onClick,
        modifier = modifier,
        primary = primary,
        destructive = destructive,
        contentDescription = text,
        minHeight = NovaPanelMetrics.ButtonMinHeight,
        cornerRadius = NovaRadius.hero,
        contentPadding = PaddingValues(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
    ) { contentColor, _ ->
        if (icon == null) {
            Text(text = text, style = novaPanelType.value, color = contentColor, textAlign = TextAlign.Center)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm, Alignment.CenterHorizontally),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(NovaPanelMetrics.IconSize),
                )
                Text(text = text, style = novaPanelType.value, color = contentColor, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Two buttons, [first] at the start and [second] beside it, while each half keeps at least
 * [NovaPanelMetrics.SplitHalfMinWidth] and both labels fit on one line. Otherwise [first] sits
 * above [second], both at full width, as a split confirm takes its whole row, so neither label
 * is squeezed into a sliver.
 */
@Composable
internal fun NovaPanelButtonPair(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(first, second), modifier = modifier.fillMaxWidth(), measurePolicy = ButtonPairMeasurePolicy)
}

private val ButtonPairMeasurePolicy = MultiContentMeasurePolicy { (firsts, seconds), constraints ->
    val first = firsts.first()
    val second = seconds.first()
    val gap = NovaPanelMetrics.SplitGap.roundToPx()
    val firstNatural = first.maxIntrinsicWidth(Constraints.Infinity)
    val secondNatural = second.maxIntrinsicWidth(Constraints.Infinity)
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else firstNatural + gap + secondNatural
    val half = (width - gap) / 2
    val beside = half >= NovaPanelMetrics.SplitHalfMinWidth.roundToPx() && firstNatural <= half && secondNatural <= half
    if (beside) {
        val height = maxOf(first.minIntrinsicHeight(half), second.minIntrinsicHeight(half))
        val start = first.measure(Constraints.fixed(half, height))
        val end = second.measure(Constraints.fixed(half, height))
        layout(width, height) {
            start.placeRelative(0, 0)
            end.placeRelative(width - half, 0)
        }
    } else {
        val stackGap = NovaPanelMetrics.SpaceSm.roundToPx()
        val full = Constraints(minWidth = width, maxWidth = width)
        val top = first.measure(full)
        val bottom = second.measure(full)
        layout(width, top.height + stackGap + bottom.height) {
            top.placeRelative(0, 0)
            bottom.placeRelative(0, top.height + stackGap)
        }
    }
}
