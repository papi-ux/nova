package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.NovaCurrentMark
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaPanelType

/**
 * Play Setup: one destination for the whole question of how this game should run.
 *
 * It replaces Launch Mode and Tune, which read as two decisions and were one. Each of
 * them ended up holding the other's subject -- Launch Mode carried resolution, bitrate
 * and codec behind a *More launch settings* row, and Tune carried Steam launch mode --
 * so making a single choice meant crossing between two drawers.
 *
 * The layout is a reading order rather than a grouping by which subsystem owns a
 * setting. Left is everything you read: what will happen, and why it is like that.
 * Right is everything you do: the few real choices, and what the alternatives to the
 * focused one would mean.
 *
 * It takes the full width because the decision is comparative -- this mode against that
 * one, this resolution against that one, both against what happened last session -- and
 * a 53% lane cannot put two things side by side.
 *
 * Nothing here is new data. [NovaLaunchProfileSummary] already computes every figure in
 * the left column; three of its fields -- `historyLines`, `requestedLine` and
 * `primaryLaunchLabel` -- were computed on every launch and rendered nowhere at all.
 *
 * Nothing in it is cut, and nothing moves by itself (R13). Every line wraps whole. What does
 * not fit where it stands is shown whole somewhere it does: the plan as a row that opens the
 * plan's own page, the legend as the one card for the current choice, or not at all.
 */
@Composable
internal fun NovaPlaySetupBody(
    plan: NovaPlaySetupPlan,
    rows: @Composable () -> Unit,
    /** The legend for the row under the cursor, in the form the room it has allows. */
    comparison: (@Composable (NovaPlaySetupLegendForm) -> Unit)? = null,
    /** The read column's head; host scope reads differently than a game does. */
    readTitle: String? = null,
    /**
     * The height the body has. Side by side, the read column fits itself into it: its lines
     * are not a stop on the d-pad, so the panel's scroll, which follows focus, could never bring
     * a line below the fold into view.
     */
    fitHeight: Dp = Dp.Unspecified,
    /**
     * Opens the plan whole on a page of its own, where each part of it is a stop the cursor can
     * scroll to. Given, a panel too narrow for two columns shows the plan as one row that opens
     * it; null keeps the whole plan in the body.
     */
    onOpenPlan: ((NovaPlaySetupPlan) -> Unit)? = null,
    /** Focus marks for the plan's row, so focus comes back to it when the plan's page pops (R7). */
    planRowModifier: Modifier = Modifier,
) {
    val title = readTitle ?: stringResource(R.string.nova_play_setup_what_will_happen)
    val planRow: @Composable () -> Unit = {
        NovaPlaySetupPlanRow(plan, title, { onOpenPlan?.invoke(plan) }, planRowModifier)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A phone in portrait has no room for two columns, and stacking them keeps the
        // same reading order: read the plan, then act on it.
        val stacked = maxWidth < NOVA_PLAY_SETUP_TWO_COLUMN_MIN
        // Given its height, the body keeps the legend in sight and scrolls the rows above it.
        val pinned = fitHeight != Dp.Unspecified && fitHeight > 0.dp
        // Stacked, the plan shares one scroll with the rows, and the cursor, which stops only on
        // the rows, would scroll the top of a tall plan away with no way back to it. So the plan
        // is a row there, whole, and its page holds the rest.
        val stackedPlan: @Composable () -> Unit = if (onOpenPlan != null) {
            planRow
        } else {
            { NovaPlaySetupReadColumn(plan, Modifier.fillMaxWidth(), title) }
        }
        if (stacked && pinned) {
            Column(modifier = Modifier.fillMaxWidth().height(fitHeight)) {
                NovaPlaySetupRowsRegion(
                    above = {
                        stackedPlan()
                        Spacer(modifier = Modifier.height(NOVA_PLAY_SETUP_READ_GAP))
                    },
                ) {
                    NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_what_you_can_change))
                    rows()
                }
                NovaPlaySetupPinnedLegend(comparison, novaPlaySetupLegendCap(fitHeight, columnHead = false))
            }
        } else if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                stackedPlan()
                Spacer(modifier = Modifier.height(NOVA_PLAY_SETUP_READ_GAP))
                NovaPlaySetupActColumn(rows, comparison, Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().then(if (pinned) Modifier.height(fitHeight) else Modifier)) {
                // Beside the rows the plan has the column's whole height: whole when it fits, and
                // the row that opens its page when it does not, never a line cut at the bottom.
                val readModifier = Modifier.width(NOVA_PLAY_SETUP_READ_WIDTH).fillMaxHeight()
                if (pinned && onOpenPlan != null) {
                    NovaFirstThatFits(
                        maxHeight = fitHeight,
                        forms = listOf(
                            { NovaPlaySetupReadColumn(plan, Modifier.fillMaxWidth(), title) },
                            planRow,
                        ),
                        modifier = readModifier,
                    )
                } else {
                    NovaPlaySetupReadColumn(plan, readModifier, title)
                }
                Spacer(modifier = Modifier.width(NOVA_PLAY_SETUP_GUTTER))
                NovaPlaySetupActColumn(
                    rows = rows,
                    comparison = comparison,
                    modifier = Modifier.weight(1f),
                    pinned = pinned,
                    legendCap = novaPlaySetupLegendCap(fitHeight, columnHead = true),
                )
            }
        }
    }
}

/**
 * What will happen, and where each part of it came from. Read, never operated, and whole: every
 * line and every fact's detail wraps onto the lines it needs, so nothing here is cut and nothing
 * has to scroll itself into view to be read.
 */
@Composable
private fun NovaPlaySetupReadColumn(
    plan: NovaPlaySetupPlan,
    modifier: Modifier = Modifier,
    readTitle: String,
) {
    Column(modifier = modifier.testTag(NOVA_PLAY_SETUP_READ_TAG)) {
        NovaPlaySetupColumnHead(readTitle)
        NovaPlaySetupPlanStatement(plan)
        if (plan.facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            NovaPlaySetupRule()
            plan.facts.forEach { fact -> NovaPlaySetupFact(fact = fact) }
        }
    }
}

/** The plan's statement: the resolved mode in the page title style, then its lines, all wrapping. */
@Composable
internal fun NovaPlaySetupPlanStatement(plan: NovaPlaySetupPlan) {
    val colors = LocalNovaComposeColors.current
    Column {
        // A mode name longer than the column is read whole on a second line rather than cut (R13).
        Text(text = plan.mode, style = novaPanelType.pageTitle, color = colors.textPrimary)
        plan.lines.forEachIndexed { index, line ->
            Text(
                text = line,
                // The last line is the part nobody asked for but everyone wants to know:
                // whether anything outside this game is about to be touched.
                color = if (index == plan.lines.lastIndex) colors.textMuted else colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The plan as one row: the mode it resolved to, what it resolves to in one line, and, when
 * anything holds it back, what does. A opens the whole plan on its own page.
 */
@Composable
private fun NovaPlaySetupPlanRow(
    plan: NovaPlaySetupPlan,
    readTitle: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(NOVA_PLAY_SETUP_PLAN_ROW_TAG)) {
        NovaPlaySetupColumnHead(readTitle)
        NovaRow(
            title = plan.mode,
            caption = novaPlaySetupPlanSummary(plan),
            trailing = NovaRowTrailing.Opens,
            onClick = onOpen,
            modifier = modifier,
        )
    }
}

/**
 * The plan's row caption: its first line, and the first fact that warns, as "Key: value". The
 * rest is a press away on the plan's page.
 */
internal fun novaPlaySetupPlanSummary(plan: NovaPlaySetupPlan): String? =
    listOfNotNull(
        plan.lines.firstOrNull()?.takeIf { it.isNotBlank() },
        plan.facts.firstOrNull { it.tone == NovaPlaySetupTone.WARN }?.let { "${it.key}: ${it.value}" },
    ).joinToString("\n").takeIf { it.isNotBlank() }

/**
 * The few real choices, and what the alternatives to the focused one would mean.
 *
 * [pinned] keeps the legend in sight. The legend explains the row under the cursor, and it was
 * drawn after the last row, inside the one scroll the whole panel shares. That was built for four
 * rows. With Frame Rate, Encoder, Face Buttons and the places a game can open above them there
 * are seven and a row of cards, so on the Retroid the legend sat a screen below the row it
 * explained: the scroll follows focus, and focus never goes to a legend. A press on Resolution
 * changed the value with its alternatives never shown. So the rows scroll by themselves and the
 * legend is drawn under them, outside that scroll, where no number of rows can push it away.
 */
@Composable
private fun NovaPlaySetupActColumn(
    rows: @Composable () -> Unit,
    comparison: (@Composable (NovaPlaySetupLegendForm) -> Unit)?,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    legendCap: Dp = Dp.Unspecified,
) {
    Column(modifier = modifier) {
        NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_what_you_can_change))
        if (pinned) {
            NovaPlaySetupRowsRegion { rows() }
            NovaPlaySetupPinnedLegend(comparison, legendCap)
        } else {
            rows()
            if (comparison != null) {
                Spacer(modifier = Modifier.height(4.dp))
                comparison(NovaPlaySetupLegendForm.All)
            }
        }
    }
}

/**
 * The part of the body that scrolls: the rows, and on a narrow screen the plan [above] them.
 *
 * It takes what its content needs and no more, so a short list keeps its legend directly under
 * it rather than across a gap at the bottom of the panel. Focus brings a row into view here the
 * way it does in the panel's own scroll, except that it never stops part of the way into the plan
 * above the rows: it stops at the top, or below the plan, so the plan is whole or out of view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.NovaPlaySetupRowsRegion(
    above: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    var abovePx by remember { mutableIntStateOf(0) }
    val page = LocalBringIntoViewSpec.current
    val spec = remember(page, scroll) { NovaPlaySetupWholePlanSpec(page, scroll) { abovePx } }
    // More than the gap under the last row. Focus brings a row's own bounds into view and not
    // the gap below it, so at the last row the scroll could still move by that gap, and "can
    // scroll" kept the dissolve drawn over the final row as if another followed.
    val slack = with(LocalDensity.current) { NOVA_PLAY_SETUP_ROWS_FADE.toPx() }
    val moreBelow = scroll.maxValue - scroll.value > slack
    // Scrolled down past the rows' head, the row at the top edge may be cut; it dissolves there,
    // as the last row does at the bottom, rather than ending in half a line. Stopped just below
    // the plan, the head stands whole at the edge and is not dimmed.
    val moreAbove = scroll.value > abovePx + slack
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            // A slim band. The panel's own is as tall as a row, and focus brings a row to the
            // bottom edge of this region, so the full band dissolved the row under the cursor.
            .novaFadeAtCut(moreBelow, band = NOVA_PLAY_SETUP_ROWS_FADE, atTop = moreAbove)
            .testTag("nova-play-setup-rows"),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
                if (above != null) {
                    Column(modifier = Modifier.fillMaxWidth().onSizeChanged { abovePx = it.height }) { above() }
                }
                content()
            }
        }
    }
}

/**
 * The page's own scrolling, [page], except where it would stop inside the first [above] pixels of
 * the rows' scroll: there it stops at the top when the row it brings in fits there, and below
 * that part otherwise, so the plan above the rows is never left cut at the region's top edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private class NovaPlaySetupWholePlanSpec(
    private val page: BringIntoViewSpec,
    private val scroll: ScrollState,
    private val above: () -> Int,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val wanted = page.calculateScrollDistance(offset, size, containerSize)
        val stop = novaPlaySetupWholePlanStop(
            current = scroll.value.toFloat(),
            wanted = wanted,
            above = above().toFloat(),
            fitsAtTop = offset + scroll.value + size <= containerSize,
        )
        return stop - scroll.value
    }
}

/**
 * Where the rows' scroll stops for a move that wants to go [wanted] from [current]: there, unless
 * that is part of the way into the [above] part, where it goes to the top when the row fits there
 * ([fitsAtTop]) and just past that part when it does not.
 */
internal fun novaPlaySetupWholePlanStop(current: Float, wanted: Float, above: Float, fitsAtTop: Boolean): Float {
    val target = current + wanted
    if (above <= 0f || target <= 0f || target >= above) return target
    return if (fitsAtTop) 0f else above
}

/** How much of the legend there is room to show. */
internal enum class NovaPlaySetupLegendForm {
    /** Every option, each with what choosing it would mean. */
    All,

    /** Only the current option's card, whole, for a legend whose every card would not fit. */
    Current,
}

/**
 * The legend, under the rows and outside their scroll.
 *
 * It is laid out before the rows, which take what is left, so it is held to [cap]: all of its
 * cards whole when they fit, the current choice's card whole when they do not, and nothing when
 * not even that fits, so the row under the cursor always keeps its room. It is never cut (R13).
 * Every Game's Default Display is seven modes in three rows of cards, and a handheld has room for
 * the one that is set.
 *
 * It keeps the tallest height it has had, so moving the cursor from a row with a long legend to
 * one with a short one does not grow the rows above it and then shrink them back on the next move.
 */
@Composable
private fun NovaPlaySetupPinnedLegend(comparison: (@Composable (NovaPlaySetupLegendForm) -> Unit)?, cap: Dp) {
    if (comparison == null) return
    NovaFirstThatFits(
        maxHeight = cap,
        forms = listOf(
            { comparison(NovaPlaySetupLegendForm.All) },
            { comparison(NovaPlaySetupLegendForm.Current) },
        ),
        keepTallest = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NOVA_PLAY_SETUP_LEGEND_GAP)
            .testTag("nova-play-setup-legend"),
    )
}

/**
 * The first of [forms] whose natural height fits [maxHeight], placed whole, or nothing when none
 * does. Each form is measured as it would be drawn, so the choice is the drawing's, not an estimate.
 * With [keepTallest] it keeps the tallest height it has placed, up to [maxHeight].
 */
@Composable
internal fun NovaFirstThatFits(
    maxHeight: Dp,
    forms: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    keepTallest: Boolean = false,
) {
    val tallest = remember(maxHeight) { NovaTallestHeight() }
    SubcomposeLayout(modifier = modifier) { constraints ->
        val limit = if (maxHeight == Dp.Unspecified || maxHeight == Dp.Infinity) {
            Constraints.Infinity
        } else {
            maxHeight.roundToPx().coerceAtLeast(0)
        }
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var chosen = emptyList<Placeable>()
        for ((index, form) in forms.withIndex()) {
            val placeables = subcompose(index, form).map { it.measure(loose) }
            if ((placeables.maxOfOrNull { it.height } ?: 0) <= limit) {
                chosen = placeables
                break
            }
        }
        var height = chosen.maxOfOrNull { it.height } ?: 0
        if (keepTallest) {
            tallest.px = maxOf(tallest.px, height)
            height = tallest.px.coerceAtMost(limit)
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else chosen.maxOfOrNull { it.width } ?: 0
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            chosen.forEach { it.placeRelative(0, 0) }
        }
    }
}

/** The tallest a [NovaFirstThatFits] has been; kept outside snapshot state, since it is written while measuring. */
private class NovaTallestHeight {
    var px = 0
}

/**
 * One key/value fact about why the plan is what it is.
 *
 * A definition list rather than four stacked blocks: the keys line up, so the eye reads
 * down one edge instead of hunting for where each one starts. Key, value and detail each
 * wrap whole.
 */
@Composable
internal fun NovaPlaySetupFact(fact: NovaPlaySetupFact) {
    val colors = LocalNovaComposeColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = fact.key.uppercase(),
            color = colors.textMuted,
            style = NovaChromeType.label(fontSize = 9.sp),
            lineHeight = 13.sp,
            // Wrapping in its own column, because a key that ran past it printed itself over the
            // value it is labelling.
            modifier = Modifier.width(NOVA_PLAY_SETUP_FACT_KEY).padding(top = 3.dp, end = 6.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fact.value,
                color = when (fact.tone) {
                    // The same source LaunchProfilePrimaryNotice reads for a healthy
                    // tone. It is not a theme token; a good grade is good in every theme.
                    NovaPlaySetupTone.GOOD -> colorResource(R.color.nova_success)
                    NovaPlaySetupTone.WARN -> colors.warning
                    NovaPlaySetupTone.PLAIN -> colors.textSecondary
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (fact.detail.isNotBlank()) {
                Text(
                    text = fact.detail,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun NovaPlaySetupRule() {
    val colors = LocalNovaComposeColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.divider.copy(alpha = 0.55f)),
    )
}

@Composable
internal fun NovaPlaySetupColumnHead(text: String) {
    val colors = LocalNovaComposeColors.current
    Text(
        text = text.uppercase(),
        color = colors.textMuted,
        style = NovaChromeType.label(fontSize = 9.sp),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * What the alternatives to the focused row would mean, stated as consequences rather than
 * as verbs.
 *
 * This is a legend, not a picker. It describes whichever row currently holds focus, so it
 * is deliberately **not** a focus target: making it one would mean stopping on a thing
 * that exists only to explain the thing you just stopped on, and it would add another
 * stop in the path of every visit down the column. Left and right do nothing in this
 * panel, which is the point -- a short bounded list, with scrolling only when the
 * host advertises enough session choices to need it.
 *
 * It stays tappable, because touch has no cursor for it to follow. A finger can take the
 * card it wants directly instead of pressing a row until the right value comes round.
 *
 * The three pickers this replaces each wrote their own state into one shared strip, ranked
 * by a `when` that always preferred the same one, and only one of the three ever cleared
 * its siblings. Steam Launch was last in that chain, so it worked from a fresh panel and
 * went dead the moment either other row had been touched. There is no picker state now, so
 * there is no precedence to get wrong.
 *
 * Every card says its whole sentence, wrapping; the cards of a row share the tallest one's
 * height. [form] [NovaPlaySetupLegendForm.Current] draws the current choice's card alone, at the
 * legend's full width, for a body with no room for all of them.
 */
@Composable
internal fun NovaPlaySetupComparison(
    title: String,
    options: List<NovaPlaySetupOption>,
    form: NovaPlaySetupLegendForm = NovaPlaySetupLegendForm.All,
    /**
     * Cards per strip row. The default keeps one row; host scope's Default Display puts
     * its four modes in the 2x2 the Polaris Sync sheet taught people, because four
     * mode names crushed into one row leave no room for their status lines.
     */
    perRow: Int = Int.MAX_VALUE,
) {
    val shown = when (form) {
        NovaPlaySetupLegendForm.All -> options
        NovaPlaySetupLegendForm.Current -> options.filter { it.current }
    }
    if (shown.isEmpty()) return
    val cardsPerRow = if (form == NovaPlaySetupLegendForm.Current) 1 else perRow.coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPlaySetupColumnHead(title)
        shown.chunked(cardsPerRow).forEachIndexed { chunkIndex, chunk ->
            if (chunkIndex > 0) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // One height for the row, so a card whose label wrapped does not stand taller
                // than its neighbours.
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                chunk.forEach { option ->
                    NovaPlaySetupComparisonCard(
                        option = option,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaPlaySetupComparisonCard(
    option: NovaPlaySetupOption,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    val actionable = option.onSelect != null && option.enabled
    val currentLabel = stringResource(R.string.nova_panel_current)
    Column(
        modifier = modifier
            .then(
                if (actionable) {
                    // Touch only, and deliberately so -- see the note above.
                    // This used to carry two focusable modifiers as well, which
                    // cost the d-pad two presses to cross one card and left the
                    // inner of the two stops with no click on it at all.
                    Modifier.clickable(role = Role.Button) { option.onSelect?.invoke() }
                } else {
                    Modifier
                }
            )
            .heightIn(min = NovaGameDetailActionHeight)
            .clip(shape)
            // The tile and its hairline whatever the card holds: a fill or a border means focus
            // (R9), and the current choice is the check beside its name. What the host is doing
            // right now, as against what it is set to do, is its sentence in the accent colour.
            .background(surfaces.tile)
            .border(NovaPanelMetrics.Hairline, surfaces.tileBorder, shape)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm)
            .semantics {
                // This was an escaped template, so a screen reader was read the source text,
                // "dollar brace option dot label", for every card.
                contentDescription = novaPlaySetupOptionDescription(option)
                if (option.current) {
                    selected = true
                    stateDescription = currentLabel
                }
            },
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            // Wrapping onto as many lines as it needs: four cards across a handheld leave a
            // label about eleven characters, and a name cut short names nothing (R13).
            Text(
                text = option.label,
                style = type.caption,
                color = if (option.enabled) colors.textPrimary else colors.textMuted,
                fontWeight = if (option.current) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (option.current) NovaCurrentMark()
        }
        if (option.consequence.isNotBlank()) {
            // The whole sentence, wrapping; the card grows to hold it and its row grows with it.
            Text(
                text = option.consequence,
                color = if (option.active && !option.current) colors.accent else colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The place a destination card under the cursor stands for, or nothing.
 *
 * Only while the cards hold focus, and only the card that does: an index the cards no longer
 * have, after the places reloaded, is not guessed at.
 */
internal fun novaPlaySetupPlaceUnderCursor(
    explained: NovaPlaySetupRow,
    places: List<NovaPlaySetupOption>,
    focusedLabel: String,
): NovaPlaySetupOption? =
    if (explained == NovaPlaySetupRow.PLAY_IN && focusedLabel.isNotEmpty()) {
        // By name, not by position. The host can add or drop a Space while a card holds focus, and a
        // remembered position then points at whichever place moved into it: the legend would explain
        // a neighbour, confidently, under a cursor that never left.
        places.firstOrNull { it.label == focusedLabel }
    } else {
        null
    }

/**
 * What the place under the cursor means, while a destination card holds focus.
 *
 * Four cards across a handheld leave their sentences a few words a line, so the drawer says it
 * whole, at its full width, for the one the cursor is on. It describes and does not choose: the
 * card above is the control, and the cards restated as a second set of choices is what
 * LaunchControls was removed for. It is one card in either form of the legend.
 */
@Composable
internal fun NovaPlaySetupPlaceLegend(
    title: String,
    place: NovaPlaySetupOption,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    Column(modifier = Modifier.fillMaxWidth().testTag("nova-play-setup-place-legend")) {
        NovaPlaySetupColumnHead(title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NovaGameDetailActionHeight)
                .clip(shape)
                .background(surfaces.tile)
                .border(NovaPanelMetrics.Hairline, surfaces.tileBorder, shape)
                .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm)
                .semantics { contentDescription = novaPlaySetupOptionDescription(place) },
        ) {
            // The whole name, on as many lines as it takes.
            Text(
                text = place.label,
                style = novaPanelType.caption,
                color = if (place.enabled) colors.textPrimary else colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
            if (place.consequence.isNotBlank()) {
                Text(
                    text = place.consequence,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** What a legend card says to a screen reader: its name, then what choosing it would mean. */
internal fun novaPlaySetupOptionDescription(option: NovaPlaySetupOption): String =
    listOf(option.label, option.consequence).filter { it.isNotBlank() }.joinToString(". ")

/**
 * A row's caption, led by [note] ("Set for this game") while [setHere] says its value was chosen
 * here rather than answered by the host. The words replace the accent bar the row used to draw,
 * which read as focus or as the current value (R9).
 */
internal fun novaPlaySetupSetHereCaption(caption: String, setHere: Boolean, note: String): String =
    if (setHere) listOf(note, caption).filter { it.isNotBlank() }.joinToString(" · ") else caption

/**
 * The most a pinned legend may take of a body [fitHeight] tall: all of it but the room a row and
 * a half need, so the row under the cursor is always there to see.
 */
internal fun novaPlaySetupLegendCap(fitHeight: Dp, columnHead: Boolean): Dp {
    if (fitHeight == Dp.Unspecified || fitHeight <= 0.dp) return Dp.Unspecified
    val kept = NOVA_PLAY_SETUP_ROWS_FLOOR + NOVA_PLAY_SETUP_LEGEND_GAP +
        (if (columnHead) NOVA_PLAY_SETUP_COLUMN_HEAD else 0.dp)
    return (fitHeight - kept).coerceAtLeast(0.dp)
}
/** The resolved plan, as one readable statement plus the facts behind it. */
internal data class NovaPlaySetupPlan(
    val mode: String,
    val lines: List<String>,
    val facts: List<NovaPlaySetupFact>,
)

internal data class NovaPlaySetupFact(
    val key: String,
    val value: String,
    val detail: String = "",
    val tone: NovaPlaySetupTone = NovaPlaySetupTone.PLAIN,
)

internal enum class NovaPlaySetupTone { PLAIN, GOOD, WARN }

internal data class NovaPlaySetupOption(
    val label: String,
    val consequence: String,
    val current: Boolean = false,
    val enabled: Boolean = true,
    /**
     * In effect this session without being the saved choice — the host fell back or a
     * relaunch is pending. Drawn as an accent edge, never as the selection tint.
     */
    val active: Boolean = false,
    /** Null makes the card explanatory rather than selectable. */
    val onSelect: (() -> Unit)? = null,
)

/**
 * Which subject Play Setup is showing: the game the panel opened for, or the host
 * defaults every game inherits. One panel, two scopes, flipped by the header pill or Y —
 * because the Every Game answer used to live in a sheet behind a row, where the person
 * comparing "this game" against "everything else" could not see both at once.
 */
internal enum class NovaPlaySetupScope { THIS_GAME, EVERY_GAME }

/**
 * The things Play Setup can change, in the order they are drawn.
 *
 * Kept in one stable order. Compact hosts still fit without scrolling; richer host catalogs
 * can add rows and use the wide panel's measured scroll fallback rather than hiding a choice.
 *
 * Resolution, frame rate, and video codec have their own rows. Codec is a client choice;
 * the encoder row selects the host backend, except when PyroWave supplies its own encoder.
 *
 * The HOST_ rows are Every Game's four: the Polaris Sync sheet's sections in the same
 * shape, so the scope pill changes the subject and nothing else.
 */
internal enum class NovaPlaySetupRow {
    PLAY_IN,
    WHERE_IT_RUNS,
    RESOLUTION,
    FRAME_RATE,
    VIDEO_CODEC,
    ENCODER,
    FACE_BUTTONS,
    TUNING,
    STEAM_LAUNCH,
    HOST_DEFAULT_DISPLAY,
    HOST_SCREEN_TO_ADD,
    HOST_SCREEN_SCALE,
    HOST_PROFILE,
    HOST_KEEP_IN_STEP,
}

/**
 * One row: what it is called, what it currently reads, and what the alternatives mean.
 *
 * The options travel with the row rather than in a state the row has to open, because the
 * strip is a legend for whatever holds focus and a legend cannot be opened or closed.
 * [stripTitle] is a conditional sentence -- "If you changed where it runs" -- rather than a
 * noun, so the strip reads as an explanation of a thing not yet done.
 */
internal data class NovaPlaySetupRowState(
    val row: NovaPlaySetupRow,
    val label: String,
    val caption: String,
    val value: String,
    val stripTitle: String,
    val options: List<NovaPlaySetupOption>,
    val enabled: Boolean = true,
    /**
     * This row holds a choice made here rather than the answer the host would have given. It is
     * not drawn as a mark: a fill, a border or an edge bar would read as focus or as current (R9),
     * so where the caption does not already say it, the builder's caption does ("Set for this
     * game").
     */
    val overridden: Boolean = false,
    val optionsPerRow: Int = Int.MAX_VALUE,
    /** The options are a scale, such as frame rates, so Left and Right stop at its ends (R1). */
    val ordered: Boolean = false,
    /**
     * The choices outgrow the row, so A opens their page instead of stepping through them in
     * place: Where It Runs, and the host's Default Display, once the host offers more modes than
     * the classic pair.
     */
    val opensPage: Boolean = false,
)

/**
 * One of Play Setup's rows, for this game or for every game.
 *
 * A row that holds one of a set of values changes in place (R1): Left and Right step through the
 * options, A steps forward, and a tap on an arrow or on the row acts at once (R12). Its legend
 * under the rows sets every option out with what it would mean, so the row draws a cycler rather
 * than segments, which would draw the same choices a second time. A row whose choices outgrow
 * the row opens its page ([NovaPlaySetupRowState.opensPage]); a row with no current value to step
 * through, such as the host profile's verbs, acts once on A through [onAdvance]. Whatever changes
 * or takes focus points the legend at this row through [onExplain].
 *
 * A value the row's option does not name, such as the frame rate Auto picks, is read after the
 * caption, so nothing the old value column said is lost.
 */
@Composable
internal fun NovaPlaySetupSettingRow(
    state: NovaPlaySetupRowState,
    onExplain: (NovaPlaySetupRow) -> Unit,
    onAdvance: (NovaPlaySetupRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val explain by rememberUpdatedState(onExplain)
    val advance by rememberUpdatedState(onAdvance)
    val row = state.row
    val options = state.options
    val currentIndex = options.indexOfFirst { it.current }
    val selectable = options.count { it.enabled && it.onSelect != null }
    val followsFocus = Modifier.onFocusChanged { if (it.hasFocus) explain(row) }
    when {
        state.opensPage -> NovaRow(
            title = state.label,
            caption = state.caption.takeIf { it.isNotBlank() },
            trailing = if (state.value.isBlank()) NovaRowTrailing.Opens else NovaRowTrailing.Value(state.value),
            disabledReason = if (state.enabled) null else state.caption,
            onClick = {
                explain(row)
                advance(row)
            },
            modifier = modifier.padding(bottom = NovaPanelMetrics.RowGap).then(followsFocus),
        )
        currentIndex >= 0 && options.size > 1 -> {
            val shownValue = state.value.takeIf { it.isNotBlank() && it != options[currentIndex].label }
            NovaValueRow(
                title = state.label,
                // By position, so two options that read alike are still two options. One the host
                // will not take stays in the list for the legend, and the row steps over it.
                options = options.mapIndexed { index, option ->
                    NovaOption(
                        value = index,
                        label = option.label,
                        disabledReason = if (option.enabled && option.onSelect != null) null else option.consequence,
                    )
                },
                current = currentIndex,
                onChange = { index ->
                    explain(row)
                    options.getOrNull(index)?.onSelect?.invoke()
                },
                caption = listOfNotNull(state.caption.takeIf { it.isNotBlank() }, shownValue)
                    .joinToString(" \u00b7 ")
                    .takeIf { it.isNotBlank() },
                style = NovaValueStyle.Cycler,
                ordered = state.ordered,
                enabled = state.enabled && selectable > 1,
                modifier = modifier.padding(bottom = NovaPanelMetrics.RowGap).then(followsFocus),
            )
        }
        else -> NovaSteamChoiceRow(
            label = state.label,
            caption = state.caption,
            enabled = state.enabled,
            value = state.value,
            onClick = {
                explain(row)
                advance(row)
            },
            onFocused = { explain(row) },
            // A row that cannot act now keeps a stop, so what its caption says can be read.
            focusableWhenDisabled = true,
            modifier = modifier,
        )
    }
}

/**
 * Where this game opens, as the one control that sets it.
 *
 * The places used to be drawn twice: a Change Space row whose A cycled through them, and the
 * legend under the rows restating the same places as cards. Two ways to draw one choice is what
 * LaunchControls was removed for. The cards are the control now, each a stop on the d-pad and a
 * tap target, and the place the game opens in takes first focus so the cursor starts where the
 * game will run. The legend below goes back to explaining only the rows. [status] is said above
 * the cards only when there is something to say: a change in flight, or why it failed.
 */
@Composable
internal fun NovaPlaySetupDestinations(
    title: String,
    status: String,
    options: List<NovaPlaySetupOption>,
    /**
     * Focus marks for each card: whether it is the one the page opens on (the place the game
     * opens in, or else the first it can open in), and where focus returns to it after a page
     * above pops. The page's host settles focus from these, so no card asks for focus itself.
     */
    focusModifier: (option: NovaPlaySetupOption, initial: Boolean) -> Modifier = { _, _ -> Modifier },
    /** Told which card took focus, so the legend below describes that place rather than a row nobody is on. */
    onFocused: (Int) -> Unit = {},
) {
    val colors = LocalNovaComposeColors.current
    val focusIndex = options.indexOfFirst { it.current && it.enabled && it.onSelect != null }
        .takeIf { it >= 0 }
        ?: options.indexOfFirst { it.enabled && it.onSelect != null }
    Column(modifier = Modifier.fillMaxWidth().testTag("nova-play-setup-destinations")) {
        NovaPlaySetupColumnHead(title)
        if (status.isNotBlank()) {
            // A change in flight or why it failed, said whole.
            Text(
                text = status,
                style = novaPanelType.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = NovaPanelMetrics.SpaceSm),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            options.forEachIndexed { index, option ->
                NovaSteamChoiceRow(
                    label = option.label,
                    caption = option.consequence,
                    enabled = option.enabled,
                    onClick = option.onSelect,
                    current = option.current,
                    onFocused = { onFocused(index) },
                    describeCaption = true,
                    // A place the game cannot open in still has a reason to read.
                    focusableWhenDisabled = true,
                    modifier = focusModifier(option, index == focusIndex).weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** The dissolve at the bottom of the scrolling rows: enough to say more follows, less than a row. */
private val NOVA_PLAY_SETUP_ROWS_FADE = 18.dp

/** What the rows keep however tall the legend is: a row and a half, so the list reads as a list. */
private val NOVA_PLAY_SETUP_ROWS_FLOOR = 80.dp

/** Drawn heights, kept beside the drawing so the two cannot drift apart unnoticed. */
private val NOVA_PLAY_SETUP_COLUMN_HEAD = 16.dp
private val NOVA_PLAY_SETUP_LEGEND_GAP = 4.dp

/** Between the plan and the rows under it. */
private val NOVA_PLAY_SETUP_READ_GAP = 18.dp

/** Below this the two columns stack; a phone has no room to put them side by side. */
private val NOVA_PLAY_SETUP_TWO_COLUMN_MIN = 640.dp

/** The read column is fixed so the choice rows keep a stable width as values change. */
private val NOVA_PLAY_SETUP_READ_WIDTH = 246.dp
private val NOVA_PLAY_SETUP_GUTTER = 22.dp
private val NOVA_PLAY_SETUP_FACT_KEY = 104.dp

/** The whole plan, where it is drawn in the body. */
internal const val NOVA_PLAY_SETUP_READ_TAG = "nova-play-setup-read"

/** The plan's row, where the body shows the plan as one row that opens its page. */
internal const val NOVA_PLAY_SETUP_PLAN_ROW_TAG = "nova-play-setup-plan-row"

/**
 * Turn the launch profile summary into what the left column reads.
 *
 * The summary's lines carry their own prefixes -- "Requested: ", "Selected: ", "Limited
 * by: ", "Last: " -- because they were written to stand alone in a list. Here the fact's
 * key already says which is which, so the prefix would print the word twice.
 *
 * Three of these fields have never been drawn anywhere. `historyLines` is how the last
 * session actually went, `requestedLine` is what was asked for as against what was
 * granted, and `primaryLaunchLabel` is the resolved verb. All three are computed on
 * every launch, and "how did it go last time" is the single most useful input to "how do
 * I want to play".
 */
internal fun novaPlaySetupPlan(
    modeLabel: String,
    lines: List<String>,
    summary: NovaLaunchProfileSummary?,
    lastSessionKey: String,
    limitedByKey: String,
    askedKey: String,
    profileKey: String,
    grantedFormat: String,
    hostFacts: List<NovaPlaySetupFact> = emptyList(),
): NovaPlaySetupPlan {
    val facts = mutableListOf<NovaPlaySetupFact>()
    if (summary != null) {
        val healthy = summary.noticeTone == NovaLaunchProfileNoticeTone.HEALTHY
        summary.historyLines.firstOrNull { it.startsWith("Last:") }
            ?.let { novaStripLabel(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let {
                facts += NovaPlaySetupFact(
                    key = lastSessionKey,
                    value = it,
                    tone = if (healthy) NovaPlaySetupTone.GOOD else NovaPlaySetupTone.PLAIN,
                )
            }

        novaStripLabel(summary.limitingLine).takeIf { it.isNotBlank() }?.let {
            facts += NovaPlaySetupFact(
                key = limitedByKey,
                value = it,
                // The evidence, not just the category. "Host Render" on its own is the
                // residual branch of Polaris' classifier -- it fires once network,
                // encoder, decoder and capture are each ruled out -- so without the
                // measurement behind it there is nothing a person can act on.
                detail = summary.noticeDetail,
                tone = NovaPlaySetupTone.WARN,
            )
        }

        summary.profileLabel.takeIf { it.isNotBlank() }?.let {
            facts += NovaPlaySetupFact(
                key = profileKey,
                value = it,
                detail = summary.profileDescription,
                tone = NovaPlaySetupTone.PLAIN,
            )
        }

        val asked = novaStripLabel(summary.requestedLine)
        val granted = novaStripLabel(summary.selectedLine)
        if (asked.isNotBlank()) {
            facts += NovaPlaySetupFact(
                key = askedKey,
                value = asked,
                // The why rides with the grant: "Granted: Recovery profile / 30 FPS ·
                // Held by History Safe Profile" is the whole story in one fact.
                detail = listOfNotNull(
                    granted.takeIf { it.isNotBlank() }?.let { grantedFormat.format(it) },
                    summary.grantHoldReason.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }

        summary.freshnessLine.takeIf { it.isNotBlank() && summary.profileLabel.isBlank() }?.let {
            facts += NovaPlaySetupFact(key = profileKey, value = it)
        }
    }
    // The host's answer sits beside the game's, because the per-game choice outranks it
    // and a hierarchy is only legible when both ends are on screen.
    facts += hostFacts
    return NovaPlaySetupPlan(mode = modeLabel, lines = lines, facts = facts)
}

/**
 * Drop a "Key: " prefix that the fact's own key is about to say again.
 *
 * Only up to the first colon, and only when it is close enough to the start to be a
 * label -- a value like "Recovery active from last session · 1 hr ago" has no prefix to
 * remove, and one like "12:30" must not lose its first half.
 */
private fun novaStripLabel(line: String): String {
    val colon = line.indexOf(':')
    if (colon !in 1..NOVA_PLAY_SETUP_MAX_LABEL) return line.trim()
    return line.substring(colon + 1).trim()
}

/** Longer than any of the summary's own prefixes, shorter than a sentence. */
private const val NOVA_PLAY_SETUP_MAX_LABEL = 14

/** The same prefix strip, for a row value that shows a summary line. */
internal fun novaPlaySetupValue(line: String): String = novaStripLabel(line)
