package com.papi.nova.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaGridRow
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaSectionLabel
import com.papi.nova.ui.panel.NovaTextField
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaGridRows
import com.papi.nova.ui.panel.novaPanelColumns
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaScrollEdgeFade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the library's panel window shows. Library Options sits at the start edge and System at the
 * end, and L1 and R1 swap one for the other in the same window, so only one is ever on screen.
 * Sources, More filters and Sort are host drawn Choice pages pushed from Options, and Where It Runs
 * inside Polaris Sync is the Play Setup page of the same name.
 */
internal sealed interface LibraryPage : NovaPage {
    /** Filters, sort, layout and search for the grid behind the panel. */
    data class Options(override val title: String) : LibraryPage {
        override val key: String get() = KEY_OPTIONS
    }

    /**
     * The host and the app: switching host, settings, Polaris Sync, help. Two to a line on a
     * landscape handheld, one column elsewhere.
     */
    data class System(override val title: String) : LibraryPage {
        override val key: String get() = KEY_SYSTEM
        override val width: NovaPanelWidth get() = NovaPanelWidth.Grid
    }

    /** A live search: the grid behind the panel narrows as the player types. */
    data class Search(override val title: String) : LibraryPage {
        override val key: String get() = KEY_SEARCH
    }

    /** Nova against the host's settings, wide so the rows and their legend have room. */
    data class PolarisSync(override val title: String) : LibraryPage {
        override val key: String get() = KEY_POLARIS_SYNC
        override val width: NovaPanelWidth get() = NovaPanelWidth.Wide
    }

    companion object {
        const val KEY_OPTIONS = "library-options"
        const val KEY_SYSTEM = "library-system"
        const val KEY_SEARCH = "library-search"
        const val KEY_POLARIS_SYNC = "library-polaris-sync"
        const val KEY_SOURCES = "library-sources"
        const val KEY_MORE = "library-more"
        const val KEY_SORT = "library-sort"
    }
}

/** The edge each peer is attached to: Options at the start, System and its pages at the end. */
internal val LibraryPage.edge: NovaEdge
    get() = if (this is LibraryPage.Options) NovaEdge.Start else NovaEdge.End

/**
 * Swaps an open panel to [root] in place, at its own edge, unless the panel already shows it with
 * nothing pushed, so the two peers are never on screen together and nothing of one peeks behind
 * the other. Returns false while the panel is closed, for the caller to open it.
 */
internal fun NovaPanelState.swapToLibraryPeer(root: LibraryPage): Boolean {
    if (!isOpen) return false
    if (depth != 1 || top?.key != root.key) switchRoot(root, root.edge)
    return true
}

/** One entry of the More filters page: back to the whole library, a category, or a genre. */
internal sealed interface NovaLibraryMoreFilter {
    data object Clear : NovaLibraryMoreFilter
    data class Category(val id: String) : NovaLibraryMoreFilter
    data class Genre(val name: String) : NovaLibraryMoreFilter
}

/** What Library Options shows, read from the library's live state each time it composes. */
@Immutable
internal data class NovaLibraryOptionsUi(
    val resultCount: Int,
    val searchQuery: String,
    val filter: NovaLibraryPrimaryFilter,
    /** The totals the three quick filters would show, as one caption under them. */
    val filterCaption: String,
    /** The source, category or genre narrowing the grid, while one does. */
    val narrowedLabel: String?,
    val sourceValue: String,
    val moreValue: String,
    val clearable: Boolean,
    val sortLabel: String,
    val layoutMode: NovaLibraryLayoutMode,
    val layoutCaption: String,
    val showPosterTitles: Boolean,
    val artwork: NovaArtworkLibraryUpdateUiState,
)

/** What Library Options does. Pages are built when they are pushed, so they read the state of that moment. */
internal class NovaLibraryOptionsActions(
    val onFilter: (NovaLibraryPrimaryFilter) -> Unit,
    val searchPage: () -> NovaPage,
    val sourcesPage: () -> NovaPage,
    val morePage: () -> NovaPage,
    val sortPage: () -> NovaPage,
    val onClearFilters: () -> Unit,
    val onLayoutMode: (NovaLibraryLayoutMode) -> Unit,
    val onPosterTitles: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onStartArtwork: () -> Unit,
    val onCancelArtwork: () -> Unit,
    val onRetryArtwork: (List<String>) -> Unit,
)

/** The quick filters, in their row's order. */
private val QuickFilters = listOf(
    NovaLibraryPrimaryFilter.ALL,
    NovaLibraryPrimaryFilter.RECENT,
    NovaLibraryPrimaryFilter.HDR,
)

/**
 * Library Options. Every choice changes in its own row or on a page of its own, and the grid behind
 * the panel follows at once. The filter row is one focus stop whose Left and Right step through
 * All, Recent and HDR, so the D-pad never leaves the panel sideways; a source or a category set on
 * its own page shows there as the current value, and stepping away from it goes back to a quick
 * filter. Focus opens on the filter row.
 */
@Composable
internal fun NovaPageScope.NovaLibraryOptionsPage(ui: NovaLibraryOptionsUi, actions: NovaLibraryOptionsActions) {
    val filterLabels = mapOf(
        NovaLibraryPrimaryFilter.ALL to stringResource(R.string.nova_library_filter_all),
        NovaLibraryPrimaryFilter.RECENT to stringResource(R.string.nova_library_filter_recent),
        NovaLibraryPrimaryFilter.HDR to stringResource(R.string.nova_library_filter_hdr),
    )
    val narrowedReason = stringResource(
        if (ui.filter == NovaLibraryPrimaryFilter.MORE) {
            R.string.nova_library_panel_filter_set_in_more
        } else {
            R.string.nova_library_panel_filter_set_in_sources
        },
    )
    val filterOptions = QuickFilters.map { NovaOption(it, filterLabels.getValue(it)) } +
        listOfNotNull(
            ui.narrowedLabel?.takeIf { ui.filter !in QuickFilters }?.let { label ->
                // The value set on the Sources or More page, marked current here. It cannot be
                // stepped to: a step from it goes back to a quick filter.
                NovaOption(ui.filter, label, disabledReason = narrowedReason)
            },
        )
    val layoutOptions = listOf(
        NovaOption(NovaLibraryLayoutMode.GRID, stringResource(R.string.nova_library_options_layout_grid)),
        NovaOption(NovaLibraryLayoutMode.COMPACT, stringResource(R.string.nova_library_options_layout_compact)),
        NovaOption(NovaLibraryLayoutMode.STAGE, stringResource(R.string.nova_library_options_layout_stage)),
    )
    val titleOptions = listOf(
        NovaOption(true, stringResource(R.string.nova_library_options_poster_titles_show)),
        NovaOption(false, stringResource(R.string.nova_library_options_poster_titles_hide)),
    )
    val push: (() -> NovaPage) -> Unit = { build -> if (isTop) panel.push(build()) }
    // Clear removes its own row, and focus went with it: a grey veil over the panel and no ring,
    // with the D-pad dead until B. It hands focus to the Filter row first.
    val filterFocus = remember { FocusRequester() }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth().novaScrollEdgeFade(listState),
    ) {
        // Each row's place in the list, counted as the list is built. Clear is there only while
        // something narrows the library, so fixed numbers pointed every row below it at its
        // neighbour, and a pop scrolled the wrong row into view and lost the one it restored.
        var position = 0
        val filterIndex = position++
        item(key = "filter", contentType = "value") {
            NovaValueRow(
                title = stringResource(R.string.nova_library_panel_filter),
                options = filterOptions,
                current = ui.filter,
                onChange = actions.onFilter,
                caption = ui.filterCaption,
                modifier = Modifier
                    .focusRequester(filterFocus)
                    .novaInitialFocus()
                    .novaRestorableFocus("filter", filterIndex),
            )
        }
        val sourcesIndex = position++
        item(key = "sources", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_library_filter_sheet_sources),
                trailing = NovaRowTrailing.Value(ui.sourceValue),
                onClick = { push(actions.sourcesPage) },
                modifier = Modifier.novaRestorableFocus("sources", sourcesIndex),
            )
        }
        val moreIndex = position++
        item(key = "more", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_library_filter_sheet_more),
                trailing = NovaRowTrailing.Value(ui.moreValue),
                onClick = { push(actions.morePage) },
                modifier = Modifier.novaRestorableFocus("more", moreIndex),
            )
        }
        if (ui.clearable) {
            val clearIndex = position++
            item(key = "clear", contentType = "row") {
                NovaRow(
                    title = stringResource(R.string.nova_library_filter_clear_all),
                    caption = stringResource(R.string.nova_library_panel_clear_caption),
                    onClick = {
                        if (isTop) {
                            runCatching { filterFocus.requestFocus() }
                            actions.onClearFilters()
                        }
                    },
                    modifier = Modifier.novaRestorableFocus("clear", clearIndex),
                )
            }
        }
        position++
        item(key = "view", contentType = "label") {
            NovaSectionLabel(stringResource(R.string.nova_library_panel_view))
        }
        val sortIndex = position++
        item(key = "sort", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_library_options_sort_title),
                trailing = NovaRowTrailing.Value(ui.sortLabel),
                onClick = { push(actions.sortPage) },
                modifier = Modifier.novaRestorableFocus("sort", sortIndex),
            )
        }
        val layoutIndex = position++
        item(key = "layout", contentType = "value") {
            NovaValueRow(
                title = stringResource(R.string.nova_library_options_layout_title),
                options = layoutOptions,
                current = ui.layoutMode,
                onChange = actions.onLayoutMode,
                caption = ui.layoutCaption,
                // Beside the title as Filter's are, or a cycler where they would not fit there:
                // Layout's went under the title on a line of their own while Filter's stayed
                // beside it, two looks for one kind of row on one page (N16).
                wrapUnderTitle = false,
                modifier = Modifier.novaRestorableFocus("layout", layoutIndex),
            )
        }
        val titlesIndex = position++
        item(key = "titles", contentType = "value") {
            NovaValueRow(
                title = stringResource(R.string.nova_library_options_poster_titles_title),
                options = titleOptions,
                current = ui.showPosterTitles,
                onChange = actions.onPosterTitles,
                style = NovaValueStyle.Switch,
                caption = stringResource(
                    if (ui.showPosterTitles) {
                        R.string.nova_library_options_poster_titles_show_hint
                    } else {
                        R.string.nova_library_options_poster_titles_hide_hint
                    },
                ),
                modifier = Modifier.novaRestorableFocus("titles", titlesIndex),
            )
        }
        val searchIndex = position++
        item(key = "search", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_library_panel_search),
                caption = stringResource(R.string.nova_library_results_format, ui.resultCount),
                trailing = if (ui.searchQuery.isBlank()) NovaRowTrailing.Opens else NovaRowTrailing.Value(ui.searchQuery),
                onClick = { push(actions.searchPage) },
                modifier = Modifier.novaRestorableFocus("search", searchIndex),
            )
        }
        val refreshIndex = position++
        item(key = "refresh", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_refresh),
                caption = stringResource(R.string.nova_library_panel_refresh_caption),
                onClick = { if (isTop) closeThen(action = actions.onRefresh) },
                modifier = Modifier.novaRestorableFocus("refresh", refreshIndex),
            )
        }
        item(key = "artwork", contentType = "label") {
            NovaSectionLabel(stringResource(R.string.nova_artwork_library_update_title))
        }
        artworkRows(ui.artwork, actions)
    }
}

/** The artwork library update, as rows: what it does, where it is, and the one thing to do next. */
private fun androidx.compose.foundation.lazy.LazyListScope.artworkRows(
    state: NovaArtworkLibraryUpdateUiState,
    actions: NovaLibraryOptionsActions,
) {
    when (state) {
        NovaArtworkLibraryUpdateUiState.Idle -> item(key = "artwork-start", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_artwork_library_update_start),
                caption = stringResource(R.string.nova_artwork_library_update_policy) + " " +
                    stringResource(R.string.nova_artwork_library_update_preserve_custom),
                onClick = actions.onStartArtwork,
            )
        }
        is NovaArtworkLibraryUpdateUiState.Running -> {
            val progress = state.progress
            item(key = "artwork-progress", contentType = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)) {
                    NovaPanelStatusText(
                        title = stringResource(R.string.nova_library_panel_artwork_updating),
                        caption = stringResource(R.string.nova_artwork_library_update_running, progress.completed, progress.total),
                    )
                    val colors = LocalNovaComposeColors.current
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                        color = colors.accent,
                        trackColor = colors.divider,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaPanelMetrics.SpaceMd),
                    )
                }
            }
            item(key = "artwork-cancel", contentType = "row") {
                if (state.cancelling) {
                    NovaPanelStatusText(caption = stringResource(R.string.nova_artwork_library_update_cancelling))
                } else {
                    NovaRow(
                        title = stringResource(R.string.nova_artwork_library_update_cancel),
                        caption = stringResource(R.string.nova_artwork_library_update_cancel_hint),
                        onClick = actions.onCancelArtwork,
                    )
                }
            }
        }
        is NovaArtworkLibraryUpdateUiState.Complete -> {
            val summary = state.summary
            item(key = "artwork-summary", contentType = "status") {
                NovaPanelStatusText(
                    caption = stringResource(
                        R.string.nova_artwork_library_update_summary,
                        summary.progress.updated,
                        summary.progress.healthy,
                        summary.progress.customPreserved,
                        summary.progress.failed,
                    ),
                )
            }
            item(key = "artwork-next", contentType = "row") {
                if (summary.failedGameIds.isNotEmpty()) {
                    NovaRow(
                        title = stringResource(R.string.nova_artwork_library_update_retry),
                        caption = stringResource(R.string.nova_artwork_library_update_retry_hint),
                        onClick = { actions.onRetryArtwork(summary.failedGameIds) },
                    )
                } else {
                    NovaRow(
                        title = stringResource(R.string.nova_artwork_library_update_retry_all),
                        caption = stringResource(R.string.nova_artwork_library_update_complete),
                        onClick = actions.onStartArtwork,
                    )
                }
            }
        }
        is NovaArtworkLibraryUpdateUiState.Cancelled -> {
            item(key = "artwork-summary", contentType = "status") {
                NovaPanelStatusText(
                    caption = stringResource(
                        R.string.nova_artwork_library_update_cancelled,
                        state.progress.completed,
                        state.progress.total,
                    ),
                )
            }
            item(key = "artwork-next", contentType = "row") { ArtworkRetryAll(actions) }
        }
        is NovaArtworkLibraryUpdateUiState.Failed -> {
            item(key = "artwork-summary", contentType = "status") {
                NovaPanelStatusText(
                    caption = stringResource(
                        when (state.reason) {
                            NovaArtworkLibraryUpdateFailure.SERVER_CAPABILITY_UNAVAILABLE ->
                                R.string.nova_artwork_library_update_unavailable
                            NovaArtworkLibraryUpdateFailure.UNEXPECTED ->
                                R.string.nova_artwork_library_update_failed
                        },
                    ),
                )
            }
            item(key = "artwork-next", contentType = "row") { ArtworkRetryAll(actions) }
        }
    }
}

@Composable
private fun ArtworkRetryAll(actions: NovaLibraryOptionsActions) {
    NovaRow(
        title = stringResource(R.string.nova_artwork_library_update_retry_all),
        caption = stringResource(R.string.nova_artwork_library_update_retry_all_hint),
        onClick = actions.onStartArtwork,
    )
}

/**
 * A statement inside a page: read, never a stop on the D-pad, inset like a row's text. [announce]
 * makes it a polite live region, for a result that changes while the page is open.
 */
@Composable
internal fun NovaPanelStatusText(
    caption: String,
    title: String? = null,
    captionColor: androidx.compose.ui.graphics.Color? = null,
    announce: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Column(
        modifier = Modifier
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .fillMaxWidth()
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceXs),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
    ) {
        title?.let { Text(text = it, style = type.rowTitle, color = colors.textPrimary) }
        Text(text = caption, style = type.caption, color = captionColor ?: colors.textSecondary)
    }
}

/** The host System speaks for: its name and address, whether Polaris answers, and its display mode. */
@Immutable
internal data class NovaLibrarySystemUi(
    val hostLabel: String,
    val status: String,
    val ready: Boolean,
    val mode: String?,
)

internal class NovaLibrarySystemActions(
    val onSwitchHost: () -> Unit,
    val onSettings: () -> Unit,
    val polarisSyncPage: () -> NovaPage,
    val onManageServer: () -> Unit,
    val onHelp: () -> Unit,
    /** About Nova, pushed in the panel: the version was a Toast that floated over the library. */
    val aboutPage: () -> NovaPage,
    val onMatrix: () -> Unit,
    val onSponsor: () -> Unit,
)

/** One row of System: its key, its words, whether it opens a page, and what A does. */
private class NovaLibrarySystemRow(
    val key: String,
    @StringRes val title: Int,
    @StringRes val caption: Int,
    val opens: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * System: a header saying which host this is and whether Polaris answers, then its rows, two to a
 * line on a landscape handheld and one column elsewhere, in the same order either way. Rows that
 * leave the library close the panel first; Polaris Sync and About are pages of their own, pushed
 * here. Every row goes somewhere, so every row carries the chevron: only Polaris Sync had one, and
 * the rows that leave read as rows that do nothing. Focus opens on the first row, never on the
 * panel.
 */
@Composable
internal fun NovaPageScope.NovaLibrarySystemPage(ui: NovaLibrarySystemUi, actions: NovaLibrarySystemActions) {
    val colors = LocalNovaComposeColors.current
    val leave: (() -> Unit) -> Unit = { action -> if (isTop) closeThen(action = action) }
    val rows = listOf(
        NovaLibrarySystemRow(
            key = "switch-host",
            title = R.string.nova_system_menu_switch_host,
            caption = R.string.nova_system_menu_switch_host_hint,
            opens = true,
            onClick = { leave(actions.onSwitchHost) },
        ),
        NovaLibrarySystemRow(
            key = "settings",
            title = R.string.nova_system_menu_settings,
            caption = R.string.nova_system_menu_settings_hint,
            opens = true,
            onClick = { leave(actions.onSettings) },
        ),
        NovaLibrarySystemRow(
            key = "polaris-sync",
            title = R.string.nova_system_menu_polaris_sync,
            caption = R.string.nova_system_menu_polaris_sync_hint,
            opens = true,
            onClick = { if (isTop) panel.push(actions.polarisSyncPage()) },
        ),
        NovaLibrarySystemRow(
            key = "manage",
            title = R.string.nova_system_menu_manage_server,
            caption = R.string.nova_system_menu_manage_server_hint,
            opens = true,
            onClick = { leave(actions.onManageServer) },
        ),
        NovaLibrarySystemRow(
            key = "help",
            title = R.string.nova_system_menu_help_diagnostics,
            caption = R.string.nova_system_menu_help_diagnostics_hint,
            opens = true,
            onClick = { leave(actions.onHelp) },
        ),
        NovaLibrarySystemRow(
            key = "about",
            title = R.string.nova_system_menu_about,
            caption = R.string.nova_system_menu_about_hint,
            opens = true,
            onClick = { if (isTop) panel.push(actions.aboutPage()) },
        ),
        NovaLibrarySystemRow(
            key = "matrix",
            title = R.string.nova_system_menu_matrix,
            caption = R.string.nova_system_menu_matrix_hint,
            opens = true,
            onClick = { leave(actions.onMatrix) },
        ),
        NovaLibrarySystemRow(
            key = "sponsor",
            title = R.string.nova_system_menu_sponsor,
            caption = R.string.nova_system_menu_sponsor_hint,
            opens = true,
            onClick = { leave(actions.onSponsor) },
        ),
    )
    val columns = novaPanelColumns(NovaPanelWidth.Grid)
    val lines = novaGridRows(rows, columns)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth().novaScrollEdgeFade(listState),
    ) {
        item(key = "header", contentType = "status") {
            NovaPanelStatusText(
                title = ui.hostLabel,
                caption = listOfNotNull(ui.status, ui.mode).joinToString(" · "),
                captionColor = if (ui.ready) colors.accent else colors.textSecondary,
            )
        }
        // Focus opens on the first row, never on the panel. A line's place in the list is where a
        // pop scrolls back to, one line under the header.
        itemsIndexed(lines, key = { _, cells -> cells.joinToString("+") { it.key } }, contentType = { _, _ -> "row" }) { line, cells ->
            NovaGridRow(cells, columns) { row, cell ->
                NovaRow(
                    title = stringResource(row.title),
                    caption = stringResource(row.caption),
                    trailing = if (row.opens) NovaRowTrailing.Opens else NovaRowTrailing.None,
                    onClick = row.onClick,
                    modifier = cell
                        .then(if (row === rows.first()) Modifier.novaInitialFocus() else Modifier)
                        .novaRestorableFocus(row.key, line + 1),
                )
            }
        }
    }
}

/**
 * Search: the field at the top, and under it the way back to the grid. The grid behind the panel
 * narrows as the player types; B hides the keyboard first, then returns to Options.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun NovaPageScope.NovaLibrarySearchPage(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val openedByTouch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val showResults = { if (isTop) closeThen {} }
    val scroll = rememberScrollState()
    // The keyboard covered Show Results by half on the RP6. The column keeps clear of the keyboard,
    // and Show Results comes into view above it when it opens.
    val showResultsInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(imeUp) {
        if (imeUp) {
            androidx.compose.runtime.withFrameNanos { }
            showResultsInView.bringIntoView()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .novaScrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        NovaTextField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.nova_library_search_hint),
            imeAction = ImeAction.Search,
            onImeAction = showResults,
            openOnStart = openedByTouch,
            modifier = Modifier.fillMaxWidth().novaInitialFocus(),
        )
        Text(
            text = stringResource(R.string.nova_library_results_format, resultCount),
            style = novaPanelType.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = NovaPanelMetrics.SpaceMd),
        )
        NovaPanelButton(
            text = stringResource(R.string.nova_library_panel_show_results),
            primary = true,
            onClick = showResults,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(showResultsInView),
        )
        if (query.isNotBlank()) {
            NovaPanelButton(
                text = stringResource(R.string.nova_library_panel_clear_search),
                onClick = { if (isTop) onQueryChange("") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The search the grid is narrowed by, said above it while it is on: "Search: con · 2 shown", with
 * the close mark that says A clears it. Nothing had shown that a search was active once its page
 * closed (N12). Clearing hands focus down to the grid first, so the ring never lands on nothing.
 */
@Composable
internal fun NovaLibrarySearchChip(
    query: String,
    resultCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val label = stringResource(R.string.nova_library_search_chip, query, resultCount)
    val clear = stringResource(R.string.nova_library_panel_clear_search)
    com.papi.nova.ui.compose.NovaActionSurface(
        onClick = {
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            onClear()
        },
        contentDescription = "$label. $clear",
        minHeight = NovaPanelMetrics.ButtonMinHeight,
        modifier = modifier.testTag(NOVA_LIBRARY_SEARCH_CHIP_TAG),
    ) { contentColor, _ ->
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        ) {
            Text(text = label, style = novaPanelType.caption, color = contentColor)
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(NovaPanelMetrics.IconSize),
            )
        }
    }
}

/** The active search's chip above the grid, for a test to find it. */
internal const val NOVA_LIBRARY_SEARCH_CHIP_TAG = "nova-library-search-chip"

/**
 * The Polaris Sync engine for the System panel's page, held by the library rather than by a
 * fragment: it starts when the page is pushed and closes when the page leaves the stack, so a
 * Where It Runs page pushed on top of it keeps the same engine.
 */
internal class NovaPolarisSyncController(
    private val context: Context,
    private val apiClient: PolarisApiClient?,
    private val serverUuid: String?,
    private val scope: CoroutineScope,
    private val onSettingsChanged: (PolarisClientSettings) -> Unit,
) {
    var engine: NovaPolarisSyncEngine? by mutableStateOf(null)
        private set

    /**
     * The engine's last result, said on the page under its status line: every Polaris Sync result
     * was a Toast that floated over the panel and was gone before it could be read (X2).
     */
    var notice: NovaPolarisSyncNotice? by mutableStateOf(null)
        private set

    val isOpen: Boolean get() = engine != null

    /** Starts the engine, from [initialSettings] while the host is asked again. Opening twice keeps the first. */
    fun open(initialSettings: PolarisClientSettings?) {
        if (engine != null) return
        engine = NovaPolarisSyncEngine(
            context = context,
            apiClient = apiClient,
            serverUuid = serverUuid,
            scope = scope,
            onSettingsChanged = onSettingsChanged,
            onMessage = { messageRes, isError -> notice = NovaPolarisSyncNotice(context.getString(messageRes), isError) },
            onTextMessage = { message, isError -> notice = NovaPolarisSyncNotice(message, isError) },
        ).also { it.start(initialSettings) }
    }

    fun close() {
        engine?.close()
        engine = null
        notice = null
    }

    /**
     * Closes the engine once [panel] no longer holds the page keyed [key], having held it: B, L1
     * or R1 to a peer, or the panel closing. A page pushed on top keeps it running.
     */
    fun closeWhenGone(panel: NovaPanelState, key: String) {
        scope.launch {
            snapshotFlow { panel.contains(key) }
                .dropWhile { !it }
                .first { !it }
            close()
        }
    }
}

/** A Polaris Sync result as its page says it: the words, and whether it went wrong. */
internal data class NovaPolarisSyncNotice(val message: String, val isError: Boolean)

/** Polaris Sync's host rows, plan and status, as its page and its Profile page both read them. */
internal class NovaPolarisSyncModel(
    val uiState: NovaPolarisSyncUiState,
    val rows: List<NovaPlaySetupRowState>,
    val plan: NovaPlaySetupPlan,
    val actions: NovaPlaySetupHostActions,
)

/** The host rows and plan from [controller]'s engine, or null once it has closed. */
@Composable
internal fun rememberNovaPolarisSyncModel(
    controller: NovaPolarisSyncController,
    serverUuid: String?,
    display: android.view.Display?,
): NovaPolarisSyncModel? {
    val engine = controller.engine ?: return null
    val context = LocalContext.current
    val getString: (Int) -> String = { resId -> context.getString(resId) }
    val profileRevision = engine.profileRevision
    val prefs = remember(profileRevision) { PreferenceConfiguration.readPreferences(context) }
    val uiState = NovaPolarisSyncUiStateMapper.build(
        settings = engine.currentSettings,
        busy = engine.busy,
        settingsUnavailable = engine.settingsUnavailable,
        autoSyncEnabled = engine.autoSyncEnabled,
        hasServerUuid = !serverUuid.isNullOrBlank(),
        deviceScreenMode = novaDeviceScreenMode(display),
        deviceScreenScale = novaDeviceScreenScale(display),
        novaDisplayMode = PreferenceConfiguration.formatStreamingDisplayMode(prefs.width, prefs.height, prefs.fps),
        novaBitrateKbps = prefs.bitrate,
        loadingLabel = stringResource(R.string.nova_polaris_sync_loading),
        unavailableLabel = stringResource(R.string.nova_polaris_sync_unavailable),
        unsetLabel = stringResource(R.string.nova_polaris_sync_unset),
        savedAfterRelaunchLabel = stringResource(R.string.nova_polaris_sync_status_saved_relaunch),
        selectedLabel = stringResource(R.string.nova_polaris_sync_status_selected),
        activeNowLabel = stringResource(R.string.nova_polaris_sync_status_active_now),
        availableLabel = stringResource(R.string.nova_polaris_sync_status_available),
    )
    val actions = NovaPlaySetupHostActions(
        onSelectMode = { engine.setStreamDisplayMode(it) },
        onSelectScreenToAdd = { mode, scale -> engine.setVirtualDisplayMode(mode, scale) },
        onSelectScreenScale = { engine.setVirtualDisplayScale(it) },
        onMatchNova = { engine.matchNova() },
        onSendNova = { engine.sendNova() },
        onUsePolaris = { engine.usePolarisProfile() },
        onClearProfile = { engine.clearProfile() },
        onKeepInStep = { engine.setAutoSync(it) },
    )
    val profileValue = novaPlaySetupHostProfileValue(uiState, engine.currentSettings, getString)
    return NovaPolarisSyncModel(
        uiState = uiState,
        rows = buildNovaPlaySetupHostRows(
            sync = uiState,
            polarisProfileValue = profileValue,
            getString = getString,
            actions = actions,
        ),
        plan = novaPlaySetupHostPlan(sync = uiState, polarisProfileValue = profileValue, getString = getString),
        actions = actions,
    )
}

/**
 * Polaris Sync as a wide page: the same plan card and host rows as Play Setup's Every Game scope.
 * A row whose choices outgrow a press pushes its page: [playInPage] for the Default Display, and
 * [profilePage] for the profile's verbs.
 */
@Composable
internal fun NovaPageScope.NovaPolarisSyncPage(
    controller: NovaPolarisSyncController,
    serverName: String,
    serverUuid: String?,
    /** The display the library is on, for the screen size the host is offered to match. */
    display: android.view.Display?,
    playInPage: (NovaPlaySetupModePickerState) -> NovaPage,
    profilePage: () -> NovaPage,
) {
    val model = rememberNovaPolarisSyncModel(controller, serverUuid, display) ?: return
    val uiState = model.uiState
    val statusLabel = stringResource(
        when (uiState.status) {
            NovaPolarisSyncStatus.LOADING -> R.string.nova_polaris_sync_loading
            NovaPolarisSyncStatus.UNAVAILABLE -> R.string.nova_polaris_sync_unavailable
            NovaPolarisSyncStatus.SYNCED -> R.string.nova_polaris_sync_synced
            NovaPolarisSyncStatus.SYNCING -> R.string.nova_polaris_sync_syncing
        },
    )
    val pickerTitle = stringResource(R.string.nova_play_setup_host_default_display)
    val readTitle = stringResource(R.string.nova_play_setup_host_read_title)
    val colors = LocalNovaComposeColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPanelStatusText(
            caption = "$serverName · $statusLabel",
            captionColor = if (uiState.status == NovaPolarisSyncStatus.SYNCED) colors.accent else colors.textSecondary,
        )
        // The last result, in place under the status and announced, until the next one replaces it.
        controller.notice?.let { notice ->
            NovaPanelStatusText(
                caption = notice.message,
                captionColor = if (notice.isError) colors.warning else colors.textSecondary,
                announce = true,
            )
        }
        NovaPlaySetupBody(
            card = {
                NovaPlaySetupPlanCard(
                    title = readTitle,
                    value = model.plan.mode,
                    line = novaPlaySetupPlanSummary(model.plan).orEmpty(),
                    // The plan opens whole on its own page, and focus comes back here (R7).
                    onOpen = { if (isTop) panel.push(PlaySetupPage.Plan(readTitle, model.plan)) },
                    // The page opens on this read-only summary: it opened on Screen To Add, where
                    // one stray Left or Right changed the display the host keeps for this device.
                    modifier = Modifier.novaInitialFocus().novaRestorableFocus("plan"),
                )
            },
        ) {
            NovaHostSetupRowList(
                rows = model.rows,
                onAdvance = { row ->
                    if (!isTop) return@NovaHostSetupRowList
                    when {
                        row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY && novaModePickerEligible(uiState.modes.size) ->
                            panel.push(playInPage(buildHostModePickerState(modes = uiState.modes, title = pickerTitle)))
                        row == NovaPlaySetupRow.HOST_PROFILE -> panel.push(profilePage())
                        else -> advanceNovaPlaySetupHostRow(row = row, rows = model.rows, sync = uiState, actions = model.actions)
                    }
                },
                rowModifier = { row, _ -> Modifier.novaRestorableFocus(row.name) },
            )
        }
    }
}
