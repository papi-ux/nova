package com.papi.nova.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaRadius

/**
 * Draws a [NovaCommonPage]. [exit] pops the page, or closes the panel at the root; every page
 * leaves first and then runs its callback, so a callback that opens something lands on top. It
 * also keeps a page that is no longer on top from acting at all.
 */
@Composable
internal fun NovaPageScope.NovaCommonPageContent(page: NovaCommonPage, exit: NovaPageExit) {
    when (page) {
        is NovaCommonPage.Choice<*> -> ChoicePage(page, exit)
        is NovaCommonPage.MultiChoice<*> -> MultiChoicePage(page, exit)
        is NovaCommonPage.Menu -> MenuPage(page, exit)
        is NovaCommonPage.Confirm -> ConfirmPage(page, exit)
        is NovaCommonPage.Notice -> NoticePage(page, exit)
        is NovaCommonPage.Form -> FormPage(page, exit)
        is NovaCommonPage.Slider -> SliderPage(page, exit)
        is NovaCommonPage.Busy -> BusyPage(page, exit)
    }
}

/**
 * Rows in the page's own list, with content padding so no row is cut at rest, and its edges
 * faded while more lies past them.
 */
@Composable
private fun NovaPageScope.PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth().novaScrollEdgeFade(listState),
        content = content,
    )
}

/** A short page that is not a list: scrolls if it must, padded like one, its edges faded. */
@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .novaScrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) { content() }
}

@Composable
private fun <T> NovaPageScope.ChoicePage(page: NovaCommonPage.Choice<T>, exit: NovaPageExit) {
    val currentIndex = page.options.indexOfFirst { it.value == page.current }
    val columns = novaPanelColumns(page.width)
    // Opens on the current option, even one far down the list that has not composed yet: the host
    // scrolls there first, with a row of context above it. A current value that is no longer an
    // option opens on the first row.
    if (currentIndex >= 0) novaInitialFocusAt(currentIndex, currentIndex / columns)
    val lines = remember(page.options.size, columns) { novaGridRows(page.options.indices.toList(), columns) }
    PageList {
        itemsIndexed(lines, key = { line, _ -> line }) { line, indices ->
            NovaGridRow(indices, columns) { index, cell ->
                val option = page.options[index]
                val initial = index == currentIndex || (currentIndex < 0 && index == 0)
                NovaRowLayout(
                    title = option.label,
                    caption = option.caption,
                    disabledReason = option.disabledReason,
                    trailing = if (index == currentIndex) NovaRowTrailing.Current else NovaRowTrailing.None,
                    onClick = { exit.leaveThen { page.onChoose(option.value) } },
                    leading = page.leading?.let { draw -> { draw(option) } },
                    modifier = cell
                        .then(if (initial) Modifier.novaInitialFocus() else Modifier)
                        .novaRestorableFocus(index, line),
                )
            }
        }
    }
}

@Composable
private fun <T> NovaPageScope.MultiChoicePage(page: NovaCommonPage.MultiChoice<T>, exit: NovaPageExit) {
    val chosen = remember(page) { mutableStateMapOf<Int, Boolean>() }
    fun isChosen(index: Int) = chosen[index] ?: (page.options[index].value in page.selected)
    PageList {
        itemsIndexed(page.options, key = { index, _ -> index }) { index, option ->
            NovaRowLayout(
                title = option.label,
                caption = option.caption,
                disabledReason = option.disabledReason,
                checked = isChosen(index),
                onClick = { exit.act { chosen[index] = !isChosen(index) } },
                modifier = Modifier
                    .then(if (index == 0) Modifier.novaInitialFocus() else Modifier)
                    .novaRestorableFocus(index, index),
            )
        }
        item(key = "done") {
            NovaPanelButton(
                text = page.doneLabel,
                primary = true,
                onClick = {
                    val result = page.options.filterIndexed { index, _ -> isChosen(index) }.map { it.value }.toSet()
                    exit.leaveThen { page.onDone(result) }
                },
                modifier = Modifier.fillMaxWidth().novaRestorableFocus("done", page.options.size),
            )
        }
    }
}

@Composable
private fun NovaPageScope.MenuPage(page: NovaCommonPage.Menu, exit: NovaPageExit) {
    val firstFocusable = page.items.firstOrNull { it !is NovaMenuItem.Action || it.disabledReason == null }
        ?: page.items.firstOrNull()
    val columns = novaPanelColumns(page.width)
    // Two to a line on a compact landscape panel, in the items' own order.
    val lines = remember(page.items, columns) { novaGridRows(page.items, columns) { it.standsAlone() } }
    PageList {
        page.header?.let { header ->
            item(key = "header") { MenuHeader(header) }
        }
        itemsIndexed(lines, key = { _, cells -> cells.joinToString(GridKeySeparator) { it.key } }) { line, items ->
            val listIndex = line + if (page.header != null) 1 else 0
            NovaGridRow(items, if (items.singleOrNull()?.standsAlone() == true) 1 else columns) { item, cell ->
                val focus = cell
                    .then(if (item === firstFocusable) Modifier.novaInitialFocus() else Modifier)
                    .novaRestorableFocus(item.key, listIndex)
                MenuItem(item, focus, exit)
            }
        }
    }
}

/**
 * Whether a menu item keeps a line of its own in a grid: the primary, a destructive split, whose
 * armed pair and warning need the width (R3), and a value changed in place.
 */
private fun NovaMenuItem.standsAlone(): Boolean = when (this) {
    is NovaMenuItem.Action -> emphasis
    is NovaMenuItem.Destructive, is NovaMenuItem.Value<*> -> true
    is NovaMenuItem.Opens -> false
}

private const val GridKeySeparator = "+"

@Composable
private fun NovaPageScope.MenuItem(item: NovaMenuItem, modifier: Modifier, exit: NovaPageExit) {
    when (item) {
        is NovaMenuItem.Action -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            emphasis = item.emphasis,
            disabledReason = item.disabledReason,
            onClick = { exit.act { if (item.closesPanel) closeThen(action = item.onClick) else item.onClick() } },
            modifier = modifier,
        )
        is NovaMenuItem.Opens -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            trailing = item.value?.let { NovaRowTrailing.Value(it) } ?: NovaRowTrailing.Opens,
            onClick = { exit.act { panel.push(item.page()) } },
            modifier = modifier,
        )
        is NovaMenuItem.Destructive -> NovaSplitConfirm(
            label = item.label,
            confirmLabel = item.confirmLabel,
            onConfirm = item.onConfirm,
            stayLabel = item.stayLabel ?: stringResource(R.string.nova_panel_stay),
            consequence = item.consequence,
            icon = item.icon,
            shape = NovaSplitShape.Row,
            modifier = modifier.fillMaxWidth(),
        )
        is NovaMenuItem.Value<*> -> MenuValue(item, modifier)
    }
}

/**
 * A menu's items are a snapshot, so the row shows a change at once and follows [NovaMenuItem.Value.current]
 * again whenever the owner rebuilds the menu with a new one.
 */
@Composable
private fun <T> MenuValue(item: NovaMenuItem.Value<T>, modifier: Modifier) {
    var shown by remember(item.current) { mutableStateOf(item.current) }
    NovaValueRow(
        title = item.label,
        options = item.options,
        current = shown,
        onChange = {
            shown = it
            item.onChange(it)
        },
        style = item.style,
        modifier = modifier,
    )
}

@Composable
private fun MenuHeader(header: NovaMenuHeader) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        modifier = Modifier.padding(bottom = NovaPanelMetrics.SpaceSm),
    ) {
        header.icon?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(NovaPanelMetrics.IconSize),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
            Text(text = header.title, style = type.rowTitle, color = colors.textPrimary)
            header.status?.let { Text(text = it, style = type.caption, color = header.tone.color()) }
            header.hint?.let { Text(text = it, style = type.caption, color = colors.textSecondary) }
        }
    }
}

@Composable
private fun NovaTone.color() = LocalNovaComposeColors.current.let { colors ->
    when (this) {
        NovaTone.Neutral -> colors.textSecondary
        NovaTone.Info, NovaTone.Active -> colors.accent
        NovaTone.Warning -> colors.warning
        NovaTone.Danger -> colors.destructive
    }
}

@Composable
private fun NovaPageScope.ConfirmPage(page: NovaCommonPage.Confirm, exit: NovaPageExit) {
    val colors = LocalNovaComposeColors.current
    val stay = { exit.leaveThen(page.onStay) }
    NovaBackHandler(active = true, onBack = stay)
    PageColumn {
        Text(text = page.message, style = novaPanelType.rowTitle, color = colors.textSecondary)
        NovaPanelButtonPair(
            first = { NovaPanelButton(text = page.stayLabel, onClick = stay, modifier = Modifier.novaInitialFocus()) },
            second = {
                // A destructive answer looks as the armed half of a split does: the destructive
                // fill, its own label colour and the close mark, so one action has one look.
                NovaPanelButton(
                    text = page.actionLabel,
                    destructive = page.destructive,
                    primary = true,
                    icon = if (page.destructive) R.drawable.ic_close else null,
                    onClick = { exit.leaveThen(page.onConfirm) },
                )
            },
        )
    }
}

@Composable
private fun NovaPageScope.NoticePage(page: NovaCommonPage.Notice, exit: NovaPageExit) {
    val colors = LocalNovaComposeColors.current
    val close = { exit.leaveThen(page.onClose) }
    NovaBackHandler(active = true, onBack = close)
    PageColumn {
        // A notice carries whole error messages, so it reads at the size every other message does.
        Text(
            text = page.message,
            style = novaPanelType.rowTitle,
            fontFamily = if (page.monospace) FontFamily.Monospace else null,
            color = colors.textSecondary,
        )
        page.primary?.let { primary ->
            NovaPanelButton(
                text = primary.label,
                primary = true,
                destructive = primary.destructive,
                onClick = { exit.leaveThen(primary.run) },
                modifier = Modifier.fillMaxWidth().novaInitialFocus(),
            )
        }
        page.help?.let { help ->
            NovaPanelButton(
                text = help.label,
                onClick = { exit.leaveThen(help.run) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        NovaPanelButton(
            text = page.closeLabel,
            onClick = close,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (page.primary == null) Modifier.novaInitialFocus() else Modifier),
        )
    }
}

@Composable
private fun NovaPageScope.FormPage(page: NovaCommonPage.Form, exit: NovaPageExit) {
    val colors = LocalNovaComposeColors.current
    val focusManager = LocalFocusManager.current
    val values = remember(page) { mutableStateMapOf<String, String>().apply { page.fields.forEach { put(it.key, it.initial) } } }
    var error by remember(page) { mutableStateOf<String?>(null) }
    val openedByTouch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val submit = {
        exit.act {
            val result = page.onSubmit(page.fields.associate { it.key to values[it.key].orEmpty() })
            if (result == null) exit.leaveThen() else error = result
        }
    }
    PageColumn {
        page.fields.forEachIndexed { index, field ->
            val last = index == page.fields.lastIndex
            NovaTextField(
                value = values[field.key].orEmpty(),
                onValueChange = {
                    values[field.key] = it
                    error = null
                },
                label = field.label,
                kind = field.kind,
                maxLength = field.maxLength,
                error = if (index == 0) error else null,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
                onImeAction = { if (last) submit() else focusManager.moveFocus(FocusDirection.Down) },
                openOnStart = index == 0 && openedByTouch,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.novaInitialFocus() else Modifier),
            )
            field.hint?.let { Text(text = it, style = novaPanelType.caption, color = colors.textSecondary) }
        }
        NovaPanelButton(
            text = page.submitLabel,
            primary = true,
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
        )
        page.warning?.let { Text(text = it, style = novaPanelType.caption, color = colors.warning) }
    }
}

@Composable
private fun NovaPageScope.SliderPage(page: NovaCommonPage.Slider, exit: NovaPageExit) {
    var value by rememberSaveable(page.key) { mutableIntStateOf(page.value.coerceIn(page.range)) }
    var typed by remember(page) { mutableStateOf(value.toString()) }
    LaunchedEffect(value, isTop) { if (isTop) page.onPreview?.invoke(value) }
    PageColumn {
        NovaSliderTrack(
            value = value,
            range = page.range,
            step = page.step,
            label = page.format(value),
            onChange = {
                value = it
                typed = it.toString()
            },
            modifier = Modifier.novaInitialFocus(),
        )
        // A range that goes below zero, such as trackpad sensitivity at -200 to 200, takes a minus
        // sign as well as digits: without it a negative value could be stepped to but not typed.
        val negatives = page.range.first < 0
        NovaTextField(
            value = typed,
            onValueChange = { text ->
                typed = novaExactValueText(text, negatives)
                typed.toIntOrNull()?.let { value = it.coerceIn(page.range) }
            },
            label = stringResource(R.string.nova_panel_exact_value),
            kind = if (negatives) NovaFieldKind.SignedNumber else NovaFieldKind.Number,
            maxLength = maxOf(page.range.first.toString().length, page.range.last.toString().length),
            modifier = Modifier.fillMaxWidth(),
        )
        NovaPanelButton(
            text = stringResource(R.string.nova_panel_save),
            primary = true,
            onClick = { exit.leaveThen { page.onSave(value) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * What an exact number field keeps of [text]: its digits, and a leading minus sign when [negatives]
 * says the range goes below zero. A lone "-" is kept so a negative can be typed a key at a time;
 * it reads as no number yet and leaves the value where it was.
 */
internal fun novaExactValueText(text: String, negatives: Boolean): String {
    val digits = text.filter(Char::isDigit)
    return if (negatives && text.trimStart().startsWith("-")) "-$digits" else digits
}

/** A focused track moved with Left and Right by [step], stopping at the ends of [range]. */
@Composable
private fun NovaSliderTrack(value: Int, range: IntRange, step: Int, label: String, onChange: (Int) -> Unit, modifier: Modifier) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = RoundedCornerShape(NovaRadius.row)
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = (value - range.first).toFloat() / span
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            // Left and Right move it; A has nothing to do on the track.
            .novaFocusHint(NovaFocusHint.Change)
            .semantics(mergeDescendants = true) { stateDescription = label }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onChange((value + direction * step).coerceIn(range))
                true
            }
            .novaClickable(onClick = {})
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        Text(text = label, style = novaPanelType.value, color = colors.textPrimary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NovaPanelMetrics.SliderTrackHeight)
                .clip(RoundedCornerShape(NovaRadius.pill))
                .novaTrackFill(fraction, surfaces.control, colors.accent),
        )
    }
}

/** The track, with the share of [fraction] filled from the start. */
private fun Modifier.novaTrackFill(fraction: Float, track: Color, fill: Color): Modifier = drawBehind {
    drawRect(track)
    drawRect(fill, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
}

@Composable
private fun NovaPageScope.BusyPage(page: NovaCommonPage.Busy, exit: NovaPageExit) {
    val colors = LocalNovaComposeColors.current
    val working = stringResource(R.string.nova_panel_working)
    val message by page.message.collectAsState()
    val cancel = page.cancel
    // Without a cancel, the page holds B so the work cannot be left half done.
    NovaBackHandler(active = true) { cancel?.let { exit.act(it.run) } }
    PageColumn {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        ) {
            NovaBusyMark()
            Text(
                text = message,
                style = novaPanelType.rowTitle,
                color = colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (cancel != null) {
            NovaPanelButton(
                text = cancel.label,
                onClick = { exit.act(cancel.run) },
                modifier = Modifier.fillMaxWidth().novaInitialFocus(),
            )
        } else {
            // Holds focus so A and B land here, where they do nothing.
            Box(
                modifier = Modifier
                    .novaInitialFocus()
                    .semantics { contentDescription = working }
                    .novaClickable(onClick = {}),
            )
        }
    }
}

/**
 * A still mark for work in progress: an accent arc on its track. No infinite animation runs inside
 * a panel, which can sit over the stream and would cost it frames.
 */
@Composable
private fun NovaBusyMark() {
    val colors = LocalNovaComposeColors.current
    val working = stringResource(R.string.nova_panel_working)
    Box(
        modifier = Modifier
            .size(NovaPanelMetrics.ProgressSize)
            .semantics { contentDescription = working }
            .drawBehind {
                val stroke = NovaPanelMetrics.ProgressStroke.toPx()
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.divider, 0f, FullTurn, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
                drawArc(
                    colors.accent,
                    NovaPanelMetrics.BusyArcStart,
                    NovaPanelMetrics.BusyArcSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
    )
}

private const val FullTurn = 360f
