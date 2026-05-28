package com.papi.nova.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
import com.papi.nova.ui.compose.novaFocusMotion
import kotlin.math.roundToInt

@Composable
fun NovaSettingsScreen(
    viewModel: NovaSettingsViewModel,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    onAction: (NovaSettingDefinition) -> Unit,
    headerActions: List<NovaSettingsHeaderAction> = emptyList()
) {
    val state by viewModel.uiState.collectAsState()
    var activeDialog by remember { mutableStateOf<NovaSettingsDialog?>(null) }

    NovaSettingsContent(
        state = state,
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        onOpenLegacy = onOpenLegacy,
        onSearch = viewModel::updateSearch,
        onClearSearch = viewModel::clearSearch,
        onCategory = viewModel::selectCategory,
        headerActions = headerActions,
        onResetSetting = viewModel::resetValue,
        onSetting = { definition ->
            when (definition.type) {
                NovaSettingType.Toggle -> viewModel.setValue(
                    definition,
                    NovaSettingValue.BooleanValue(!state.booleanValue(definition))
                )
                NovaSettingType.Select -> activeDialog = NovaSettingsDialog.Select(definition)
                NovaSettingType.Slider -> activeDialog = NovaSettingsDialog.Slider(definition)
                NovaSettingType.Text -> activeDialog = NovaSettingsDialog.Text(definition)
                NovaSettingType.Action -> onAction(definition)
            }
        }
    )

    activeDialog?.let { dialog ->
        NovaSettingDialog(
            dialog = dialog,
            state = state,
            onDismiss = { activeDialog = null },
            onSave = { definition, value ->
                viewModel.setValue(definition, value)
                activeDialog = null
            }
        )
    }
}

data class NovaSettingsHeaderAction(
    val label: String,
    val onClick: () -> Unit
)

private val NovaSettingsCardShape = RoundedCornerShape(14.dp)
private val NovaSettingsChipShape = RoundedCornerShape(12.dp)

private object NovaSettingsMetrics {
    fun categoryRailWidthDp(): Int = 196
    fun wideColumnSpacingDp(): Int = 14
    fun quickStripHeightDp(): Int = 52
    fun quickPillWidthDp(): Int = 144
    fun headerToQuickStripSpacingDp(): Int = 6
    fun quickStripToContentSpacingDp(): Int = 6
    fun contentToHintSpacingDp(): Int = 4
    fun categoryRailSpacingDp(): Int = 6
    fun categoryRowVerticalPaddingDp(): Int = 6
    fun settingsRowSpacingDp(): Int = 6
    fun settingsRowVerticalPaddingDp(): Int = 6
    fun rowsBottomPaddingDp(): Int = 12
    fun valueChipMinHeightDp(): Int = 28
}

@Composable
private fun NovaSettingsContent(
    state: NovaSettingsUiState,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onCategory: (String) -> Unit,
    headerActions: List<NovaSettingsHeaderAction>,
    onResetSetting: (NovaSettingDefinition) -> Unit,
    onSetting: (NovaSettingDefinition) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val controllerHints = novaSettingsControllerHints()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.window)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        NovaSettingsCompactHeader(
            title = title,
            subtitle = subtitle,
            query = state.searchQuery,
            onQuery = onSearch,
            onClear = onClearSearch,
            onBack = onBack,
            onOpenLegacy = onOpenLegacy,
            headerActions = headerActions,
            wide = wide
        )
        Spacer(Modifier.height(NovaSettingsMetrics.headerToQuickStripSpacingDp().dp))
        NovaSettingsQuickStrip(state, onSetting)
        Spacer(Modifier.height(NovaSettingsMetrics.quickStripToContentSpacingDp().dp))
        if (wide) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NovaSettingsMetrics.wideColumnSpacingDp().dp)
            ) {
                NovaSettingsCategoryRail(
                    state = state,
                    onCategory = onCategory,
                    modifier = Modifier
                        .width(NovaSettingsMetrics.categoryRailWidthDp().dp)
                        .fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    SearchResultSummary(state)
                    NovaSettingsRows(
                        state = state,
                        onSetting = onSetting,
                        onResetSetting = onResetSetting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        } else {
            NovaSettingsCategoryChips(state, onCategory)
            Spacer(Modifier.height(10.dp))
            SearchResultSummary(state)
            NovaSettingsRows(
                state = state,
                onSetting = onSetting,
                onResetSetting = onResetSetting,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
        Spacer(Modifier.height(NovaSettingsMetrics.contentToHintSpacingDp().dp))
        NovaControllerHintBar(
            hints = controllerHints,
            compact = wide,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun novaSettingsControllerHints(): List<NovaControllerHint> = listOf(
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_a),
        label = stringResource(R.string.nova_controller_hint_select)
    ),
    NovaControllerHint(
        key = stringResource(R.string.nova_controller_hint_b),
        label = stringResource(R.string.nova_controller_hint_back)
    )
)

@Composable
private fun NovaSettingsCompactHeader(
    title: String,
    subtitle: String,
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    headerActions: List<NovaSettingsHeaderAction>,
    wide: Boolean
) {
    val colors = LocalNovaComposeColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (wide) {
                NovaSettingsSearchField(
                    query = query,
                    onQuery = onQuery,
                    onClear = onClear,
                    modifier = Modifier.widthIn(min = 280.dp, max = 440.dp)
                )
            }
            for (action in headerActions) {
                TextButton(
                    onClick = action.onClick,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(action.label)
                }
            }
            TextButton(
                onClick = onOpenLegacy,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Legacy")
            }
        }
        if (!wide) {
            Spacer(Modifier.height(6.dp))
            NovaSettingsSearchField(
                query = query,
                onQuery = onQuery,
                onClear = onClear,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NovaSettingsSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val shape = NovaSettingsCardShape
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(if (focused) surfaces.selectedControl else surfaces.control)
            .border(if (focused) 3.dp else 1.dp, if (focused) surfaces.focusRing else surfaces.tileBorder, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .padding(horizontal = 12.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isBlank()) {
                        Text(
                            text = "Search settings",
                            color = colors.textMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
                if (query.isNotBlank()) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
    )
}

@Composable
private fun SearchResultSummary(state: NovaSettingsUiState) {
    if (!state.isSearchActive()) return

    val colors = LocalNovaComposeColors.current
    Text(
        text = "${state.searchResultCount} results",
        color = colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun NovaSettingsQuickStrip(
    state: NovaSettingsUiState,
    onSetting: (NovaSettingDefinition) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NovaSettingsMetrics.quickStripHeightDp().dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (definition in state.quickSettings) {
            NovaSettingPill(
                definition = definition,
                value = state.valueLabel(definition),
                onClick = { onSetting(definition) }
            )
        }
    }
}

@Composable
private fun NovaSettingPill(
    definition: NovaSettingDefinition,
    value: String,
    onClick: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val shape = NovaSettingsCardShape
    Column(
        modifier = Modifier
            .width(NovaSettingsMetrics.quickPillWidthDp().dp)
            .heightIn(min = NovaSettingsMetrics.quickStripHeightDp().dp)
            .clip(shape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(if (focused) surfaces.selectedControl else surfaces.control)
            .border(if (focused) 3.dp else 1.dp, if (focused) surfaces.focusRing else surfaces.tileBorder, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = definition.title,
            color = colors.textSecondary,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 13.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NovaSettingsCategoryRail(
    state: NovaSettingsUiState,
    onCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NovaSettingsMetrics.categoryRailSpacingDp().dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(state.categories, key = { it.key }) { category ->
            NovaCategoryRow(
                category = category,
                selected = category.key == state.selectedCategoryKey && state.searchQuery.isBlank(),
                onClick = { onCategory(category.key) }
            )
        }
    }
}

@Composable
private fun NovaSettingsCategoryChips(
    state: NovaSettingsUiState,
    onCategory: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (category in state.categories) {
            NovaCategoryRow(
                category = category,
                selected = category.key == state.selectedCategoryKey && state.searchQuery.isBlank(),
                onClick = { onCategory(category.key) },
                compact = true
            )
        }
    }
}

@Composable
private fun NovaCategoryRow(
    category: NovaSettingCategory,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val shape = NovaSettingsCardShape
    val background = when {
        selected || focused -> surfaces.selectedControl
        else -> surfaces.control
    }
    Column(
        modifier = Modifier
            .fillMaxWidth(if (compact) 0.48f else 1f)
            .clip(shape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(background)
            .border(if (focused) 3.dp else 1.dp, if (focused) surfaces.focusRing else surfaces.tileBorder, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 12.dp, vertical = NovaSettingsMetrics.categoryRowVerticalPaddingDp().dp)
    ) {
        Text(
            text = category.title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact && category.summary.isNotBlank()) {
            Text(
                text = category.summary,
                color = colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NovaSettingsRows(
    state: NovaSettingsUiState,
    onSetting: (NovaSettingDefinition) -> Unit,
    onResetSetting: (NovaSettingDefinition) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NovaSettingsMetrics.settingsRowSpacingDp().dp),
        contentPadding = PaddingValues(bottom = NovaSettingsMetrics.rowsBottomPaddingDp().dp)
    ) {
        items(state.visibleSettings, key = { it.key }, contentType = { it.type }) { definition ->
            NovaSettingRow(
                definition = definition,
                value = state.valueLabel(definition),
                checked = state.booleanValue(definition),
                enabled = state.isEnabled(definition),
                isOverride = state.isOverride(definition),
                canReset = state.canReset(definition),
                onReset = { onResetSetting(definition) },
                onClick = { onSetting(definition) }
            )
        }
    }
}

@Composable
private fun NovaSettingRow(
    definition: NovaSettingDefinition,
    value: String,
    checked: Boolean,
    enabled: Boolean,
    isOverride: Boolean,
    canReset: Boolean,
    onReset: () -> Unit,
    onClick: () -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    var focused by remember { mutableStateOf(false) }
    val shape = NovaSettingsCardShape
    val alpha = if (enabled) 1f else 0.44f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .novaFocusMotion(focused = focused, pressed = false)
            .background(if (focused) surfaces.selectedControl else Color.Transparent)
            .border(if (focused) 3.dp else 1.dp, if (focused) surfaces.focusRing else surfaces.panelBorder, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled = enabled)
            .padding(horizontal = 12.dp, vertical = NovaSettingsMetrics.settingsRowVerticalPaddingDp().dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = definition.title,
                    color = colors.textPrimary.copy(alpha = alpha),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOverride) {
                    NovaSettingOverrideBadge(alpha = alpha)
                }
                NovaSettingApplyBadge(definition.applyTiming, alpha)
            }
            if (definition.summary.isNotBlank() && definition.summary != "%s") {
                Text(
                    text = definition.summary,
                    color = colors.textMuted.copy(alpha = alpha),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canReset) {
                TextButton(
                    onClick = onReset,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Reset")
                }
            }
            if (definition.type == NovaSettingType.Toggle) {
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            } else {
                NovaSettingValueChip(value = value, alpha = alpha)
            }
        }
    }
}

@Composable
private fun NovaSettingOverrideBadge(alpha: Float) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = NovaSettingsChipShape
    Text(
        text = "Override",
        color = colors.textPrimary.copy(alpha = alpha),
        fontSize = 10.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(surfaces.selectedControl.copy(alpha = alpha))
            .border(1.dp, surfaces.focusRing.copy(alpha = alpha), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun NovaSettingApplyBadge(
    timing: NovaSettingApplyTiming,
    alpha: Float
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = NovaSettingsChipShape
    Text(
        text = timing.label,
        color = colors.textMuted.copy(alpha = alpha),
        fontSize = 10.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(surfaces.control.copy(alpha = alpha))
            .border(1.dp, surfaces.tileBorder.copy(alpha = alpha), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun NovaSettingValueChip(
    value: String,
    alpha: Float
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val shape = NovaSettingsChipShape
    Box(
        modifier = Modifier
            .widthIn(min = 92.dp, max = 220.dp)
            .heightIn(min = NovaSettingsMetrics.valueChipMinHeightDp().dp)
            .clip(shape)
            .background(surfaces.control.copy(alpha = alpha))
            .border(1.dp, surfaces.tileBorder.copy(alpha = alpha), shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = value,
            color = colors.textSecondary.copy(alpha = alpha),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NovaSettingDialog(
    dialog: NovaSettingsDialog,
    state: NovaSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (NovaSettingDefinition, NovaSettingValue) -> Unit
) {
    when (dialog) {
        is NovaSettingsDialog.Select -> NovaSelectDialog(dialog.definition, state, onDismiss, onSave)
        is NovaSettingsDialog.Slider -> NovaSliderDialog(dialog.definition, state, onDismiss, onSave)
        is NovaSettingsDialog.Text -> NovaTextDialog(dialog.definition, state, onDismiss, onSave)
    }
}

@Composable
private fun NovaSelectDialog(
    definition: NovaSettingDefinition,
    state: NovaSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (NovaSettingDefinition, NovaSettingValue) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(definition.title) },
        text = {
            LazyColumn {
                items(definition.options, key = { it.value }) { option ->
                    Text(
                        text = option.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSave(definition, NovaSettingValue.StringValue(option.value))
                            }
                            .padding(vertical = 12.dp),
                        color = if (state.stringValue(definition) == option.value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun NovaSliderDialog(
    definition: NovaSettingDefinition,
    state: NovaSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (NovaSettingDefinition, NovaSettingValue) -> Unit
) {
    var value by remember(definition.key) {
        mutableStateOf(state.intValue(definition).toFloat())
    }
    val min = definition.min?.toFloat() ?: 0f
    val max = definition.max?.toFloat() ?: 100f
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(definition, NovaSettingValue.IntValue(value.roundToInt())) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(definition.title) },
        text = {
            Column {
                Text(state.formatSliderValue(definition, value.roundToInt()))
                Spacer(Modifier.height(16.dp))
                Slider(
                    value = value.coerceIn(min, max),
                    onValueChange = { value = it },
                    valueRange = min..max
                )
            }
        }
    )
}

@Composable
private fun NovaTextDialog(
    definition: NovaSettingDefinition,
    state: NovaSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (NovaSettingDefinition, NovaSettingValue) -> Unit
) {
    var value by remember(definition.key) { mutableStateOf(state.stringValue(definition)) }
    val valid = NovaSettingsValidator.isValidTextValue(definition.key, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(definition, NovaSettingValue.StringValue(value.trim())) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(definition.title) },
        text = {
            Column {
                if (definition.risk != NovaSettingRisk.Normal) {
                    NovaRiskWarning(definition)
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    isError = !valid
                )
                if (!valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = validationMessage(definition.key),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        }
    )
}

@Composable
private fun NovaRiskWarning(definition: NovaSettingDefinition) {
    val message = when (definition.key) {
        PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING ->
            "Use width x height, such as 1920x1080. Unsupported modes may fail to start."
        PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING ->
            "Use a refresh rate your display and host can actually present."
        PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING ->
            "Use Mbps values that fit your network. Too high can cause stutter or disconnects."
        else -> "This setting can affect stream reliability. Confirm the value before saving."
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp
    )
}

private fun validationMessage(key: String): String {
    return when (key) {
        PreferenceConfiguration.CUSTOM_RESOLUTION_PREF_STRING -> "Enter a resolution like 1920x1080."
        PreferenceConfiguration.CUSTOM_REFRESH_RATE_PREF_STRING -> "Enter a refresh rate from 1 to 240."
        PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING -> "Enter a bitrate from 1 to 300 Mbps."
        else -> "Enter a valid value before saving."
    }
}

private sealed interface NovaSettingsDialog {
    data class Select(val definition: NovaSettingDefinition) : NovaSettingsDialog
    data class Slider(val definition: NovaSettingDefinition) : NovaSettingsDialog
    data class Text(val definition: NovaSettingDefinition) : NovaSettingsDialog
}

private fun NovaSettingsUiState.valueLabel(definition: NovaSettingDefinition): String {
    val value = values[definition.key] ?: definition.defaultValue
    return when (value) {
        is NovaSettingValue.BooleanValue -> if (value.value) "On" else "Off"
        is NovaSettingValue.IntValue -> formatSliderValue(definition, value.value)
        is NovaSettingValue.StringValue -> {
            definition.options.firstOrNull { it.value == value.value }?.label ?: value.value
        }
        is NovaSettingValue.StringSetValue -> value.value.joinToString(", ")
        null -> "Set"
    }
}

private fun NovaSettingsUiState.booleanValue(definition: NovaSettingDefinition): Boolean {
    val value = values[definition.key] ?: definition.defaultValue
    return (value as? NovaSettingValue.BooleanValue)?.value ?: false
}

private fun NovaSettingsUiState.intValue(definition: NovaSettingDefinition): Int {
    val value = values[definition.key] ?: definition.defaultValue
    return (value as? NovaSettingValue.IntValue)?.value ?: definition.min ?: 0
}

private fun NovaSettingsUiState.stringValue(definition: NovaSettingDefinition): String {
    val value = values[definition.key] ?: definition.defaultValue
    return (value as? NovaSettingValue.StringValue)?.value.orEmpty()
}

private fun NovaSettingsUiState.isEnabled(definition: NovaSettingDefinition): Boolean {
    val dependency = definition.dependencyKey ?: return true
    val value = values[dependency]
    return (value as? NovaSettingValue.BooleanValue)?.value ?: true
}

private fun NovaSettingsUiState.formatSliderValue(
    definition: NovaSettingDefinition,
    value: Int
): String {
    return when (definition.key) {
        PreferenceConfiguration.BITRATE_PREF_STRING,
        "seekbar_metered_bitrate_kbps" -> if (value == 0) "Auto" else "${value / 1000} Mbps"
        else -> value.toString() + definition.suffix.orEmpty()
    }
}
