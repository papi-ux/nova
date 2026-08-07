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
import com.papi.nova.ui.compose.NovaChromeFamily

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
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A phone in portrait has no room for two columns, and stacking them keeps the
        // same reading order: read the plan, then act on it.
        val stacked = maxWidth < NOVA_PLAY_SETUP_TWO_COLUMN_MIN
        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                NovaPlaySetupReadColumn(plan, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(18.dp))
                NovaPlaySetupActColumn(rows, comparison, Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                NovaPlaySetupReadColumn(
                    plan = plan,
                    modifier = Modifier.width(NOVA_PLAY_SETUP_READ_WIDTH).fillMaxHeight(),
                )
                Spacer(modifier = Modifier.width(NOVA_PLAY_SETUP_GUTTER))
                NovaPlaySetupActColumn(rows, comparison, Modifier.weight(1f))
            }
        }
    }
}

/** What will happen, and where each part of it came from. Read, never operated. */
@Composable
private fun NovaPlaySetupReadColumn(plan: NovaPlaySetupPlan, modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    Column(modifier = modifier) {
        NovaPlaySetupColumnHead(stringResource(R.string.nova_play_setup_what_will_happen))
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
                modifier = Modifier.padding(top = 7.dp),
                // These are sentences when Polaris has something to explain, so they wrap
                // rather than losing the end of the explanation mid-word.
                maxLines = 3,
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
            Spacer(modifier = Modifier.height(14.dp))
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
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            text = fact.key.uppercase(),
            color = colors.textMuted,
            fontSize = 9.sp,
            fontFamily = NovaChromeFamily,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.12.em,
            lineHeight = 13.sp,
            modifier = Modifier.width(NOVA_PLAY_SETUP_FACT_KEY).padding(top = 3.dp),
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
private fun NovaPlaySetupColumnHead(text: String) {
    val colors = LocalNovaComposeColors.current
    Text(
        text = text.uppercase(),
        color = colors.textMuted,
        fontSize = 9.sp,
        fontFamily = NovaChromeFamily,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.22.em,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/**
 * The alternatives to the focused row, stated as consequences rather than as verbs, and
 * selectable in place.
 *
 * This is what the full width is for, and it is also where the choice is made. An
 * earlier shape put the modes behind a picker raised over the destination, which cost a
 * press and re-introduced the options drawer that inline mode rows had deliberately
 * removed. Expanding the lane instead of raising a sheet is the rule this window already
 * follows for the preflight review, and it reads better here for the same reason: you
 * choose while looking at what each choice would do, rather than after dismissing the
 * thing that told you.
 *
 * Selection is a tint, focus is a ring, and they compose -- so the card you are on and
 * the card you are using are never the same drawing.
 */
@Composable
internal fun NovaPlaySetupComparison(title: String, options: List<NovaPlaySetupOption>) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPlaySetupColumnHead(title)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(NovaGameDetailRadius.row)
                val actionable = option.onSelect != null && option.enabled
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused || it.hasFocus }
                        .then(
                            if (actionable) {
                                Modifier.clickable(role = Role.Button) { option.onSelect?.invoke() }
                            } else {
                                Modifier
                            }
                        )
                        // clickable alone is not a focus target; every focusable in this
                        // app has to say so.
                        .focusable(enabled = actionable)
                        .heightIn(min = NovaGameDetailActionHeight)
                        .clip(shape)
                        .background(if (option.current) colors.accentSurface else surfaces.tile)
                        .border(
                            1.dp,
                            if (option.current || focused) {
                                colors.accent.copy(alpha = 0.58f)
                            } else {
                                surfaces.tileBorder
                            },
                            shape,
                        )
                        .then(
                            if (focused && actionable) {
                                Modifier.border(2.dp, surfaces.focusRing, shape)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 11.dp)
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
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
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
    /** Null makes the card explanatory rather than selectable. */
    val onSelect: (() -> Unit)? = null,
)

/** Below this the two columns stack; a phone has no room to put them side by side. */
private val NOVA_PLAY_SETUP_TWO_COLUMN_MIN = 640.dp

/** The read column is fixed so the choice rows keep a stable width as values change. */
private val NOVA_PLAY_SETUP_READ_WIDTH = 246.dp
private val NOVA_PLAY_SETUP_GUTTER = 22.dp
private val NOVA_PLAY_SETUP_FACT_KEY = 86.dp

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

        val asked = novaStripLabel(summary.requestedLine)
        val granted = novaStripLabel(summary.selectedLine)
        if (asked.isNotBlank()) {
            facts += NovaPlaySetupFact(
                key = askedKey,
                value = asked,
                detail = if (granted.isNotBlank()) grantedFormat.format(granted) else "",
            )
        }

        summary.freshnessLine.takeIf { it.isNotBlank() }?.let {
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
