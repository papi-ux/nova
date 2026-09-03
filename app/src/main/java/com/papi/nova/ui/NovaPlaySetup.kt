package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A phone in portrait has no room for two columns, and stacking them keeps the
        // same reading order: read the plan, then act on it.
        val stacked = maxWidth < NOVA_PLAY_SETUP_TWO_COLUMN_MIN
        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NovaPlaySetupReadColumn(plan, introMaxLines, Modifier.fillMaxWidth(), readTitle)
                Spacer(modifier = Modifier.height(18.dp))
                NovaPlaySetupActColumn(rows, comparison, Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                NovaPlaySetupReadColumn(
                    plan = plan,
                    introMaxLines = introMaxLines,
                    modifier = Modifier.width(NOVA_PLAY_SETUP_READ_WIDTH).fillMaxHeight(),
                    readTitle = readTitle,
                )
                Spacer(modifier = Modifier.width(NOVA_PLAY_SETUP_GUTTER))
                NovaPlaySetupActColumn(rows, comparison, Modifier.weight(1f))
            }
        }
    }
}

/** What will happen, and where each part of it came from. Read, never operated. */
@Composable
private fun NovaPlaySetupReadColumn(
    plan: NovaPlaySetupPlan,
    introMaxLines: Int,
    modifier: Modifier = Modifier,
    readTitle: String? = null,
) {
    val colors = LocalNovaComposeColors.current
    Column(modifier = modifier) {
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
            Text(
                text = line,
                // The last line is the part nobody asked for but everyone wants to know:
                // whether anything outside this game is about to be touched.
                color = if (index == plan.lines.lastIndex) colors.textMuted else colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = introMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (plan.facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            NovaPlaySetupRule()
            plan.facts.forEach { NovaPlaySetupFact(it) }
        }
    }
}

/** The few real choices, and what the alternatives to the focused one would mean. */
@Composable
private fun NovaPlaySetupActColumn(
    rows: @Composable () -> Unit,
    comparison: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_what_you_can_change))
        rows()
        if (comparison != null) {
            Spacer(modifier = Modifier.height(4.dp))
            comparison()
        }
    }
}

/**
 * One key/value fact about why the plan is what it is.
 *
 * A definition list rather than four stacked blocks: the keys line up, so the eye reads
 * down one edge instead of hunting for where each one starts.
 */
@Composable
private fun NovaPlaySetupFact(fact: NovaPlaySetupFact) {
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
 * that exists only to explain the thing you just stopped on, and it would put a fifth and
 * sixth stop in the path of every visit down the column. Left and right do nothing in this
 * panel, which is the point -- four rows, no scrolling, nothing to hunt for.
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
     * One line rather than two once the column carries a fourth row.
     *
     * The panel has to fit a ~325dp landscape viewport without scrolling. Four rows at the
     * 48dp accessible floor plus their gaps is 212dp of it, and the legend has to live in
     * what is left. A host advertising a display planner is exactly the case that adds that
     * fourth row, so the legend gives up its second line rather than the panel giving up
     * fitting.
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                chunk.forEach { option ->
                    NovaPlaySetupComparisonCard(
                        option = option,
                        consequenceMaxLines = consequenceMaxLines,
                        modifier = Modifier.weight(1f),
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
                contentDescription = "${'$'}{option.label}. ${'$'}{option.consequence}"
                if (option.current) selected = true
            },
    ) {
        Text(
            text = option.label,
            color = if (option.enabled) colors.textPrimary else colors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = option.consequence,
            color = if (option.active && !option.current) colors.accent else colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            // Bounded, so a long consequence cannot push the card past the cut.
            // The sentence is a hint at what a choice means, not the contract.
            maxLines = consequenceMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
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
 * Four per scope, and fixed at four. The act column has to fit a landscape viewport that is
 * roughly 230-275dp once the panel's chrome and the bottom fade are taken out, and the strip
 * below the rows used to start at 272dp in the lightest case and 332dp for a Steam game -- so
 * the strip every control on this screen wrote to was off the bottom of the window at the
 * moment it was written, with nothing scrolling to it. A fifth row would put it back there.
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
    WHERE_IT_RUNS,
    RESOLUTION,
    FRAME_RATE,
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
 * How much of the legend the column can afford, and how much prose the plan can.
 *
 * Both are a function of how many rows the host produced: a host advertising a display
 * planner adds Resolution, and those 53dp come out of whatever is below. Rather than pick
 * one answer for every case and truncate the rest, the panel measures its body and this
 * spends what is actually there -- three lines of consequence where there is room for
 * three, one where there is room for one.
 */
internal fun novaPlaySetupConsequenceLines(availableHeight: Dp, rowCount: Int): Int {
    if (availableHeight <= 0.dp) return 2
    val used = NOVA_PLAY_SETUP_COLUMN_HEAD +
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
