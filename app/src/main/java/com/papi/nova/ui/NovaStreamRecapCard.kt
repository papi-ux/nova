package com.papi.nova.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.novaPanelType
import java.util.Locale

/**
 * The Library's optional Last Stream recap, in the host bar: in the landscape strip's middle when
 * the strip's fit has room for it, otherwise the bar's second line
 * ([NovaLibraryLandscapeShowcaseStripContent], [NovaLibraryPortraitToolbarContent]). It says what
 * ended the stream and when; the sample counts and the readings are the page View opens.
 *
 * It never asks for focus: the Library's own focus restoration keeps the place it had, and the
 * recap is reached only by moving to it. View opens the read-only page; Dismiss removes it,
 * nothing else.
 *
 * Its words take the theme's text roles. They used to take none, and with no Surface above them in
 * the Library they fell back to Material's default content colour, black, on the dark themes.
 */
@Composable
internal fun NovaStreamRecapCard(
    recap: NovaStreamRecap.Recap,
    onView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The lines the summary may take: the strip's measured room, or all it needs on its own line. */
    summaryLines: Int = Int.MAX_VALUE,
    /** Reuse the exact words the landscape strip measured, including their formatted date. */
    preparedWords: NovaTopBarRecap? = null,
) {
    val colors = LocalNovaComposeColors.current
    val focusManager = LocalFocusManager.current
    val words = preparedWords ?: novaStreamRecapWords(recap)
    val title = stringResource(R.string.nova_recap_card_title)
    val summaryStyle = novaStreamRecapSummaryStyle()
    Row(
        modifier = modifier.fillMaxWidth().testTag(NOVA_STREAM_RECAP_CARD_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        // The actions follow the words rather than the far edge, where they read as more menus.
        Column(
            modifier = Modifier.weight(1f, fill = false).novaAvoidCameraCutout(),
            verticalArrangement = Arrangement.spacedBy(NOVA_STREAM_RECAP_WORDS_GAP),
        ) {
            Text(
                text = words.label,
                style = novaStreamRecapLabelStyle(),
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = words.summary,
                style = summaryStyle,
                color = colors.textPrimary,
                maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(NOVA_STREAM_RECAP_SUMMARY_TAG),
            )
        }
        NovaActionSurface(
            onClick = onView,
            contentDescription = "${recapLabel(LocalContext.current, recap, title)}. ${words.summary}. ${words.viewLabel}",
            minHeight = NovaPanelMetrics.ButtonMinHeight,
            modifier = Modifier.testTag(NOVA_STREAM_RECAP_VIEW_TAG),
        ) { contentColor, _ ->
            Text(text = words.viewLabel, style = summaryStyle, color = contentColor, maxLines = 1)
        }
        NovaActionSurface(
            onClick = {
                // The recap is going: focus moves on first, as the search chip's does.
                focusManager.moveFocus(FocusDirection.Down)
                onDismiss()
            },
            contentDescription = "$title. ${words.dismissLabel}",
            minHeight = NovaPanelMetrics.ButtonMinHeight,
            modifier = Modifier.testTag(NOVA_STREAM_RECAP_DISMISS_TAG),
        ) { contentColor, _ ->
            Text(text = words.dismissLabel, style = summaryStyle, color = contentColor, maxLines = 1)
        }
    }
}

/** The card's label, with the badge a long clean stream earned, if it did. */
private fun recapLabel(context: Context, recap: NovaStreamRecap.Recap, title: String): String =
    if (recap.clearSkies) "$title · ${context.getString(R.string.nova_recap_clear_skies)}" else title

/** The recap's words as the card draws them, so the landscape strip can measure it first. */
@Composable
internal fun novaStreamRecapWords(recap: NovaStreamRecap.Recap): NovaTopBarRecap {
    val context = LocalContext.current
    // Written again for a new recap or a new configuration, such as another language.
    val configuration = LocalConfiguration.current
    val title = stringResource(R.string.nova_recap_card_title)
    val view = stringResource(R.string.nova_recap_view)
    val dismiss = stringResource(R.string.nova_recap_dismiss)
    return remember(recap, configuration, title, view, dismiss) {
        NovaTopBarRecap(
            // A section label, as the panels write one.
            label = recapLabel(context, recap, title).uppercase(Locale.getDefault()),
            summary = NovaStreamRecapText.card(context, recap),
            viewLabel = view,
            dismissLabel = dismiss,
        )
    }
}

/**
 * The panel's section label and caption, set solid for the host bar. Unset, their leading is the
 * theme's body text's, 24sp, which left no room in the strip for a label over a caption.
 */
@Composable
@ReadOnlyComposable
internal fun novaStreamRecapLabelStyle(): TextStyle = novaStreamRecapSolid(novaPanelType.sectionLabel)

@Composable
@ReadOnlyComposable
internal fun novaStreamRecapSummaryStyle(): TextStyle = novaStreamRecapSolid(novaPanelType.caption)

/** [style] with a line a little taller than its letters. */
internal fun novaStreamRecapSolid(style: TextStyle): TextStyle =
    style.copy(lineHeight = style.fontSize * NOVA_STREAM_RECAP_LEADING)

private const val NOVA_STREAM_RECAP_LEADING = 1.3f

/** The label and the summary stand this far apart. */
internal val NOVA_STREAM_RECAP_WORDS_GAP = 1.dp

internal const val NOVA_STREAM_RECAP_CARD_TAG = "nova-library-last-stream"
internal const val NOVA_STREAM_RECAP_SUMMARY_TAG = "nova-library-last-stream-summary"
internal const val NOVA_STREAM_RECAP_VIEW_TAG = "nova-library-last-stream-view"
internal const val NOVA_STREAM_RECAP_DISMISS_TAG = "nova-library-last-stream-dismiss"

/** The host bar's second line when the recap is there rather than in the strip's middle. */
internal const val NOVA_LIBRARY_RECAP_LINE_TAG = "nova-library-last-stream-line"

/**
 * The recap at a glance, over its page's actions: the badge, the calm line and the frame rate's
 * shape. All of it is only what was measured, and the page's own words on what it doesn't measure
 * still stand. It never moves, so it is the same under reduced motion.
 */
@Composable
internal fun NovaStreamRecapAtAGlance(recap: NovaStreamRecap.Recap) {
    val colors = LocalNovaComposeColors.current
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().testTag(NOVA_STREAM_RECAP_AT_A_GLANCE_TAG),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        if (recap.clearSkies) {
            Text(
                text = stringResource(R.string.nova_recap_clear_skies),
                style = novaPanelType.caption,
                color = colors.onAccent,
                modifier = Modifier
                    .background(colors.accent, RoundedCornerShape(NovaRadius.chip))
                    .padding(horizontal = NovaPanelMetrics.SpaceSm, vertical = NovaPanelMetrics.SpaceXs),
            )
        }
        if (recap.nothingMissing) {
            Text(
                text = stringResource(R.string.nova_recap_nothing_missing),
                style = novaPanelType.rowTitle,
                color = colors.textPrimary,
            )
        }
        recap.fpsTrail?.let { trail ->
            val words = remember(recap, trail) { NovaStreamRecapText.fpsTrail(context, recap, trail) }
            Column(
                modifier = Modifier.clearAndSetSemantics { contentDescription = words },
                verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
            ) {
                NovaFpsSparkline(trail, Modifier.fillMaxWidth().height(NOVA_FPS_SPARKLINE_HEIGHT).testTag(NOVA_FPS_SPARKLINE_TAG))
                Text(text = words, style = novaPanelType.caption, color = colors.textSecondary)
            }
        }
    }
}

/**
 * One line from zero up to the connection's best, so a steady stream reads flat rather than having
 * its smallest wobble blown up to fill the height. Drawn once; nothing about it animates.
 */
@Composable
private fun NovaFpsSparkline(trail: NovaStreamRecap.FpsTrail, modifier: Modifier) {
    val accent = LocalNovaComposeColors.current.accent
    val line = remember(trail) { novaFpsSparklinePoints(trail.values) }
    Canvas(modifier) {
        val stroke = NOVA_FPS_SPARKLINE_STROKE.toPx()
        val usable = size.height - stroke
        val path = Path()
        line.forEachIndexed { index, (x, y) ->
            val px = x * size.width
            val py = stroke / 2f + (1f - y) * usable
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, accent, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Each value as a point on the unit square, x along the connection and y from zero to the best. */
internal fun novaFpsSparklinePoints(values: List<Double>): List<Pair<Float, Float>> {
    if (values.size < 2) return emptyList()
    val best = values.max().takeIf { it > 0.0 } ?: 1.0
    return values.mapIndexed { index, value ->
        index.toFloat() / (values.size - 1) to (value / best).toFloat()
    }
}

internal const val NOVA_STREAM_RECAP_AT_A_GLANCE_TAG = "nova-stream-recap-at-a-glance"
internal const val NOVA_FPS_SPARKLINE_TAG = "nova-fps-sparkline"
private val NOVA_FPS_SPARKLINE_HEIGHT = 40.dp
private val NOVA_FPS_SPARKLINE_STROKE = 2.dp
