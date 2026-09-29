package com.papi.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.novaConfirm
import com.papi.nova.ui.compose.novaFocusTick
import com.papi.nova.ui.panel.BackGlyph
import com.papi.nova.ui.panel.NovaChevron
import com.papi.nova.ui.panel.NovaCurrentMark
import com.papi.nova.ui.panel.NovaFocusHint
import com.papi.nova.ui.panel.OpensGlyph
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaSectionLabel
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusHint
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaRowRest
import com.papi.nova.ui.panel.novaScrollEdgeFade

/**
 * Play Setup: one destination for the whole question of how this game should run, drawn as the
 * approved mockup draws it (papi, 2026-09-28).
 *
 * The panel is the wide one at the end edge. Its header holds the title and the scope pill; under
 * it the plan card says what will happen, and under that are the setting rows, one tile each, 4dp
 * apart. A row whose value carries a `›` opens a page inside the panel; a row with no arrow changes
 * in place, showing `‹ value ›` while it has focus. The focused row says in one line, inside its own
 * tile, what its value means, so nothing moves when focus does. On a page the plan card stays where
 * it was and previews the option under the cursor.
 *
 * It replaces a two column body with a legend of option cards under the rows. The legend explained
 * the row under the cursor from a screen away, drew empty boxes for choices with nothing to say,
 * and cost the rows most of a short handheld's height: on the RP6 two rows were visible. Every
 * consequence the cards said is now said where the choice is made: in the focused row's caption, in
 * the notes of an option page, and in the plan card's preview.
 *
 * Nothing in it is cut (R13). Every line wraps whole, and the rows scroll under the pinned plan card
 * when a large font makes them taller than the panel, with the cut edge fading.
 */
@Composable
internal fun NovaPlaySetupBody(
    /** The plan card, pinned above the rows so it never scrolls away; null draws none. */
    card: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    /** On the rows' group, such as the requester focus enters when Y swaps every row. */
    rowsModifier: Modifier = Modifier,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (card != null) {
            card()
            Spacer(modifier = Modifier.height(NovaPanelMetrics.SpaceSm))
        }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .testTag(NOVA_PLAY_SETUP_ROWS_TAG)
                .then(rowsModifier)
                .novaScrollEdgeFade(scroll)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
            content = rows,
        )
    }
}

/**
 * The plan card: what the launch will do, as one tile of two lines. Line one is [title] and the
 * resolved mode as its [value]; line two is the numbers.
 *
 * At the root it is a page row: it takes focus (Up from the first setting row) and A opens the
 * plan whole on its page ([onOpen]). On a page it is not a stop, and it previews the option under
 * the cursor: its title says which ("If you choose 2x"), [accentPart] marks what that choice
 * changes in the line, and [limit] says in the warning colour what would hold it back.
 */
@Composable
internal fun NovaPlaySetupPlanCard(
    title: String,
    value: String,
    line: String,
    modifier: Modifier = Modifier,
    accentPart: String = "",
    limit: String = "",
    onOpen: (() -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.row)
    val rest = novaRowRest
    val haptics = LocalHapticFeedback.current
    val open by rememberUpdatedState(onOpen)
    val frame = if (onOpen != null) {
        Modifier
            .novaFocusRing(shape, rest = rest)
            .novaClickable(role = Role.Button) {
                haptics.novaConfirm()
                open?.invoke()
            }
    } else {
        // Read on a page, never a stop there: the cursor stays on the options it previews.
        Modifier
            .background(rest.fill, shape)
            .border(rest.borderWidth, rest.border, shape)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .then(frame)
            .semantics(mergeDescendants = true) {}
            .padding(start = NovaPanelMetrics.SpaceMd, end = NovaPanelMetrics.SpaceSm, top = TileVertical, bottom = TileVertical)
            .testTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The title takes its whole width first and the value what is left, at the end; when
            // both will not fit on the line the value goes under the title, never the title into
            // a column of one word a line.
            val valueStyle = novaPlaySetupValueStyle()
            Layout(
                contents = listOf(
                    { Text(text = title, style = type.rowTitle, color = colors.textPrimary) },
                    {
                        if (limit.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NovaPlaySetupWarningGlyph(Modifier.padding(end = NovaPanelMetrics.SpaceXs))
                                Text(text = limit, style = valueStyle, color = colors.warning)
                            }
                        } else if (value.isNotBlank()) {
                            Text(text = value, style = valueStyle, color = colors.textSecondary, textAlign = TextAlign.End)
                        }
                    },
                ),
                modifier = Modifier.weight(1f),
                measurePolicy = NovaPlaySetupTitleFirst,
            )
            NovaPlaySetupSlot { if (onOpen != null) NovaPlaySetupChevron(OpensGlyph, colors.textSecondary) }
        }
        if (line.isNotBlank()) {
            val accent = colors.accent
            val marked = remember(line, accentPart, accent) {
                buildAnnotatedString {
                    val at = if (accentPart.isBlank()) -1 else line.indexOf(accentPart)
                    if (at < 0) {
                        append(line)
                    } else {
                        append(line.substring(0, at))
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(accentPart) }
                        append(line.substring(at + accentPart.length))
                    }
                }
            }
            // The whole line, wrapping, where a long one needs a second (R13).
            Text(text = marked, style = type.caption, color = colors.textSecondary)
        }
    }
}

/**
 * The plan card's second line: the plan's first line, which is the numbers, as the mockup draws it.
 * What holds the launch back is a press away on the plan's page, and the card's preview names it
 * in the warning colour for the option that would meet it; on the card's own line it wrapped the
 * numbers onto a second line as a label and a colon.
 */
internal fun novaPlaySetupPlanSummary(plan: NovaPlaySetupPlan): String? =
    plan.lines.firstOrNull()?.takeIf { it.isNotBlank() }

/**
 * A title and a value on one line: the title at its whole width, the value in what is left at the
 * end. When both will not fit, the value goes under the title, where it has the whole width.
 */
private val NovaPlaySetupTitleFirst = MultiContentMeasurePolicy { (titles, values), constraints ->
    val title = titles.first()
    val value = values.firstOrNull()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
    val gap = NovaPanelMetrics.SpaceMd.roundToPx()
    val titleNatural = title.maxIntrinsicWidth(Constraints.Infinity)
    val valueNatural = value?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
    if (value == null || valueNatural == 0 || titleNatural + gap + valueNatural <= width) {
        val titlePlaced = title.measure(Constraints(maxWidth = minOf(titleNatural, width)))
        val room = (width - titlePlaced.width - gap).coerceAtLeast(0)
        val valuePlaced = value?.measure(Constraints(maxWidth = room))
        val shownWidth = if (constraints.hasBoundedWidth) width else titlePlaced.width + gap + (valuePlaced?.width ?: 0)
        val height = maxOf(titlePlaced.height, valuePlaced?.height ?: 0, constraints.minHeight)
        layout(shownWidth, height) {
            titlePlaced.placeRelative(0, (height - titlePlaced.height) / 2)
            valuePlaced?.placeRelative(shownWidth - valuePlaced.width, (height - valuePlaced.height) / 2)
        }
    } else {
        val titlePlaced = title.measure(Constraints(maxWidth = width))
        val valuePlaced = value.measure(Constraints(maxWidth = width))
        val height = maxOf(titlePlaced.height + valuePlaced.height, constraints.minHeight)
        layout(width, height) {
            titlePlaced.placeRelative(0, 0)
            valuePlaced.placeRelative(0, titlePlaced.height)
        }
    }
}

/**
 * The line the plan card shows while [preview]'s option has focus: [base], the plan's own line,
 * with the part that option changes swapped for its new value. A line that names no such part
 * gains it at the front, so the preview never shows the old value as if it were the new one.
 */
internal fun novaPlaySetupPreviewLine(base: String, preview: NovaPlaySetupPreview): String {
    if (preview.changed.isBlank()) return base
    val parts = base.split(" · ").filter { it.isNotBlank() }.toMutableList()
    val at = parts.indexOfFirst { part ->
        when (preview.part) {
            NovaPlaySetupPreviewPart.SIZE -> NOVA_PLAY_SETUP_SIZE.containsMatchIn(part)
            NovaPlaySetupPreviewPart.CODEC -> NOVA_PLAY_SETUP_CODEC.containsMatchIn(part)
        }
    }
    if (at < 0) return (listOf(preview.changed) + parts).joinToString(" · ")
    parts[at] = when (preview.part) {
        NovaPlaySetupPreviewPart.SIZE -> NOVA_PLAY_SETUP_SIZE.replaceFirst(parts[at], preview.changed)
        NovaPlaySetupPreviewPart.CODEC -> preview.changed
    }
    return parts.joinToString(" · ")
}

private val NOVA_PLAY_SETUP_SIZE = Regex("""\d+\s*[x×]\s*\d+""")
private val NOVA_PLAY_SETUP_CODEC = Regex("""^(H\.?264|H\.?265|HEVC|AV1|PyroWave|Auto)\b""", RegexOption.IGNORE_CASE)

/**
 * The scope pill in Play Setup's header: This Game and Every Game, the current one checked. It is
 * not a stop on the d-pad; Y flips it, and a tap on either half does (R12).
 */
@Composable
internal fun NovaPlaySetupScopePill(
    scope: NovaPlaySetupScope,
    onSelected: (NovaPlaySetupScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val select by rememberUpdatedState(onSelected)
    val currentLabel = stringResource(R.string.nova_panel_current)
    val shape = RoundedCornerShape(NovaRadius.row)
    Row(
        modifier = modifier
            .clip(shape)
            .background(surfaces.control, shape)
            .focusProperties { canFocus = false }
            .testTag(NOVA_PLAY_SETUP_SCOPE_PILL_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            NovaPlaySetupScope.THIS_GAME to stringResource(R.string.nova_play_setup_scope_this_game),
            NovaPlaySetupScope.EVERY_GAME to stringResource(R.string.nova_play_setup_every_game),
        ).forEachIndexed { index, (value, label) ->
            val current = value == scope
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(NovaPanelMetrics.Hairline)
                        .height(NovaPanelMetrics.SpaceMd)
                        .background(surfaces.tileBorder),
                )
            }
            Row(
                modifier = Modifier
                    .heightIn(min = NovaPlaySetupPillHeight)
                    .pointerInput(value) { detectTapGestures(onTap = { select(value) }) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        selected = current
                        if (current) stateDescription = currentLabel
                        onClick(label = label) {
                            select(value)
                            true
                        }
                    }
                    .padding(horizontal = NovaPanelMetrics.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
            ) {
                Text(
                    text = label,
                    style = type.caption,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) colors.textPrimary else colors.textSecondary,
                )
                if (current) NovaCurrentMark(Modifier.size(NovaPlaySetupPillMark))
            }
        }
    }
}

/** How a Play Setup row answers the d-pad. */
internal enum class NovaPlaySetupRowKind {
    /** A opens the row's page, or asks the owner to act; its value carries `›`. */
    PAGE,

    /** Left and Right change the value in place, and A steps it forward (R1). */
    IN_PLACE,

    /** Nothing to step through: A asks the owner to act, once. */
    ACTION,
}

internal fun novaPlaySetupRowKind(state: NovaPlaySetupRowState): NovaPlaySetupRowKind = when {
    state.opensPage -> NovaPlaySetupRowKind.PAGE
    state.options.size > 1 && state.options.any { it.current } -> NovaPlaySetupRowKind.IN_PLACE
    else -> NovaPlaySetupRowKind.ACTION
}

/**
 * The option one step from [index] in [delta]'s direction among those that can be chosen, or null
 * at an ordered row's end. Unordered rows wrap ([wrap]).
 */
internal fun novaPlaySetupStep(options: List<NovaPlaySetupOption>, index: Int, delta: Int, wrap: Boolean): Int? {
    if (options.isEmpty()) return null
    var next = index
    repeat(options.size) {
        next += delta
        if (next !in options.indices) {
            if (!wrap) return null
            next = Math.floorMod(next, options.size)
        }
        val option = options[next]
        if (option.enabled && option.onSelect != null) return next.takeIf { it != index }
    }
    return null
}

/**
 * One of Play Setup's setting rows, for this game or for every game: one tile, 44dp at the compact
 * density, with the title at the start and the value on one right line before an 18dp slot.
 *
 * A row that opens a page ([NovaPlaySetupRowState.opensPage]) always shows `›` in the slot, and A
 * runs [onAdvance], which pushes the page.
 *
 * A row that changes in place shows only its value at rest. With focus it shows `‹ value ›` with
 * both chevrons in accent; an ordered row ([NovaPlaySetupRowState.ordered]) shows every stop, the
 * current one underlined. Left and Right change it on key down and never move focus; an ordered row
 * stops at its ends. A steps forward and wraps, as does a tap on the row; a tap on an arrow or a stop
 * acts on that (R1, R12).
 *
 * A row with nothing to step through runs [onAdvance] on A.
 *
 * The focused row, and a row that cannot change, says what its value means on a second line inside
 * its own tile. A value set here rather than by the host carries an accent dot, and with
 * [setHereNote] the caption leads with the same dot and says so in words. Nothing is ellipsized: a
 * caption too long for its line wraps and the tile grows.
 */
@Composable
internal fun NovaPlaySetupSettingRow(
    state: NovaPlaySetupRowState,
    onAdvance: (NovaPlaySetupRow) -> Unit,
    modifier: Modifier = Modifier,
    /** "Set for this game" in This Game; null in Every Game, which sets nothing for a game. */
    setHereNote: String? = null,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(NovaRadius.row)
    val options = state.options
    val currentIndex = options.indexOfFirst { it.current }
    val kind = novaPlaySetupRowKind(state)
    val selectable = options.count { it.enabled && it.onSelect != null }
    val changes = kind == NovaPlaySetupRowKind.IN_PLACE && state.enabled && selectable > 1
    val acts = kind != NovaPlaySetupRowKind.IN_PLACE && state.enabled
    val latestOptions by rememberUpdatedState(options)
    val latestIndex by rememberUpdatedState(currentIndex)
    val advance by rememberUpdatedState(onAdvance)
    val row = state.row
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val previousLabel = stringResource(R.string.nova_panel_previous)
    val nextLabel = stringResource(R.string.nova_panel_next)
    val setHere = state.overridden && setHereNote != null

    fun step(delta: Int, wrap: Boolean) {
        if (!changes) return
        val target = novaPlaySetupStep(latestOptions, latestIndex, delta, wrap)
        if (target == null) {
            if (!wrap) haptics.performHapticFeedback(HapticFeedbackType.Reject)
            return
        }
        haptics.novaFocusTick()
        latestOptions[target].onSelect?.invoke()
    }

    fun pick(index: Int) {
        if (!changes || index == latestIndex) return
        val option = latestOptions.getOrNull(index) ?: return
        if (!option.enabled) return
        haptics.novaFocusTick()
        option.onSelect?.invoke()
    }

    val current = options.getOrNull(currentIndex)
    val shownValue = state.value.ifBlank { current?.label.orEmpty() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            // `◂▸ Change · A Next` where the row changes in place, since A steps it; nothing on A
            // where the row cannot act; Select where A opens its page or asks the owner.
            .novaFocusHint(
                when {
                    changes -> NovaFocusHint.Next
                    acts -> null
                    else -> NovaFocusHint.Read
                },
            )
            .onFocusChanged {
                if (it.hasFocus && !focused) haptics.novaFocusTick()
                focused = it.hasFocus
            }
            .semantics(mergeDescendants = true) {
                stateDescription = shownValue
                if (changes) {
                    customActions = listOf(
                        CustomAccessibilityAction(previousLabel) {
                            step(-1, wrap = !state.ordered)
                            true
                        },
                        CustomAccessibilityAction(nextLabel) {
                            step(1, wrap = !state.ordered)
                            true
                        },
                    )
                }
            }
            .onPreviewKeyEvent { event ->
                // A row that cannot change has nothing for Left and Right to do, so they move on.
                if (!changes) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionLeft -> if (rtl) 1 else -1
                    Key.DirectionRight -> if (rtl) -1 else 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) step(delta, wrap = !state.ordered)
                // Both halves of the key are the row's; focus never leaves it sideways.
                true
            }
            .novaClickable(
                enabled = changes || acts,
                role = Role.Button,
                focusableWhenDisabled = true,
            ) {
                if (kind == NovaPlaySetupRowKind.IN_PLACE) {
                    step(1, wrap = true)
                } else {
                    haptics.novaConfirm()
                    advance(row)
                }
            }
            .padding(start = NovaPanelMetrics.SpaceMd, end = NovaPanelMetrics.SpaceSm, top = TileVertical, bottom = TileVertical),
        verticalArrangement = Arrangement.Center,
    ) {
        val quiet = if (state.enabled) 1f else NovaPanelMetrics.DisabledAlpha
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(quiet)) {
            Text(
                text = state.label,
                style = type.rowTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).padding(end = NovaPanelMetrics.SpaceMd),
            )
            when {
                changes && focused && state.ordered -> NovaPlaySetupStrip(
                    options = options,
                    currentIndex = currentIndex,
                    unit = state.unit,
                    onPrevious = { step(-1, wrap = false) },
                    onPick = ::pick,
                )
                changes && focused -> {
                    NovaPlaySetupChevron(BackGlyph, colors.accent, onTap = { step(-1, wrap = !state.ordered) })
                    Text(text = shownValue, style = novaPlaySetupValueStyle(), color = colors.textPrimary)
                }
                else -> {
                    if (setHere) NovaPlaySetupSetHereDot(Modifier.padding(end = NovaPanelMetrics.SpaceSm))
                    Text(text = shownValue, style = novaPlaySetupValueStyle(), color = colors.textSecondary)
                }
            }
            NovaPlaySetupSlot {
                when {
                    kind == NovaPlaySetupRowKind.PAGE -> NovaPlaySetupChevron(OpensGlyph, colors.textSecondary)
                    changes && focused -> {
                        val atEnd = state.ordered && novaPlaySetupStep(options, currentIndex, 1, wrap = false) == null
                        NovaPlaySetupChevron(
                            OpensGlyph,
                            if (atEnd) colors.textMuted else colors.accent,
                            onTap = { step(1, wrap = !state.ordered) },
                        )
                    }
                }
            }
        }
        if ((focused || !state.enabled) && (state.caption.isNotBlank() || setHere)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (setHere) NovaPlaySetupSetHereDot(Modifier.padding(end = NovaPanelMetrics.SpaceSm))
                Text(
                    text = listOfNotNull(setHereNote.takeIf { setHere }, state.caption.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Every stop of an ordered row, as the Frame Rate strip draws them: the current one SemiBold and
 * underlined in accent, the unit once at the end. A tap on a stop chooses it.
 */
@Composable
private fun NovaPlaySetupStrip(
    options: List<NovaPlaySetupOption>,
    currentIndex: Int,
    unit: String,
    onPrevious: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val style = novaPlaySetupValueStyle()
    val atStart = novaPlaySetupStep(options, currentIndex, -1, wrap = false) == null
    NovaPlaySetupChevron(BackGlyph, if (atStart) colors.textMuted else colors.accent, onTap = onPrevious)
    Row(horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { index, option ->
            val current = index == currentIndex
            val accent = colors.accent
            Text(
                text = option.short.ifBlank { option.label },
                style = style,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    !option.enabled -> colors.textMuted
                    current -> colors.textPrimary
                    else -> colors.textSecondary
                },
                modifier = Modifier
                    .pointerInput(index) { detectTapGestures(onTap = { onPick(index) }) }
                    .then(
                        if (current) {
                            Modifier.drawBehind {
                                val stroke = NovaPlaySetupUnderline.toPx()
                                drawLine(
                                    color = accent,
                                    start = Offset(0f, size.height + stroke),
                                    end = Offset(size.width, size.height + stroke),
                                    strokeWidth = stroke,
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (unit.isNotBlank()) Text(text = unit, style = style, color = colors.textSecondary)
    }
}

/** The 18dp slot every Play Setup tile ends with: `›`, the current check, or nothing. */
@Composable
private fun NovaPlaySetupSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.padding(start = NovaPanelMetrics.SpaceSm).size(NovaPanelMetrics.CurrentMarkSize),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A tile's chevron, [glyph] naming its direction: the 18dp drawing, not the character. */
@Composable
private fun NovaPlaySetupChevron(glyph: String, color: Color, onTap: (() -> Unit)? = null) {
    val tap by rememberUpdatedState(onTap)
    val back = glyph == BackGlyph
    NovaChevron(
        back = back,
        tint = color,
        modifier = if (onTap != null) {
            Modifier
                .padding(end = if (back) NovaPanelMetrics.SpaceXs else 0.dp)
                .pointerInput(glyph) { detectTapGestures(onTap = { tap?.invoke() }) }
        } else {
            Modifier
        },
    )
}

/** The accent dot of a value set for this game. */
@Composable
private fun NovaPlaySetupSetHereDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(NovaPlaySetupDot)
            .clip(CircleShape)
            .background(LocalNovaComposeColors.current.accent),
    )
}

@Composable
private fun NovaPlaySetupWarningGlyph(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_warning),
        contentDescription = null,
        tint = LocalNovaComposeColors.current.warning,
        modifier = modifier.size(NovaPlaySetupWarningSize),
    )
}

/** A value in a Play Setup tile: the value size, Medium as the mockup draws it. */
@Composable
private fun novaPlaySetupValueStyle() = novaPanelType.value.copy(fontWeight = FontWeight.Medium)

/**
 * A group of options on a Play Setup page, such as a codec page's ENCODER band, under its label.
 */
internal data class NovaPlaySetupBand(
    val label: String?,
    val options: List<NovaPlaySetupOption>,
)

/**
 * One option on a Play Setup page: its name and its value on the first line, and on the second
 * what choosing it means. The current option carries the accent check and a SemiBold name (R9).
 * "Recommended" leads the note in accent, a warning note is drawn in the warning colour with its
 * mark, and an option that cannot be chosen stays in the list, muted, as "Not available" and its
 * reason, and focus passes over it. A on an option that can be chosen runs [onPick]; one that is
 * only read takes focus and does nothing.
 */
@Composable
internal fun NovaPlaySetupOptionRow(
    option: NovaPlaySetupOption,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Keeps a stop on an option that cannot be chosen, for a list that may hold nothing else, such
     * as the places a game can open in while a change is in flight: focus is never left on nothing.
     */
    focusableWhenDisabled: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(NovaRadius.row)
    val pick by rememberUpdatedState(onPick)
    val currentLabel = stringResource(R.string.nova_panel_current)
    val notAvailable = stringResource(R.string.nova_play_setup_not_available)
    val recommended = stringResource(R.string.nova_play_setup_recommended)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            .then(if (option.enabled || focusableWhenDisabled) Modifier else Modifier.focusProperties { canFocus = false })
            .semantics(mergeDescendants = true) {
                if (option.current) {
                    selected = true
                    stateDescription = currentLabel
                }
                if (!option.enabled) disabled()
            }
            .novaClickable(enabled = option.enabled, role = Role.Button, focusableWhenDisabled = focusableWhenDisabled) {
                if (option.onSelect != null) {
                    haptics.novaConfirm()
                    pick()
                }
            }
            .padding(start = NovaPanelMetrics.SpaceMd, end = NovaPanelMetrics.SpaceSm, top = TileVertical, bottom = TileVertical),
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (option.enabled) colors.textPrimary else colors.textMuted
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option.label,
                style = type.rowTitle,
                fontWeight = if (option.current) FontWeight.SemiBold else type.rowTitle.fontWeight,
                color = ink,
                modifier = Modifier.weight(1f).padding(end = NovaPanelMetrics.SpaceMd),
            )
            if (option.value.isNotBlank()) {
                Text(
                    text = option.value,
                    style = novaPlaySetupValueStyle(),
                    color = if (option.enabled) colors.textSecondary else colors.textMuted,
                )
            }
            NovaPlaySetupSlot { if (option.current) NovaCurrentMark() }
        }
        val note = when {
            !option.enabled -> listOf(notAvailable, option.consequence).filter { it.isNotBlank() }.joinToString(" · ")
            else -> option.consequence
        }
        if (note.isNotBlank() || option.recommended) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.warning && option.enabled) {
                    NovaPlaySetupWarningGlyph(Modifier.padding(end = NovaPanelMetrics.SpaceXs))
                }
                val accent = colors.accent
                val lead = if (option.recommended && option.enabled) recommended else ""
                Text(
                    text = buildAnnotatedString {
                        if (lead.isNotBlank()) {
                            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(lead) }
                            if (note.isNotBlank()) append(" · ")
                        }
                        append(note)
                    },
                    style = type.caption,
                    color = when {
                        !option.enabled -> colors.textMuted
                        option.warning -> colors.warning
                        else -> colors.textSecondary
                    },
                )
            }
        }
    }
}

/**
 * A page's options, band by band: a band's label as a section heading, then its options. [onPick]
 * gets each option that can be chosen when A lands on it; [rowModifier] marks each by its key.
 */
@Composable
internal fun NovaPlaySetupBands(
    bands: List<NovaPlaySetupBand>,
    onPick: (NovaPlaySetupOption) -> Unit,
    rowModifier: (key: String, option: NovaPlaySetupOption) -> Modifier = { _, _ -> Modifier },
) {
    bands.forEachIndexed { bandIndex, band ->
        band.label?.takeIf { it.isNotBlank() }?.let { NovaSectionLabel(it) }
        band.options.forEach { option ->
            NovaPlaySetupOptionRow(
                option = option,
                onPick = { onPick(option) },
                modifier = rowModifier(novaPlaySetupOptionKey(bandIndex, option), option),
            )
        }
    }
}

/** An option's key on its page: its band and its name, so two bands may share a name. */
internal fun novaPlaySetupOptionKey(bandIndex: Int, option: NovaPlaySetupOption): String = "$bandIndex:${option.label}"

/** The option a page opens on: the current one that can be chosen, else the first that can. */
internal fun novaPlaySetupInitialOption(bands: List<NovaPlaySetupBand>): String? {
    val all = bands.flatMapIndexed { index, band -> band.options.map { index to it } }
    val (band, option) = all.firstOrNull { (_, option) -> option.current && option.enabled }
        ?: all.firstOrNull { (_, option) -> option.enabled && option.onSelect != null }
        ?: all.firstOrNull { (_, option) -> option.enabled }
        ?: return null
    return novaPlaySetupOptionKey(band, option)
}

/**
 * One key/value fact about why the plan is what it is, on the plan's page.
 *
 * A definition list rather than four stacked blocks: the keys line up, so the eye reads down one
 * edge instead of hunting for where each one starts. Key, value and detail each wrap whole.
 */
@Composable
internal fun NovaPlaySetupFact(fact: NovaPlaySetupFact) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = fact.key.uppercase(),
            color = colors.textMuted,
            style = type.sectionLabel,
            // Wrapping in its own column, because a key that ran past it printed itself over the
            // value it is labelling.
            modifier = Modifier.width(NOVA_PLAY_SETUP_FACT_KEY).padding(top = 3.dp, end = NovaPanelMetrics.SpaceSm),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fact.value,
                color = when (fact.tone) {
                    // The same source LaunchProfilePrimaryNotice reads for a healthy tone. It is not a
                    // theme token; a good grade is good in every theme.
                    NovaPlaySetupTone.GOOD -> colorResource(R.color.nova_success)
                    NovaPlaySetupTone.WARN -> colors.warning
                    NovaPlaySetupTone.PLAIN -> colors.textSecondary
                },
                style = type.rowTitle,
            )
            if (fact.detail.isNotBlank()) {
                Text(
                    text = fact.detail,
                    color = colors.textMuted,
                    style = type.caption,
                    modifier = Modifier.padding(top = NovaPanelMetrics.SpaceXs),
                )
            }
        }
    }
}

/** The plan's statement on its page: the resolved mode in the page title style, then its lines. */
@Composable
internal fun NovaPlaySetupPlanStatement(plan: NovaPlaySetupPlan) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Column {
        // A mode name longer than the page is read whole on a second line rather than cut (R13).
        Text(text = plan.mode, style = type.pageTitle, color = colors.textPrimary)
        plan.lines.forEachIndexed { index, line ->
            Text(
                text = line,
                // The last line is the part nobody asked for but everyone wants to know: whether
                // anything outside this game is about to be touched.
                color = if (index == plan.lines.lastIndex) colors.textMuted else colors.textSecondary,
                style = type.caption,
                modifier = Modifier.padding(top = NovaPanelMetrics.SpaceXs),
            )
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

/** Which part of the plan's line an option changes, for the plan card's preview. */
internal enum class NovaPlaySetupPreviewPart { SIZE, CODEC }

/**
 * What the plan card shows while an option has focus on its page: [changed] in place of the
 * [part] of the plan's line it replaces, drawn in accent, and [limit], when something would hold
 * the launch back, in the warning colour.
 */
internal data class NovaPlaySetupPreview(
    val part: NovaPlaySetupPreviewPart,
    val changed: String,
    val limit: String = "",
)

internal data class NovaPlaySetupOption(
    val label: String,
    /** What choosing it means, or, when it cannot be chosen, why. The second line on a page. */
    val consequence: String,
    val current: Boolean = false,
    val enabled: Boolean = true,
    /**
     * In effect this session without being the saved choice: the host fell back or a relaunch is
     * pending.
     */
    val active: Boolean = false,
    /** Null makes the option something to read rather than to choose. */
    val onSelect: (() -> Unit)? = null,
    /** The first line's value on a page, such as a size. */
    val value: String = "",
    /** The option's stop in an ordered strip, such as "60" for 60 FPS. */
    val short: String = "",
    /** Leads the note with "Recommended" in accent. */
    val recommended: Boolean = false,
    /** The note warns: the warning colour and its mark. */
    val warning: Boolean = false,
    /** What the plan card previews while this option has focus. */
    val preview: NovaPlaySetupPreview? = null,
)

/**
 * Which subject Play Setup is showing: the game the panel opened for, or the host defaults every
 * game inherits. One panel, two scopes, flipped by the header pill or Y, because the Every Game
 * answer used to live in a sheet behind a row, where the person comparing "this game" against
 * "everything else" could not see both at once.
 */
internal enum class NovaPlaySetupScope { THIS_GAME, EVERY_GAME }

/**
 * The things Play Setup can change, in the order they are drawn.
 *
 * PLAY_IN is where the game opens (the host's desktop or a Space); it is the first band of the
 * Where It Runs page, and a Space game's own Where It Runs row. ENCODER is the second band of the
 * Video Codec page. The HOST_ rows are Every Game's: the Polaris Sync sheet's sections in the same
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

/** The rows Play Setup's root draws; the rest are bands of a page. */
internal fun novaPlaySetupRootRows(rows: List<NovaPlaySetupRowState>): List<NovaPlaySetupRowState> {
    val hasWhere = rows.any { it.row == NovaPlaySetupRow.WHERE_IT_RUNS }
    return rows.filter { state ->
        when (state.row) {
            NovaPlaySetupRow.ENCODER -> false
            // Where a game opens is the first band of the Where It Runs page. A Space game has no
            // mode to choose, so its places are its Where It Runs row.
            NovaPlaySetupRow.PLAY_IN -> !hasWhere
            else -> true
        }
    }
}

/**
 * One row: what it is called, what it reads, what its value means, and its options.
 *
 * The options travel with the row rather than in a state the row has to open, so a row that changes
 * in place and the page a row opens read the same list, and there is no picker state to go stale.
 */
internal data class NovaPlaySetupRowState(
    val row: NovaPlaySetupRow,
    val label: String,
    /** What the current value means, in one line; shown while the row has focus. */
    val caption: String,
    /** What the row reads; blank reads the current option's name. */
    val value: String,
    val options: List<NovaPlaySetupOption>,
    val enabled: Boolean = true,
    /**
     * This row holds a choice made here rather than the answer the host would have given: in This
     * Game, the accent dot, and "Set for this game" leading the caption.
     */
    val overridden: Boolean = false,
    /** The options are a scale, such as frame rates, so Left and Right stop at its ends (R1). */
    val ordered: Boolean = false,
    /**
     * The choices outgrow the row, or carry sentences that have to be read, so A opens their page
     * instead of stepping through them in place (R2).
     */
    val opensPage: Boolean = false,
    /** The unit an ordered strip names once at its end, such as FPS. */
    val unit: String = "",
)

/** Above and below a tile's two lines: 44dp holds a 14sp title and a 12sp caption. */
private val TileVertical = 4.dp
private val NovaPlaySetupDot = 6.dp
private val NovaPlaySetupUnderline = 2.dp
private val NovaPlaySetupWarningSize = 14.dp
private val NovaPlaySetupPillHeight = 24.dp
private val NovaPlaySetupPillMark = 14.dp
private val NOVA_PLAY_SETUP_FACT_KEY = 104.dp

/** The scrolling rows under the plan card. */
internal const val NOVA_PLAY_SETUP_ROWS_TAG = "nova-play-setup-rows"

/** The plan card, at the root and pinned on a page. */
internal const val NOVA_PLAY_SETUP_PLAN_CARD_TAG = "nova-play-setup-plan-card"

/** The scope pill in the header. */
internal const val NOVA_PLAY_SETUP_SCOPE_PILL_TAG = "nova-play-setup-scope-pill"

/**
 * Turn the launch profile summary into what the plan says.
 *
 * The summary's lines carry their own prefixes ("Requested: ", "Selected: ", "Limited by: ",
 * "Last: ") because they were written to stand alone in a list. Here the fact's key already says
 * which is which, so the prefix would print the word twice.
 *
 * `historyLines` is how the last session actually went, `requestedLine` is what was asked for as
 * against what was granted, and "how did it go last time" is the single most useful input to "how
 * do I want to play".
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
                // The evidence, not just the category. "Host Render" on its own is the residual
                // branch of Polaris' classifier, so without the measurement behind it there is
                // nothing a person can act on.
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
                // The why rides with the grant: "Granted: Recovery profile / 30 FPS · Held by
                // History Safe Profile" is the whole story in one fact.
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
    // The host's answer sits beside the game's, because the per-game choice outranks it and a
    // hierarchy is only legible when both ends are on screen.
    facts += hostFacts
    return NovaPlaySetupPlan(mode = modeLabel, lines = lines, facts = facts)
}

/**
 * Drop a "Key: " prefix that the fact's own key is about to say again.
 *
 * Only up to the first colon, and only when it is close enough to the start to be a label: a
 * value like "Recovery active from last session · 1 hr ago" has no prefix to remove, and one like
 * "12:30" must not lose its first half.
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
