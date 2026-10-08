package com.papi.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaPanelType

/**
 * The Library's optional Last Stream card, above the games like the search chip. It never asks
 * for focus: the Library's own focus restoration keeps the place it had, and the card is reached
 * only by moving to it. View opens the read-only page; Dismiss removes it, nothing else.
 */
@Composable
internal fun NovaStreamRecapCard(
    recap: NovaStreamRecap.Recap,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val title = stringResource(R.string.nova_recap_card_title)
    val summary = NovaStreamRecapText.card(context, recap)
    Row(
        modifier = modifier.fillMaxWidth().testTag(NOVA_STREAM_RECAP_CARD_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = novaPanelType.caption)
            Text(text = summary, style = novaPanelType.caption)
        }
        NovaActionSurface(
            onClick = onView,
            contentDescription = "$title. $summary. ${stringResource(R.string.nova_recap_view)}",
            minHeight = NovaPanelMetrics.ButtonMinHeight,
            modifier = Modifier.testTag(NOVA_STREAM_RECAP_VIEW_TAG),
        ) { contentColor, _ ->
            Text(text = stringResource(R.string.nova_recap_view), style = novaPanelType.caption, color = contentColor)
        }
        NovaActionSurface(
            onClick = {
                // The card is going: focus moves on first, as the search chip's does.
                focusManager.moveFocus(FocusDirection.Down)
                onDismiss()
            },
            contentDescription = "$title. ${stringResource(R.string.nova_recap_dismiss)}",
            minHeight = NovaPanelMetrics.ButtonMinHeight,
            modifier = Modifier.testTag(NOVA_STREAM_RECAP_DISMISS_TAG),
        ) { contentColor, _ ->
            Text(text = stringResource(R.string.nova_recap_dismiss), style = novaPanelType.caption, color = contentColor)
        }
    }
}

internal const val NOVA_STREAM_RECAP_CARD_TAG = "nova-library-last-stream"
internal const val NOVA_STREAM_RECAP_VIEW_TAG = "nova-library-last-stream-view"
internal const val NOVA_STREAM_RECAP_DISMISS_TAG = "nova-library-last-stream-dismiss"
