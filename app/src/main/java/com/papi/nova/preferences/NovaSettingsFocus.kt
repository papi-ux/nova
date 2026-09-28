package com.papi.nova.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal data class NovaSettingsFocus(
    val railState: LazyListState,
    val rowsState: LazyListState,
    val categoryModifier: (NovaSettingCategory) -> Modifier,
    val rowModifier: (NovaSettingDefinition) -> Modifier,
    val paneModifier: Modifier,
    val quickStripModifier: Modifier,
    val firstQuickModifier: Modifier,
)

private data class SettingsFocusDestination(val category: String, val enterPane: Boolean)

/** Explicit rail/pane boundaries; vertical traversal within each list stays with Compose. */
@Composable
internal fun rememberNovaSettingsFocus(
    state: NovaSettingsUiState,
    onCategory: (String) -> Unit,
): NovaSettingsFocus {
    val categories = remember(state.categories.map { it.key }) {
        state.categories.associate { it.key to FocusRequester() }
    }
    val rows = remember(state.visibleSettings.map { it.key }) {
        state.visibleSettings.associate { it.key to FocusRequester() }
    }
    val railState = rememberLazyListState()
    val paneKey = state.selectedCategoryKey to state.searchQuery
    val rowsState = remember(paneKey) { LazyListState() }
    val lastRows = remember { mutableMapOf<Pair<String, String>, String>() }
    val firstQuick = remember { FocusRequester() }
    var destination by remember { mutableStateOf<SettingsFocusDestination?>(null) }

    // Scroll before requesting focus so a remembered row/category can be off screen.
    // The effect also waits for a newly focused category's pane to be composed.
    LaunchedEffect(destination, state.selectedCategoryKey, state.visibleSettings) {
        val target = destination ?: return@LaunchedEffect
        if (target.enterPane) {
            if (target.category != state.selectedCategoryKey) return@LaunchedEffect
            val enabledRows = state.visibleSettings.filter { state.isEnabled(it) }
            val row = enabledRows.firstOrNull { it.key == lastRows[paneKey] }
                ?: enabledRows.firstOrNull()
            if (row != null) {
                val previewOffset = if (state.selectedCategoryKey == "category_overlays" && !state.isSearchActive()) 1 else 0
                rowsState.scrollToItem(state.visibleSettings.indexOf(row) + previewOffset)
                withFrameNanos { }
                rows.getValue(row.key).requestFocus()
            }
        } else {
            val index = state.categories.indexOfFirst { it.key == target.category }
            if (index >= 0) {
                railState.scrollToItem(index)
                withFrameNanos { }
                categories.getValue(target.category).requestFocus()
            }
        }
        destination = null
    }

    return NovaSettingsFocus(
        railState = railState,
        rowsState = rowsState,
        categoryModifier = { category ->
            Modifier.focusRequester(categories.getValue(category.key))
                .onFocusChanged {
                    // A D-pad highlight previews its own pane immediately; A still works for touch.
                    if (it.hasFocus && category.key != state.selectedCategoryKey) onCategory(category.key)
                }
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false
                    else when (it.key) {
                        Key.DirectionRight -> {
                            onCategory(category.key)
                            destination = SettingsFocusDestination(category.key, enterPane = true)
                            true
                        }
                        Key.DirectionUp -> {
                            if (category.key == state.categories.firstOrNull()?.key && state.quickSettings.isNotEmpty()) {
                                firstQuick.requestFocus()
                                true
                            } else false
                        }
                        else -> false
                    }
                }
        },
        rowModifier = { definition ->
            Modifier.focusRequester(rows.getValue(definition.key))
                .onFocusChanged { if (it.hasFocus) lastRows[paneKey] = definition.key }
        },
        paneModifier = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                destination = SettingsFocusDestination(state.selectedCategoryKey, enterPane = false)
                true
            } else false
        },
        quickStripModifier = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                destination = SettingsFocusDestination(state.selectedCategoryKey, enterPane = false)
                true
            } else false
        },
        firstQuickModifier = Modifier.focusRequester(firstQuick),
    )
}
