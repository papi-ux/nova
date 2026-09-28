package com.papi.nova.ui.panel

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * An [emphasis] row takes the accent fill, as a menu's primary action does. A row with a
 * [disabledReason] stays focusable, shows the reason as its caption and swallows A. Titles wrap
 * rather than ellipsize.
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

/** [NovaRow] with an optional [leading] slot, such as a theme swatch on a Choice page. */
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
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val haptics = LocalHapticFeedback.current
    val enabled = disabledReason == null
    val filled = emphasis && enabled
    val current = trailing == NovaRowTrailing.Current
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(
                shape = shape,
                ring = if (filled) colors.onAccent else Color.Unspecified,
                focusedFill = if (filled) colors.accent else Color.Unspecified,
                restFill = if (filled) colors.accent else Color.Transparent,
            )
            .semantics(mergeDescendants = true) {
                if (current) {
                    selected = true
                    stateDescription = currentLabel
                }
            }
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            .then(
                if (interactive) {
                    Modifier.novaClickable(enabled = enabled, role = Role.Button, focusableWhenDisabled = true) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = type.rowTitle,
                fontWeight = if (current) FontWeight.SemiBold else type.rowTitle.fontWeight,
                color = ink,
            )
            (disabledReason ?: caption)?.let {
                Text(
                    text = it,
                    style = type.caption,
                    color = quietInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NovaRowTrailingMark(trailing, quietInk)
    }
}

@Composable
private fun NovaRowTrailingMark(trailing: NovaRowTrailing, color: Color) {
    val type = novaPanelType
    when (trailing) {
        NovaRowTrailing.None -> Unit
        NovaRowTrailing.Current -> NovaCurrentMark()
        NovaRowTrailing.Opens -> Text(text = OpensGlyph, style = type.value, color = color)
        is NovaRowTrailing.Value -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        ) {
            Text(text = trailing.text, style = type.value, color = color)
            Text(text = OpensGlyph, style = type.value, color = color)
        }
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
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        tint = LocalNovaComposeColors.current.accent,
        modifier = modifier
            .size(NovaPanelMetrics.CurrentMarkSize)
            .testTag(NovaCurrentMarkTag),
    )
}

/** Test tag of every [NovaCurrentMark], so a test can find where current is marked. */
const val NovaCurrentMarkTag = "nova-current-mark"
