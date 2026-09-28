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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaRadius

/**
 * Draws a [NovaCommonPage]. [leave] pops the page, or closes the panel at the root; every page
 * leaves first and then runs its callback, so a callback that opens something lands on top.
 */
@Composable
internal fun NovaPageScope.NovaCommonPageContent(page: NovaCommonPage, leave: () -> Unit) {
    when (page) {
        is NovaCommonPage.Choice<*> -> ChoicePage(page, leave)
        is NovaCommonPage.MultiChoice<*> -> MultiChoicePage(page, leave)
        is NovaCommonPage.Menu -> MenuPage(page)
        is NovaCommonPage.Confirm -> ConfirmPage(page, leave)
        is NovaCommonPage.Notice -> NoticePage(page, leave)
        is NovaCommonPage.Form -> FormPage(page, leave)
        is NovaCommonPage.Slider -> SliderPage(page, leave)
        is NovaCommonPage.Busy -> BusyPage(page)
    }
}

/** Rows in the page's own list, with content padding so no row is cut at rest. */
@Composable
private fun NovaPageScope.PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/** A short page that is not a list: scrolls if it must, padded like one. */
@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) { content() }
}

@Composable
private fun <T> NovaPageScope.ChoicePage(page: NovaCommonPage.Choice<T>, leave: () -> Unit) {
    val currentIndex = page.options.indexOfFirst { it.value == page.current }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Opens on the current option, even one far down the list, with a row of context above it.
        if (!scrolled && currentIndex > 1) listState.scrollToItem(currentIndex - 1)
        scrolled = true
    }
    PageList {
        itemsIndexed(page.options, key = { index, _ -> index }) { index, option ->
            val initial = index == currentIndex || (currentIndex < 0 && index == 0)
            NovaRowLayout(
                title = option.label,
                caption = option.caption,
                disabledReason = option.disabledReason,
                trailing = if (index == currentIndex) NovaRowTrailing.Current else NovaRowTrailing.None,
                onClick = {
                    leave()
                    page.onChoose(option.value)
                },
                leading = page.leading?.let { draw -> { draw(option) } },
                modifier = Modifier
                    .then(if (initial) Modifier.novaInitialFocus() else Modifier)
                    .novaRestorableFocus(index, index),
            )
        }
    }
}

@Composable
private fun <T> NovaPageScope.MultiChoicePage(page: NovaCommonPage.MultiChoice<T>, leave: () -> Unit) {
    val chosen = remember(page) { mutableStateMapOf<Int, Boolean>() }
    fun isChosen(index: Int) = chosen[index] ?: (page.options[index].value in page.selected)
    PageList {
        itemsIndexed(page.options, key = { index, _ -> index }) { index, option ->
            NovaRowLayout(
                title = option.label,
                caption = option.caption,
                disabledReason = option.disabledReason,
                trailing = if (isChosen(index)) NovaRowTrailing.Current else NovaRowTrailing.None,
                onClick = { chosen[index] = !isChosen(index) },
                modifier = Modifier
                    .then(if (index == 0) Modifier.novaInitialFocus() else Modifier)
                    .novaRestorableFocus(index, index),
            )
        }
        item(key = "done") {
            NovaActionButton(
                text = page.doneLabel,
                primary = true,
                onClick = {
                    val result = page.options.filterIndexed { index, _ -> isChosen(index) }.map { it.value }.toSet()
                    leave()
                    page.onDone(result)
                },
                modifier = Modifier.fillMaxWidth().novaRestorableFocus("done", page.options.size),
                minHeight = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current),
            )
        }
    }
}

@Composable
private fun NovaPageScope.MenuPage(page: NovaCommonPage.Menu) {
    val firstFocusable = page.items.indexOfFirst { it !is NovaMenuItem.Action || it.disabledReason == null }
        .coerceAtLeast(0)
    PageList {
        page.header?.let { header ->
            item(key = "header") { MenuHeader(header) }
        }
        itemsIndexed(page.items, key = { _, item -> item.key }) { index, item ->
            val listIndex = index + if (page.header != null) 1 else 0
            val focus = Modifier
                .then(if (index == firstFocusable) Modifier.novaInitialFocus() else Modifier)
                .novaRestorableFocus(item.key, listIndex)
            MenuItem(item, focus)
        }
    }
}

@Composable
private fun NovaPageScope.MenuItem(item: NovaMenuItem, modifier: Modifier) {
    when (item) {
        is NovaMenuItem.Action -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            emphasis = item.emphasis,
            disabledReason = item.disabledReason,
            onClick = { if (item.closesPanel) closeThen(action = item.onClick) else item.onClick() },
            modifier = modifier,
        )
        is NovaMenuItem.Opens -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            trailing = item.value?.let { NovaRowTrailing.Value(it) } ?: NovaRowTrailing.Opens,
            onClick = { panel.push(item.page()) },
            modifier = modifier,
        )
        is NovaMenuItem.Destructive -> NovaSplitConfirm(
            label = item.label,
            confirmLabel = item.confirmLabel,
            onConfirm = item.onConfirm,
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
private fun NovaPageScope.ConfirmPage(page: NovaCommonPage.Confirm, leave: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val stay = {
        leave()
        page.onStay()
    }
    NovaBackHandler(active = true, onBack = stay)
    PageColumn {
        Text(text = page.message, style = novaPanelType.rowTitle, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SplitGap)) {
            NovaActionButton(
                text = page.stayLabel,
                onClick = stay,
                modifier = Modifier.weight(1f).novaInitialFocus(),
                minHeight = NovaPanelMetrics.ButtonMinHeight,
            )
            NovaActionButton(
                text = page.actionLabel,
                destructive = page.destructive,
                primary = !page.destructive,
                onClick = {
                    leave()
                    page.onConfirm()
                },
                modifier = Modifier.weight(1f),
                minHeight = NovaPanelMetrics.ButtonMinHeight,
            )
        }
    }
}

@Composable
private fun NovaPageScope.NoticePage(page: NovaCommonPage.Notice, leave: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val close = {
        leave()
        page.onClose()
    }
    NovaBackHandler(active = true, onBack = close)
    val buttonHeight = NovaPanelMetrics.ButtonMinHeight
    PageColumn {
        Text(
            text = page.message,
            style = novaPanelType.caption,
            fontFamily = if (page.monospace) FontFamily.Monospace else null,
            color = colors.textSecondary,
        )
        page.primary?.let { primary ->
            NovaActionButton(
                text = primary.label,
                primary = true,
                destructive = primary.destructive,
                onClick = {
                    leave()
                    primary.run()
                },
                modifier = Modifier.fillMaxWidth().novaInitialFocus(),
                minHeight = buttonHeight,
            )
        }
        page.help?.let { help ->
            NovaActionButton(
                text = help.label,
                onClick = {
                    leave()
                    help.run()
                },
                modifier = Modifier.fillMaxWidth(),
                minHeight = buttonHeight,
            )
        }
        NovaActionButton(
            text = page.closeLabel,
            onClick = close,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (page.primary == null) Modifier.novaInitialFocus() else Modifier),
            minHeight = buttonHeight,
        )
    }
}

@Composable
private fun NovaPageScope.FormPage(page: NovaCommonPage.Form, leave: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val focusManager = LocalFocusManager.current
    val values = remember(page) { mutableStateMapOf<String, String>().apply { page.fields.forEach { put(it.key, it.initial) } } }
    var error by remember(page) { mutableStateOf<String?>(null) }
    val openedByTouch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val submit = {
        val result = page.onSubmit(page.fields.associate { it.key to values[it.key].orEmpty() })
        if (result == null) leave() else error = result
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
        NovaActionButton(
            text = page.submitLabel,
            primary = true,
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            minHeight = NovaPanelMetrics.ButtonMinHeight,
        )
        page.warning?.let { Text(text = it, style = novaPanelType.caption, color = colors.warning) }
    }
}

@Composable
private fun NovaPageScope.SliderPage(page: NovaCommonPage.Slider, leave: () -> Unit) {
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
        NovaTextField(
            value = typed,
            onValueChange = { text ->
                typed = text.filter(Char::isDigit)
                typed.toIntOrNull()?.let { value = it.coerceIn(page.range) }
            },
            label = stringResource(R.string.nova_panel_exact_value),
            kind = NovaFieldKind.Number,
            maxLength = page.range.last.toString().length,
            modifier = Modifier.fillMaxWidth(),
        )
        NovaActionButton(
            text = stringResource(R.string.nova_panel_save),
            primary = true,
            onClick = {
                leave()
                page.onSave(value)
            },
            modifier = Modifier.fillMaxWidth(),
            minHeight = NovaPanelMetrics.ButtonMinHeight,
        )
    }
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
            .novaFocusRing(shape)
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
private fun NovaPageScope.BusyPage(page: NovaCommonPage.Busy) {
    val colors = LocalNovaComposeColors.current
    val message by page.message.collectAsState()
    val cancel = page.cancel
    // Without a cancel, the page holds B so the work cannot be left half done.
    NovaBackHandler(active = true) { cancel?.run?.invoke() }
    PageColumn {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        ) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = NovaPanelMetrics.ProgressStroke,
                modifier = Modifier.size(NovaPanelMetrics.ProgressSize),
            )
            Text(
                text = message,
                style = novaPanelType.rowTitle,
                color = colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (cancel != null) {
            NovaActionButton(
                text = cancel.label,
                onClick = cancel.run,
                modifier = Modifier.fillMaxWidth().novaInitialFocus(),
                minHeight = NovaPanelMetrics.ButtonMinHeight,
            )
        } else {
            Box(modifier = Modifier.novaInitialFocus().novaClickable(onClick = {}))
        }
    }
}
