package com.papi.nova.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.novaPanelType
import kotlin.math.max

/** A stable focus stop: hiding navigation never removes the button that opens it again. */
@Composable
internal fun NovaPortraitMenuBar(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toggleModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    // Fit the actual title independently of its local camera clearance. A camera changing
    // padding must not change the title's natural width and repeatedly flip its row.
    val titleWidth = rememberTextMeasurer().measure(
        title, style = LocalTextStyle.current.merge(type.panelTitle), softWrap = false,
    ).size.width
    val titleNaturalWidth = with(LocalDensity.current) { titleWidth.toDp() }
    val label = stringResource(if (expanded) R.string.nova_portrait_hide_menu else R.string.nova_portrait_show_menu)
    // Keep the same composed action nodes in either layout, including their focus owners.
    Layout(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nova-portrait-menu-bar"),
        content = {
            NovaActionSurface(
                modifier = toggleModifier.testTag("nova-portrait-menu-toggle"),
                onClick = onToggle, contentDescription = label,
                minHeight = 48.dp, cornerRadius = NovaRadius.row,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) { color, _ -> Text(label, color = color, style = type.value) }
            Box(Modifier.novaAvoidCameraCutout()) {
                // Measure the intrinsic glyph region, within the real incoming constraints.
                // A title wider than its row still wraps at the available width.
                Text(title, modifier = Modifier.width(titleNaturalWidth),
                    color = colors.textPrimary, style = type.panelTitle)
            }
            if (onBack != null) NovaActionSurface(
                onClick = onBack, contentDescription = stringResource(R.string.nova_settings_back),
                minHeight = 48.dp, cornerRadius = NovaRadius.row,
            ) { color, _ -> Text(stringResource(R.string.nova_settings_back), color = color, style = type.value) }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // Bound both actions to the available row. Ordinary labels keep their intrinsic size;
        // larger labels can wrap while retaining their text and minimum target.
        val back = children.getOrNull(2)?.measure(loose.copy(maxWidth = ((width - gap) / 2).coerceAtLeast(0)))
        val menu = children[0].measure(loose.copy(maxWidth = (width - (back?.width ?: -gap) - gap).coerceAtLeast(0)))
        val availableTitle = (width - menu.width - (back?.width ?: 0) - gap * if (back == null) 1 else 2).coerceAtLeast(0)
        val ownTitleRow = titleWidth > availableTitle
        val heading = children[1].measure(loose.copy(
            maxWidth = if (ownTitleRow) width else availableTitle,
            maxHeight = Constraints.Infinity,
        ))
        val actionHeight = max(constraints.minHeight, max(menu.height, back?.height ?: 0))
        val height = constraints.constrainHeight(if (ownTitleRow) actionHeight + gap + heading.height
            else max(actionHeight, heading.height))
        layout(width, height) {
            // Action positions depend only on their own row, not title wrapping/camera padding.
            // Switching title rows therefore cannot remove/reapply action clearance in a loop.
            menu.placeRelative(0, (actionHeight - menu.height) / 2)
            back?.placeRelative(width - back.width, (actionHeight - back.height) / 2)
            heading.placeRelative(if (ownTitleRow) 0 else menu.width + gap,
                if (ownTitleRow) actionHeight + gap else (height - heading.height) / 2)
        }
    }
}
