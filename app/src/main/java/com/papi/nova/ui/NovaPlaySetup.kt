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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaChromeType
import com.papi.nova.ui.compose.NovaRevealingText
import com.papi.nova.ui.compose.NovaRadius

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
 */
@Composable
internal fun NovaPlaySetupBody(
    plan: NovaPlaySetupPlan,
    rows: @Composable () -> Unit,
    comparison: (@Composable () -> Unit)? = null,
    introMaxLines: Int = 2,
    /** The read column's head; host scope reads differently than a game does. */
    readTitle: String? = null,
    /**
     * The height the body has. Side by side, the read column fits itself into it: its lines
     * are not a stop on the d-pad, so the panel's scroll, which follows focus, could never bring
     * a cut line into view.
     */
    fitHeight: Dp = Dp.Unspecified,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A phone in portrait has no room for two columns, and stacking them keeps the
        // same reading order: read the plan, then act on it.
        val stacked = maxWidth < NOVA_PLAY_SETUP_TWO_COLUMN_MIN
        // Given its height, the body keeps the legend in sight and scrolls the rows above it.
        val pinned = fitHeight != Dp.Unspecified && fitHeight > 0.dp
        if (stacked && pinned) {
            Column(modifier = Modifier.fillMaxWidth().height(fitHeight)) {
                NovaPlaySetupRowsRegion {
                    NovaPlaySetupReadColumn(plan, introMaxLines, Modifier.fillMaxWidth(), readTitle)
                    Spacer(modifier = Modifier.height(18.dp))
                    NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_what_you_can_change))
                    rows()
                }
                NovaPlaySetupPinnedLegend(comparison, novaPlaySetupLegendCap(fitHeight, columnHead = false))
            }
        } else if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NovaPlaySetupReadColumn(plan, introMaxLines, Modifier.fillMaxWidth(), readTitle)
                Spacer(modifier = Modifier.height(18.dp))
                NovaPlaySetupActColumn(rows, comparison, Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().then(if (pinned) Modifier.height(fitHeight) else Modifier)) {
                NovaPlaySetupReadColumn(
                    plan = plan,
                    introMaxLines = introMaxLines,
                    modifier = Modifier.width(NOVA_PLAY_SETUP_READ_WIDTH).fillMaxHeight(),
                    readTitle = readTitle,
                    fitHeight = fitHeight,
                )
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
 * What will happen, and where each part of it came from. Read, never operated.
 *
 * Given a height, it fits into it. Nothing here is a stop on the d-pad, so the panel's scroll,
 * which follows focus, could never reach a line cut off at the bottom: on the Retroid a Space
 * launch printed its host profile under the hint bar with no way to see it. The column measures
 * its own text and gives up prose first, then the detail under each fact, a line at a time, and
 * never a fact's value.
 */
@Composable
private fun NovaPlaySetupReadColumn(
    plan: NovaPlaySetupPlan,
    introMaxLines: Int,
    modifier: Modifier = Modifier,
    readTitle: String? = null,
    fitHeight: Dp = Dp.Unspecified,
) {
    val colors = LocalNovaComposeColors.current
    BoxWithConstraints(modifier = modifier) {
        val fit = rememberNovaPlaySetupReadFit(plan, introMaxLines, maxWidth, fitHeight)
        // The column fits itself by cutting lines, and nothing in it takes the cursor, so what it
        // cut could not be read at all. Its texts take turns instead: one at a time, top to
        // bottom, each shows the rest of itself once and hands on. A round in which nothing had
        // anything hidden is the last; otherwise the column rests and goes round again.
        val items = plan.lines.size + plan.facts.size
        var turn by remember(plan, fit) { mutableIntStateOf(-1) }
        var round by remember(plan, fit) { mutableIntStateOf(0) }
        var revealedThisRound by remember(plan, fit) { mutableStateOf(false) }
        LaunchedEffect(plan, fit, round) {
            if (items == 0) return@LaunchedEffect
            delay(if (round == 0) NOVA_PLAY_SETUP_READ_FIRST_TURN_MS else NOVA_PLAY_SETUP_READ_REST_MS)
            revealedThisRound = false
            turn = 0
        }
        val played: (Int, Boolean) -> Unit = { index, revealed ->
            if (turn == index) {
                if (revealed) revealedThisRound = true
                when (val next = novaPlaySetupNextTurn(index, items, revealedThisRound)) {
                    NOVA_PLAY_SETUP_TURN_REST -> { turn = -1; round++ }
                    NOVA_PLAY_SETUP_TURN_DONE -> turn = -1
                    else -> turn = next
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            NovaPlaySetupColumnHead(readTitle ?: stringResource(R.string.nova_play_setup_what_will_happen))
            Text(
                text = plan.mode,
                color = colors.textPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plan.lines.forEachIndexed { index, line ->
                NovaRevealingText(
                    text = line,
                    highlighted = turn == index,
                    passes = 1,
                    onPlayed = { revealed -> played(index, revealed) },
                    // The last line is the part nobody asked for but everyone wants to know:
                    // whether anything outside this game is about to be touched.
                    color = if (index == plan.lines.lastIndex) colors.textMuted else colors.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = fit.lineMaxLines.getOrElse(index) { introMaxLines },
                )
            }

            if (plan.facts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                NovaPlaySetupRule()
                plan.facts.forEachIndexed { index, fact ->
                    val item = plan.lines.size + index
                    NovaPlaySetupFact(
                        fact = fact,
                        detailMaxLines = fit.detailMaxLines.getOrElse(index) { Int.MAX_VALUE },
                        revealing = turn == item,
                        onPlayed = { revealed -> played(item, revealed) },
                    )
                }
            }
        }
    }
}

/** The line limits that fit this plan into [fitHeight]; everything it needs when there is no height to fit. */
@Composable
private fun rememberNovaPlaySetupReadFit(
    plan: NovaPlaySetupPlan,
    introMaxLines: Int,
    width: Dp,
    fitHeight: Dp,
): NovaPlaySetupReadFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(plan, introMaxLines, width, fitHeight, density, base) {
        val unfitted = NovaPlaySetupReadFit(
            lineMaxLines = List(plan.lines.size) { introMaxLines },
            detailMaxLines = List(plan.facts.size) { Int.MAX_VALUE },
        )
        if (fitHeight == Dp.Unspecified || fitHeight <= 0.dp || width == Dp.Infinity || width <= NOVA_PLAY_SETUP_FACT_KEY) {
            return@remember unfitted
        }
        with(density) {
            fun measure(text: String, style: TextStyle, maxWidth: Dp): NovaPlaySetupMeasuredText {
                if (text.isBlank()) return NovaPlaySetupMeasuredText(emptyList())
                val layout = measurer.measure(
                    text = text,
                    style = style,
                    constraints = Constraints(maxWidth = maxWidth.roundToPx().coerceAtLeast(1)),
                )
                return NovaPlaySetupMeasuredText(List(layout.lineCount) { layout.getLineBottom(it).roundToInt() })
            }
            val lineStyle = base.merge(TextStyle(fontSize = 14.sp, lineHeight = 19.sp))
            val valueStyle = base.merge(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            val detailStyle = base.merge(TextStyle(fontSize = 11.sp, lineHeight = 15.sp))
            val valueWidth = width - NOVA_PLAY_SETUP_FACT_KEY
            val fixed = NOVA_PLAY_SETUP_COLUMN_HEAD + NOVA_PLAY_SETUP_MODE_LINE +
                (if (plan.facts.isNotEmpty()) NOVA_PLAY_SETUP_RULE_BLOCK else 0.dp)
            novaPlaySetupFitReadColumn(
                available = (fitHeight - NOVA_PLAY_SETUP_SLACK).roundToPx(),
                fixed = fixed.roundToPx(),
                lineGap = 6.dp.roundToPx(),
                lines = plan.lines.map { measure(it, lineStyle, width) },
                lineCap = introMaxLines,
                factChrome = 10.dp.roundToPx(),
                detailGap = 4.dp.roundToPx(),
                keyMin = NOVA_PLAY_SETUP_FACT_KEY_MIN.roundToPx(),
                facts = plan.facts.map {
                    NovaPlaySetupMeasuredFact(
                        value = measure(it.value, valueStyle, valueWidth),
                        detail = measure(it.detail, detailStyle, valueWidth),
                    )
                },
            )
        }
    }
}

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
    comparison: (@Composable () -> Unit)?,
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
                comparison()
            }
        }
    }
}

/**
 * The part of the body that scrolls: the rows, and on a narrow screen the plan above them.
 *
 * It takes what its content needs and no more, so a short list keeps its legend directly under
 * it rather than across a gap at the bottom of the panel. Focus brings a row into view here the
 * way it does in the panel's own scroll.
 */
@Composable
private fun ColumnScope.NovaPlaySetupRowsRegion(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    // More than the gap under the last row. Focus brings a row's own bounds into view and not
    // the gap below it, so at the last row the scroll could still move by that gap, and "can
    // scroll" kept the dissolve drawn over the final row as if another followed.
    val slack = with(LocalDensity.current) { NOVA_PLAY_SETUP_ROWS_FADE.toPx() }
    val moreBelow = scroll.maxValue - scroll.value > slack
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            // A slim band. The panel's own is as tall as a row, and focus brings a row to the
            // bottom edge of this region, so the full band dissolved the row under the cursor.
            .novaFadeAtCut(moreBelow, band = NOVA_PLAY_SETUP_ROWS_FADE)
            .verticalScroll(scroll)
            .testTag("nova-play-setup-rows"),
    ) {
        content()
    }
}

/**
 * The legend, under the rows and outside their scroll.
 *
 * It is laid out before the rows, which take what is left, so it is held to [cap]. Every Game's
 * Default Display is seven modes in three rows of cards, and uncapped it took the whole body and
 * left the rows it explains a few pixels tall.
 */
@Composable
private fun NovaPlaySetupPinnedLegend(comparison: (@Composable () -> Unit)?, cap: Dp) {
    if (comparison == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NOVA_PLAY_SETUP_LEGEND_GAP)
            .then(if (cap != Dp.Unspecified) Modifier.heightIn(max = cap).clipToBounds() else Modifier)
            .testTag("nova-play-setup-legend"),
    ) {
        comparison()
    }
}

/**
 * One key/value fact about why the plan is what it is.
 *
 * A definition list rather than four stacked blocks: the keys line up, so the eye reads
 * down one edge instead of hunting for where each one starts.
 */
@Composable
private fun NovaPlaySetupFact(
    fact: NovaPlaySetupFact,
    detailMaxLines: Int = Int.MAX_VALUE,
    /** It is this fact's turn to show the part of its detail the fit cut off. */
    revealing: Boolean = false,
    onPlayed: ((revealed: Boolean) -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = fact.key.uppercase(),
            color = colors.textMuted,
            style = NovaChromeType.label(fontSize = 9.sp),
            lineHeight = 13.sp,
            // Two lines rather than one, because a key that runs past its column prints
            // itself over the value it is labelling.
            maxLines = 2,
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
                NovaRevealingText(
                    text = fact.detail,
                    highlighted = revealing,
                    passes = 1,
                    onPlayed = onPlayed,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = detailMaxLines,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (revealing) {
                // Nothing to show, so the turn passes straight on.
                LaunchedEffect(Unit) { onPlayed?.invoke(false) }
            }
        }
    }
}

@Composable
private fun NovaPlaySetupRule() {
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
 */
@Composable
internal fun NovaPlaySetupComparison(
    title: String,
    options: List<NovaPlaySetupOption>,
    /**
     * One line rather than two once the column grows beyond its compact shape.
     *
     * The panel has to fit a ~325dp landscape viewport without scrolling. Four rows at the
     * 48dp accessible floor plus their gaps consume most of it, and the legend has to live
     * in what is left. The legend gives up extra lines before the panel uses its scrolling
     * fallback.
     */
    consequenceMaxLines: Int = 2,
    /**
     * Cards per strip row. The default keeps one row; host scope's Default Display puts
     * its four modes in the 2x2 the Polaris Sync sheet taught people, because four
     * mode names crushed into one row leave no room for their status lines.
     */
    perRow: Int = Int.MAX_VALUE,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPlaySetupColumnHead(title)
        options.chunked(perRow.coerceAtLeast(1)).forEachIndexed { chunkIndex, chunk ->
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
                        consequenceMaxLines = consequenceMaxLines,
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
    consequenceMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    val actionable = option.onSelect != null && option.enabled
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
            .background(if (option.current) colors.accentSurface else surfaces.tile)
            .border(
                1.dp,
                when {
                    option.current -> colors.accent.copy(alpha = 0.58f)
                    // What the host is actually doing right now, as against what it is
                    // set to do — the same two-state drawing the sync sheet's mode grid
                    // uses, so a fallback reads the same on both surfaces.
                    option.active -> colors.accent.copy(alpha = 0.34f)
                    else -> surfaces.tileBorder
                },
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                // This was an escaped template, so a screen reader was read the source text,
                // "dollar brace option dot label", for every card.
                contentDescription = novaPlaySetupOptionDescription(option)
                if (option.current) selected = true
            },
    ) {
        Text(
            text = option.label,
            color = if (option.enabled) colors.textPrimary else colors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            // Two, because four cards across a handheld leave a label about eleven characters,
            // and a name cut short names nothing. The host's titles and a translation can both
            // run longer than that.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        NovaRevealingText(
            text = option.consequence,
            // The cursor never stops on a legend card, so its highlight is being the current
            // choice. That one plays its whole sentence twice and rests; the others are a press
            // of A or a tap away from being it.
            highlighted = option.current,
            passes = 2,
            color = if (option.active && !option.current) colors.accent else colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            // Bounded, so a long consequence cannot push the card past the cut.
            // And held at that height: the legend changes with the row under the cursor, and a
            // legend that grew and shrank would resize the rows above it on every move.
            minLines = consequenceMaxLines,
            maxLines = consequenceMaxLines,
            modifier = Modifier.padding(top = 4.dp),
        )
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
    focusedIndex: Int,
): NovaPlaySetupOption? =
    if (explained == NovaPlaySetupRow.PLAY_IN) places.getOrNull(focusedIndex) else null

/**
 * What the place under the cursor means, while a destination card holds focus.
 *
 * Four cards across a handheld cut their sentence short, so the drawer says it whole for the one
 * the cursor is on. It describes and does not choose: the card above is the control, and the
 * cards restated as a second set of choices is what LaunchControls was removed for. It keeps the
 * shape and height of the legend it stands in for, so the rows above do not move when the
 * cursor goes from a card to a row.
 */
@Composable
internal fun NovaPlaySetupPlaceLegend(
    title: String,
    place: NovaPlaySetupOption,
    consequenceMaxLines: Int = 2,
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
                .border(1.dp, surfaces.tileBorder, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = novaPlaySetupOptionDescription(place) },
        ) {
            Text(
                text = place.label,
                color = if (place.enabled) colors.textPrimary else colors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NovaRevealingText(
                text = place.consequence,
                // The cursor is on this place's card, so its whole sentence is what it is here for.
                highlighted = true,
                passes = 2,
                color = colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                minLines = consequenceMaxLines,
                maxLines = consequenceMaxLines,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What a legend card says to a screen reader: its name, then what choosing it would mean. */
internal fun novaPlaySetupOptionDescription(option: NovaPlaySetupOption): String =
    listOf(option.label, option.consequence).filter { it.isNotBlank() }.joinToString(". ")

/**
 * Whether a press on a row acts, or only moves the legend to it.
 *
 * A row's alternatives are shown in the legend, and the legend follows focus. With a controller
 * the row under the cursor is always the one being explained, so A changes a value whose
 * alternatives are on screen. A finger has no cursor: it lands on a row the legend is not
 * showing, and the press used to change that row's value unseen. So the first press on a row
 * that does not hold focus brings the legend to it, and a press on the row that does changes it.
 */
internal fun novaPlaySetupPressActs(firstPressFocuses: Boolean, heldFocus: Boolean): Boolean =
    !firstPressFocuses || heldFocus

/** The column rests and goes round again. */
internal const val NOVA_PLAY_SETUP_TURN_REST = -1

/** Nothing in the column was cut, so there is nothing to go round for. */
internal const val NOVA_PLAY_SETUP_TURN_DONE = -2

/**
 * Whose turn it is after [index] has had its own, among [items] texts in the read column.
 *
 * The next one down; after the last, a rest and another round if anything this round actually
 * had text hidden, and an end if nothing did, so a column that fits never stirs.
 */
internal fun novaPlaySetupNextTurn(index: Int, items: Int, revealedThisRound: Boolean): Int = when {
    index + 1 < items -> index + 1
    revealedThisRound -> NOVA_PLAY_SETUP_TURN_REST
    else -> NOVA_PLAY_SETUP_TURN_DONE
}

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

/**
 * How many lines a legend card may use once the legend is pinned under scrolling rows.
 *
 * Pinned, the legend no longer competes with every row, only with the rows that should stay in
 * view above it. Three is what the panel keeps: past that the rows scroll, so a fourth, fifth or
 * seventh row costs the legend nothing, where unpinned each one took a line and then the legend.
 */
internal fun novaPlaySetupPinnedLegendLines(availableHeight: Dp, rowCount: Int): Int =
    novaPlaySetupConsequenceLines(availableHeight, rowCount.coerceAtMost(NOVA_PLAY_SETUP_ROWS_KEPT_IN_VIEW))

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
 * More Launch Settings is not among them. It held resolution, and behind it codec and
 * bitrate -- which are consequences of a resolution, not choices anyone makes separately.
 * Resolution is a row of its own now, and the rest is stated in the read column where the
 * other consequences already are.
 *
 * The HOST_ rows are Every Game's four: the Polaris Sync sheet's sections in the same
 * shape, so the scope pill changes the subject and nothing else.
 */
internal enum class NovaPlaySetupRow {
    PLAY_IN,
    WHERE_IT_RUNS,
    RESOLUTION,
    FRAME_RATE,
    ENCODER,
    FACE_BUTTONS,
    TUNING,
    STEAM_LAUNCH,
    HOST_DEFAULT_DISPLAY,
    HOST_PROFILE,
    HOST_KEEP_IN_STEP,
}

/**
 * This Game | Every Game, at the panel header's edge. Touch takes a segment directly;
 * Y flips, and its glyph rides with the pill because the key exists only while this
 * panel is open — the bottom hint bar names the keys every screen has, not this one.
 */
@Composable
internal fun NovaPlaySetupScopePill(
    scope: NovaPlaySetupScope,
    onSelected: (NovaPlaySetupScope) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.nova_play_setup_scope_key),
            color = colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(NovaRadius.pill))
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.pill))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NovaRadius.pill))
                .background(surfaces.tile)
                .border(1.dp, surfaces.tileBorder, RoundedCornerShape(NovaRadius.pill)),
        ) {
            NovaPlaySetupScopeSegment(
                label = stringResource(R.string.nova_play_setup_scope_this_game),
                isSelected = scope == NovaPlaySetupScope.THIS_GAME,
                onClick = { onSelected(NovaPlaySetupScope.THIS_GAME) },
            )
            NovaPlaySetupScopeSegment(
                label = stringResource(R.string.nova_play_setup_every_game),
                isSelected = scope == NovaPlaySetupScope.EVERY_GAME,
                onClick = { onSelected(NovaPlaySetupScope.EVERY_GAME) },
            )
        }
    }
}

@Composable
private fun NovaPlaySetupScopeSegment(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val description = stringResource(R.string.nova_play_setup_scope_cd, label)
    Text(
        text = label,
        color = if (isSelected) colors.onAccent else colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(NovaRadius.pill))
            .background(if (isSelected) colors.accent else surfaces.tile)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (isSelected) selected = true
            }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
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
     * This row holds a choice made here rather than the answer the host would have given.
     * Drawn as the selection tint and the accent edge, because a setting that will change
     * the next launch should not look identical to one that is simply reporting.
     */
    val overridden: Boolean = false,
)

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
    autoFocus: Boolean,
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
            Text(
                text = status,
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
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
                    selected = option.current,
                    autoFocus = autoFocus && index == focusIndex,
                    onFocused = { onFocused(index) },
                    describeCaption = true,
                    // A place the game cannot open in still has a reason to read.
                    focusableWhenDisabled = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * How much of the legend the column can afford, and how much prose the plan can.
 *
 * Both are a function of how many rows the host produced: a host advertising a display
 * planner adds Resolution, and those 53dp come out of whatever is below. Rather than pick
 * one answer for every case and truncate the rest, the panel measures its body and this
 * spends what is actually there -- three lines of consequence where there is room for
 * three, one where there is room for one.
 */
internal fun novaPlaySetupConsequenceLines(
    availableHeight: Dp,
    rowCount: Int,
    /** Where this game opens is drawn above the rows, with a head of its own. */
    destinations: Boolean = false,
): Int {
    if (availableHeight <= 0.dp) return 2
    val used = NOVA_PLAY_SETUP_COLUMN_HEAD +
        (if (destinations) NOVA_PLAY_SETUP_COLUMN_HEAD + NOVA_PLAY_SETUP_DESTINATION_STRIDE else 0.dp) +
        (NOVA_PLAY_SETUP_ROW_STRIDE * rowCount) +
        NOVA_PLAY_SETUP_LEGEND_GAP +
        NOVA_PLAY_SETUP_COLUMN_HEAD +
        NOVA_PLAY_SETUP_CARD_CHROME +
        NOVA_PLAY_SETUP_SLACK
    val room = availableHeight - used
    if (room <= 0.dp) return 1
    return (room / NOVA_PLAY_SETUP_CONSEQUENCE_LINE).toInt().coerceIn(1, 3)
}

/** The plan's opening sentences get the same treatment, from the same measurement. */
internal fun novaPlaySetupIntroLines(availableHeight: Dp, factCount: Int): Int {
    if (availableHeight <= 0.dp) return 2
    val used = NOVA_PLAY_SETUP_COLUMN_HEAD + NOVA_PLAY_SETUP_MODE_LINE +
        NOVA_PLAY_SETUP_RULE_BLOCK + (NOVA_PLAY_SETUP_FACT * factCount)
    val room = availableHeight - used
    if (room <= 0.dp) return 1
    return (room / NOVA_PLAY_SETUP_INTRO_LINE).toInt().coerceIn(1, 4)
}

/** A text's bottom edge after each of its lines, in pixels, as measured at its column width. */
internal class NovaPlaySetupMeasuredText(private val lineBottoms: List<Int>) {
    val lineCount: Int get() = lineBottoms.size

    fun height(maxLines: Int): Int =
        if (lineBottoms.isEmpty() || maxLines <= 0) 0 else lineBottoms[minOf(maxLines, lineBottoms.size) - 1]
}

internal class NovaPlaySetupMeasuredFact(
    val value: NovaPlaySetupMeasuredText,
    val detail: NovaPlaySetupMeasuredText,
)

/** How many lines each plan line and each fact detail may use; Int.MAX_VALUE is all it needs. */
internal data class NovaPlaySetupReadFit(
    val lineMaxLines: List<Int>,
    val detailMaxLines: List<Int>,
)

/**
 * Trim the read column until it fits [available], one line at a time, and stop the moment it does.
 *
 * Prose goes before facts: the plan's last line, the opening sentence, down to two lines, then
 * every fact's detail to two, then the sentence to one, the details to one, and every plan line
 * to one. The longest part gives a line first, so no single detail collapses while another keeps
 * its length. A fact's value is never trimmed; it is the fact. When even that is not enough the
 * column keeps one line of everything and the panel's scroll remains the fallback.
 */
internal fun novaPlaySetupFitReadColumn(
    available: Int,
    fixed: Int,
    lineGap: Int,
    lines: List<NovaPlaySetupMeasuredText>,
    lineCap: Int,
    factChrome: Int,
    detailGap: Int,
    keyMin: Int,
    facts: List<NovaPlaySetupMeasuredFact>,
): NovaPlaySetupReadFit {
    val lineMax = lines.map { minOf(lineCap.coerceAtLeast(1), it.lineCount.coerceAtLeast(1)) }.toMutableList()
    val detailMax = facts.map { it.detail.lineCount }.toMutableList()
    fun total(): Int {
        var sum = fixed
        lines.forEachIndexed { index, line ->
            if (line.lineCount > 0) sum += lineGap + line.height(lineMax[index])
        }
        facts.forEachIndexed { index, fact ->
            val detail = if (fact.detail.lineCount > 0 && detailMax[index] > 0) {
                detailGap + fact.detail.height(detailMax[index])
            } else {
                0
            }
            sum += factChrome + maxOf(keyMin, fact.value.height(Int.MAX_VALUE) + detail)
        }
        return sum
    }
    fun trim(limits: MutableList<Int>, indices: List<Int>, floor: Int): Boolean {
        while (total() > available) {
            val index = indices.filter { limits[it] > floor }.maxByOrNull { limits[it] } ?: break
            limits[index] -= 1
        }
        return total() <= available
    }
    val last = listOfNotNull(lines.indices.lastOrNull())
    val details = facts.indices.toList()
    if (total() > available) {
        trim(lineMax, last, 2) ||
            trim(detailMax, details, 2) ||
            trim(lineMax, last, 1) ||
            trim(detailMax, details, 1) ||
            trim(lineMax, lines.indices.toList(), 1)
    }
    return NovaPlaySetupReadFit(
        lineMaxLines = lineMax.toList(),
        detailMaxLines = detailMax.map { if (it <= 0) Int.MAX_VALUE else it },
    )
}

/** Long enough to have read what is on the screen before any of it moves. */
private const val NOVA_PLAY_SETUP_READ_FIRST_TURN_MS = 2600L

/** Between rounds. The column is for reading; it should mostly be still. */
private const val NOVA_PLAY_SETUP_READ_REST_MS = 9000L

/** The rows a pinned legend leaves room for; the rest are a scroll away. */
private const val NOVA_PLAY_SETUP_ROWS_KEPT_IN_VIEW = 3

/** The dissolve at the bottom of the scrolling rows: enough to say more follows, less than a row. */
private val NOVA_PLAY_SETUP_ROWS_FADE = 18.dp

/** What the rows keep however tall the legend is: a row and a half, so the list reads as a list. */
private val NOVA_PLAY_SETUP_ROWS_FLOOR = 80.dp

/** Drawn heights, kept beside the drawing so the two cannot drift apart unnoticed. */
private val NOVA_PLAY_SETUP_COLUMN_HEAD = 16.dp
private val NOVA_PLAY_SETUP_ROW_STRIDE = 53.dp
private val NOVA_PLAY_SETUP_LEGEND_GAP = 4.dp
private val NOVA_PLAY_SETUP_CARD_CHROME = 40.dp
/**
 * Slack, so the budget aims to fit rather than to just fit.
 *
 * Without it the four-row case landed a few dp over, which turns the bottom fade on --
 * and the fade exists to say there is more below, so a layout that overflows by 4dp
 * dissolves 52dp of itself saying so. Being wrong in this direction is much cheaper.
 */
private val NOVA_PLAY_SETUP_SLACK = 12.dp
private val NOVA_PLAY_SETUP_CONSEQUENCE_LINE = 14.dp
private val NOVA_PLAY_SETUP_MODE_LINE = 35.dp
private val NOVA_PLAY_SETUP_RULE_BLOCK = 22.dp
private val NOVA_PLAY_SETUP_FACT = 34.dp
private val NOVA_PLAY_SETUP_INTRO_LINE = 19.dp

/** Below this the two columns stack; a phone has no room to put them side by side. */
private val NOVA_PLAY_SETUP_TWO_COLUMN_MIN = 640.dp

/** The read column is fixed so the choice rows keep a stable width as values change. */
private val NOVA_PLAY_SETUP_READ_WIDTH = 246.dp
private val NOVA_PLAY_SETUP_GUTTER = 22.dp
private val NOVA_PLAY_SETUP_FACT_KEY = 104.dp
/** A fact is never shorter than its key: one 13sp label line under a 3dp inset. */
private val NOVA_PLAY_SETUP_FACT_KEY_MIN = 16.dp
/**
 * One row of destination cards: a choice row's 48dp floor with a two line caption, plus its gap.
 * Their captions say why a place cannot be chosen, so they are budgeted at two lines.
 */
private val NOVA_PLAY_SETUP_DESTINATION_STRIDE = 70.dp

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
