package com.papi.nova.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
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
import com.papi.nova.ui.panel.novaPanelType
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

    /** The host and the app: switching host, settings, Polaris Sync, help. */
    data class System(override val title: String) : LibraryPage {
        override val key: String get() = KEY_SYSTEM
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

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.novaInitialFocus().novaRestorableFocus("filter", filterIndex),
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
                    onClick = { if (isTop) actions.onClearFilters() },
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

/** A statement inside a page: read, never a stop on the D-pad, inset like a row's text. */
@Composable
internal fun NovaPanelStatusText(caption: String, title: String? = null, captionColor: androidx.compose.ui.graphics.Color? = null) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Column(
        modifier = Modifier
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
    val onAbout: () -> Unit,
    val onMatrix: () -> Unit,
    val onSponsor: () -> Unit,
)

/**
 * System: a header saying which host this is and whether Polaris answers, then one column of rows.
 * Rows that leave the library close the panel first; Polaris Sync is a page of its own, pushed
 * here. Focus opens on the first row, never on the panel.
 */
@Composable
internal fun NovaPageScope.NovaLibrarySystemPage(ui: NovaLibrarySystemUi, actions: NovaLibrarySystemActions) {
    val colors = LocalNovaComposeColors.current
    val leave: (() -> Unit) -> Unit = { action -> if (isTop) closeThen(action = action) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "header", contentType = "status") {
            NovaPanelStatusText(
                title = ui.hostLabel,
                caption = listOfNotNull(ui.status, ui.mode).joinToString(" · "),
                captionColor = if (ui.ready) colors.accent else colors.textSecondary,
            )
        }
        item(key = "switch-host", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_switch_host),
                caption = stringResource(R.string.nova_system_menu_switch_host_hint),
                onClick = { leave(actions.onSwitchHost) },
                modifier = Modifier.novaInitialFocus().novaRestorableFocus("switch-host", 1),
            )
        }
        item(key = "settings", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_settings),
                caption = stringResource(R.string.nova_system_menu_settings_hint),
                onClick = { leave(actions.onSettings) },
                modifier = Modifier.novaRestorableFocus("settings", 2),
            )
        }
        item(key = "polaris-sync", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_polaris_sync),
                caption = stringResource(R.string.nova_system_menu_polaris_sync_hint),
                trailing = NovaRowTrailing.Opens,
                onClick = { if (isTop) panel.push(actions.polarisSyncPage()) },
                modifier = Modifier.novaRestorableFocus("polaris-sync", 3),
            )
        }
        item(key = "manage", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_manage_server),
                caption = stringResource(R.string.nova_system_menu_manage_server_hint),
                onClick = { leave(actions.onManageServer) },
                modifier = Modifier.novaRestorableFocus("manage", 4),
            )
        }
        item(key = "help", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_help_diagnostics),
                caption = stringResource(R.string.nova_system_menu_help_diagnostics_hint),
                onClick = { leave(actions.onHelp) },
                modifier = Modifier.novaRestorableFocus("help", 5),
            )
        }
        item(key = "about", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_about),
                caption = stringResource(R.string.nova_system_menu_about_hint),
                onClick = { leave(actions.onAbout) },
                modifier = Modifier.novaRestorableFocus("about", 6),
            )
        }
        item(key = "matrix", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_matrix),
                caption = stringResource(R.string.nova_system_menu_matrix_hint),
                onClick = { leave(actions.onMatrix) },
                modifier = Modifier.novaRestorableFocus("matrix", 7),
            )
        }
        item(key = "sponsor", contentType = "row") {
            NovaRow(
                title = stringResource(R.string.nova_system_menu_sponsor),
                caption = stringResource(R.string.nova_system_menu_sponsor_hint),
                onClick = { leave(actions.onSponsor) },
                modifier = Modifier.novaRestorableFocus("sponsor", 8),
            )
        }
    }
}

/**
 * Search: the field at the top, and under it the way back to the grid. The grid behind the panel
 * narrows as the player types; B hides the keyboard first, then returns to Options.
 */
@Composable
internal fun NovaPageScope.NovaLibrarySearchPage(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val openedByTouch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val showResults = { if (isTop) closeThen {} }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
            modifier = Modifier.fillMaxWidth(),
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

    /** Which row the legend under the rows explains; rows point it at themselves as focus moves. */
    var explainedRow: NovaPlaySetupRow by mutableStateOf(NovaPlaySetupRow.HOST_DEFAULT_DISPLAY)

    val isOpen: Boolean get() = engine != null

    /** Starts the engine, from [initialSettings] while the host is asked again. Opening twice keeps the first. */
    fun open(initialSettings: PolarisClientSettings?) {
        if (engine != null) return
        explainedRow = NovaPlaySetupRow.HOST_DEFAULT_DISPLAY
        engine = NovaPolarisSyncEngine(
            context = context,
            apiClient = apiClient,
            serverUuid = serverUuid,
            scope = scope,
            onSettingsChanged = onSettingsChanged,
            onMessage = { messageRes, _ -> Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show() },
            onTextMessage = { message, _ -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
        ).also { it.start(initialSettings) }
    }

    fun close() {
        engine?.close()
        engine = null
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

/**
 * Polaris Sync as a wide page: the same host rows, plan and legend as Play Setup's Every Game
 * scope, fitted to the page's height so the legend under the rows stays in sight. A row whose
 * choices outgrow a press pushes [playInPage].
 */
@Composable
internal fun NovaPageScope.NovaPolarisSyncPage(
    controller: NovaPolarisSyncController,
    serverName: String,
    serverUuid: String?,
    /** The display the library is on, for the screen size the host is offered to match. */
    display: android.view.Display?,
    playInPage: (NovaPlaySetupModePickerState) -> NovaPage,
) {
    val engine = controller.engine ?: return
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
    val rows = buildNovaPlaySetupHostRows(
        sync = uiState,
        polarisProfileValue = profileValue,
        getString = getString,
        actions = actions,
    )
    val plan = novaPlaySetupHostPlan(sync = uiState, polarisProfileValue = profileValue, getString = getString)
    val statusLabel = stringResource(
        when (uiState.status) {
            NovaPolarisSyncStatus.LOADING -> R.string.nova_polaris_sync_loading
            NovaPolarisSyncStatus.UNAVAILABLE -> R.string.nova_polaris_sync_unavailable
            NovaPolarisSyncStatus.SYNCED -> R.string.nova_polaris_sync_synced
            NovaPolarisSyncStatus.SYNCING -> R.string.nova_polaris_sync_syncing
        },
    )
    val pickerTitle = stringResource(R.string.nova_play_setup_host_default_display)
    val colors = LocalNovaComposeColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        NovaPanelStatusText(
            caption = "$serverName · $statusLabel",
            captionColor = if (uiState.status == NovaPolarisSyncStatus.SYNCED) colors.accent else colors.textSecondary,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val bodyHeight: Dp = maxHeight
            NovaPlaySetupBody(
                plan = plan,
                readTitle = stringResource(R.string.nova_play_setup_host_read_title),
                introMaxLines = novaPlaySetupIntroLines(bodyHeight, factCount = plan.facts.size),
                fitHeight = bodyHeight,
                rows = {
                    NovaHostSetupRowList(
                        rows = rows,
                        onExplain = { controller.explainedRow = it },
                        onAdvance = { row ->
                            if (!isTop) return@NovaHostSetupRowList
                            if (row == NovaPlaySetupRow.HOST_DEFAULT_DISPLAY && novaModePickerEligible(uiState.modes.size)) {
                                controller.explainedRow = row
                                panel.push(playInPage(buildHostModePickerState(modes = uiState.modes, title = pickerTitle)))
                            } else {
                                advanceNovaPlaySetupHostRow(row = row, rows = rows, sync = uiState, actions = actions)
                            }
                        },
                        rowModifier = { row, first ->
                            (if (first) Modifier.novaInitialFocus() else Modifier).novaRestorableFocus(row.name)
                        },
                    )
                },
                comparison = {
                    NovaHostSetupComparison(
                        rows = rows,
                        explainedRow = controller.explainedRow,
                        consequenceMaxLines = novaPlaySetupConsequenceLines(bodyHeight, rows.size),
                    )
                },
            )
        }
    }
}
