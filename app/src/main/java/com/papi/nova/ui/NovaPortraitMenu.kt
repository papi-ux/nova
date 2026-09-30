package com.papi.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.novaPanelType

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
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nova-portrait-menu-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) NovaActionSurface(
            onClick = onBack, contentDescription = stringResource(R.string.nova_settings_back),
            minHeight = 48.dp, cornerRadius = NovaRadius.row,
        ) { color, _ -> Text(stringResource(R.string.nova_settings_back), color = color, style = novaPanelType.value) }
        Text(title, modifier = Modifier.weight(1f), color = colors.textPrimary, style = novaPanelType.panelTitle)
        val label = stringResource(if (expanded) R.string.nova_portrait_hide_menu else R.string.nova_portrait_show_menu)
        NovaActionSurface(
            modifier = toggleModifier.testTag("nova-portrait-menu-toggle"),
            onClick = onToggle, contentDescription = label,
            minHeight = 48.dp, cornerRadius = NovaRadius.row,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { color, _ -> Text(label, color = color, style = novaPanelType.value) }
    }
}
