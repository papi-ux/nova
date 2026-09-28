package com.papi.nova.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.papi.nova.ui.panel.NovaPanelState

/** A row of the pane to take focus once the pane shows [paneKey], then [then] (a page to push). */
internal class NovaSettingsPaneEntry(val paneKey: String, val rowKey: String?, val then: (() -> Unit)?)

/**
 * The explicit boundaries between the category rail, the quick strip and the pane; vertical
 * traversal inside each list stays with Compose.
 *
 * The pane is a [com.papi.nova.ui.panel.NovaPageStackHost] whose root page is the category's
 * rows. Its rows take focus only when the player moves there: the host settles focus on every
 * new root, and a D-pad highlight on the rail previews its pane by switching that root, so the
 * root's rows refuse focus ([rootGate]) unless the pane already holds it or this coordinator is
 * moving it in. Settings therefore still opens on the rail, and browsing the rail never pulls
 * focus into the pane.
 */
@Stable
internal class NovaSettingsFocus(val pane: NovaPanelState) {
    val railState = LazyListState()
    val firstQuick = FocusRequester()
    private val categories = HashMap<String, FocusRequester>()
    private val rows = HashMap<String, FocusRequester>()
    private val lastRows = HashMap<String, String>()

    /** Whether focus is anywhere in the pane: its rows or a page pushed over them. */
    var paneHasFocus: Boolean = false
        private set

    // True only while this coordinator itself moves focus onto a row.
    private var entering = false

    /** The category the rail should focus next. */
    var railTarget: String? by mutableStateOf(null)

    /** The row the pane should focus next. */
    var paneEntry: NovaSettingsPaneEntry? by mutableStateOf(null)

    fun categoryRequester(key: String): FocusRequester = categories.getOrPut(key) { FocusRequester() }

    fun rowRequester(key: String): FocusRequester = rows.getOrPut(key) { FocusRequester() }

    /** The row focus returns to in [paneKey]: the one last focused there, while it can take focus. */
    fun rememberedRow(paneKey: String): String? = lastRows[paneKey]

    fun focusRail(categoryKey: String) {
        railTarget = categoryKey
    }

    /** Enters the pane of [paneKey] at [rowKey], or at its remembered or first enabled row. */
    fun enterPane(paneKey: String, rowKey: String? = null, then: (() -> Unit)? = null) {
        paneEntry = NovaSettingsPaneEntry(paneKey, rowKey, then)
    }

    /** Moves focus onto [key]'s row past the root gate. */
    fun focusRow(key: String): Boolean {
        entering = true
        return try {
            rows[key]?.requestFocus() == true
        } finally {
            entering = false
        }
    }

    /** On the box around the pane host: tracks whether the pane holds focus. */
    val paneModifier: Modifier = Modifier.onFocusChanged { paneHasFocus = it.hasFocus }

    /**
     * On the root page's list: its rows refuse focus requested from outside the pane, such as
     * the host settling focus on a root that a rail highlight just switched. A D-pad move into
     * the rows passes (in the narrow layout Down from the categories is the way in), and so does
     * focus this coordinator moves in or that returns from a page pushed over the rows.
     */
    val rootGate: Modifier = Modifier.focusProperties {
        onEnter = {
            val requested = requestedFocusDirection != FocusDirection.Enter
            if (!requested && !paneHasFocus && !entering) cancelFocusChange()
        }
    }

    /** On a row: its requester, and the memory of it as the pane's last row. */
    fun rowModifier(paneKey: String, key: String): Modifier = Modifier
        .focusRequester(rowRequester(key))
        .onFocusChanged { if (it.hasFocus) lastRows[paneKey] = key }
}

/**
 * The rail's own key handling and focus: a highlight previews its pane, Right (or A) enters it,
 * and Up from the first category reaches the quick strip. While a page is pushed over the pane
 * the rail cannot take focus, so D-pad focus stays in the page.
 */
@Composable
internal fun NovaSettingsFocus.categoryModifier(
    category: NovaSettingCategory,
    state: NovaSettingsUiState,
    onCategory: (String) -> Unit,
): Modifier {
    val latest by rememberUpdatedState(state)
    val select by rememberUpdatedState(onCategory)
    return Modifier
        .focusProperties { canFocus = pane.depth <= 1 }
        .focusRequester(categoryRequester(category.key))
        .onFocusChanged {
            // A D-pad highlight previews its own pane immediately; A and a tap still work.
            if (it.hasFocus && category.key != latest.selectedCategoryKey) select(category.key)
        }
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) {
                false
            } else {
                when (it.key) {
                    Key.DirectionRight -> {
                        select(category.key)
                        enterPane(category.key)
                        true
                    }
                    Key.DirectionUp ->
                        if (category.key == latest.categories.firstOrNull()?.key && latest.quickSettings.isNotEmpty()) {
                            firstQuick.requestFocus()
                            true
                        } else {
                            false
                        }
                    else -> false
                }
            }
        }
}

/** Down from the quick strip lands on the rail's selected category. */
internal fun NovaSettingsFocus.quickStripModifier(selectedCategory: () -> String): Modifier =
    Modifier.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
            focusRail(selectedCategory())
            true
        } else {
            false
        }
    }

/**
 * Left from a row goes back to the rail, but only in the rows themselves: a value row changes its
 * value with Left before this sees it, and a page pushed over the rows keeps focus in the page.
 */
internal fun NovaSettingsFocus.paneLeftModifier(selectedCategory: () -> String): Modifier =
    Modifier.onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && pane.depth <= 1) {
            focusRail(selectedCategory())
            true
        } else {
            false
        }
    }

/** Carries out [NovaSettingsFocus.railTarget]: scrolls the rail to the category, then focuses it. */
@Composable
internal fun NovaSettingsFocus.RailFocusEffect(state: NovaSettingsUiState) {
    val target = railTarget
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val index = state.categories.indexOfFirst { it.key == target }
        if (index >= 0) {
            if (railState.layoutInfo.visibleItemsInfo.none { it.index == index }) railState.scrollToItem(index)
            withFrameNanos { }
            categoryRequester(target).requestFocus()
        }
        railTarget = null
    }
}

@Composable
internal fun rememberNovaSettingsFocus(pane: NovaPanelState): NovaSettingsFocus = remember(pane) { NovaSettingsFocus(pane) }
