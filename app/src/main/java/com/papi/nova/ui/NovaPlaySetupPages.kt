package com.papi.nova.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageContent
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelFrame
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaSectionLabel
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaRowRest
import com.papi.nova.ui.panel.novaScrollEdgeFade

/**
 * The pages of Play Setup's panel, at the end edge of the game detail window, all wide so the
 * panel keeps its width as they push and pop. Root is the plan card and the rows; What Will
 * Happen, Where It Runs, a row's options and the desktop Steam decision are pages of their own, so
 * B from any of them returns one step. Where It Runs is also the page Polaris Sync pushes for the
 * host's Default Display.
 */
internal sealed interface PlaySetupPage : NovaPage {
    override val width: NovaPanelWidth get() = NovaPanelWidth.Wide

    /** What the launch will do and the few real choices, for this game or for every game. */
    data class Root(override val title: String) : PlaySetupPage {
        override val key: String get() = KEY_ROOT
    }

    /**
     * Where a game runs, or the host's Default Display, as one list that opens on the current
     * choice. [picker] is read as the page composes, so it follows the host while it is open. A
     * pick pops the page and then runs [onPick]. [places], when the host has Spaces, is where the
     * game opens, drawn as the first band; [modes] are the classic pair for a host with no mode
     * catalog to pick from.
     */
    class PlayIn(
        override val title: String,
        val picker: () -> NovaPlaySetupModePickerState?,
        val onPick: (String) -> Unit,
        val onPickHostDefault: (() -> Unit)? = null,
        val onConfigureHost: () -> Unit = {},
        val places: () -> NovaPlaySetupRowState? = { null },
        val modes: () -> List<NovaPlaySetupOption> = { emptyList() },
    ) : PlaySetupPage {
        override val key: String get() = KEY_PLAY_IN
    }

    /**
     * A row's options on a page of their own (R2), such as Resolution, Video Codec with its encoder,
     * or the host profile's verbs. [bands] is read as the page composes, so the page follows the
     * row while it is open. One A picks and pops, and focus lands back on [row].
     */
    class Options(
        override val title: String,
        val row: NovaPlaySetupRow,
        val bands: @Composable () -> List<NovaPlaySetupBand>,
        val footer: String = "",
    ) : PlaySetupPage {
        override val key: String get() = "$KEY_OPTIONS:${row.name}"
    }

    /** Desktop Steam is running on the host: the three ways this launch can go. */
    data class SteamDecision(override val title: String) : PlaySetupPage {
        override val key: String get() = KEY_STEAM_DECISION
    }

    /**
     * The whole plan, as it stood when its card was pressed: the statement and every fact behind
     * it, each whole and each a stop, so the cursor scrolls through all of it.
     */
    data class Plan(override val title: String, val plan: NovaPlaySetupPlan) : PlaySetupPage {
        override val key: String get() = KEY_PLAN
    }

    companion object {
        const val KEY_ROOT = "play-setup"
        const val KEY_PLAY_IN = "play-in"
        const val KEY_OPTIONS = "play-setup-options"
        const val KEY_STEAM_DECISION = "steam-decision"
        const val KEY_PLAN = "play-setup-plan"
    }
}

/**
 * Play Setup's panel, in the game detail window's own tree: attached to the end edge at full
 * height and rounded on its inner edge only, wide, over the game rather than instead of it.
 * [panel] drives it: open while it has a page, and still drawn while it slides away. B pops one
 * page and at the root runs [onClose], as do the scrim and a drag toward the edge. Once the exit
 * has landed, focus goes back to what [panel] was opened with (R7): the element that held focus
 * inside the panel is gone by then, and nothing else would put it anywhere.
 *
 * [headerEnd] is drawn at the end of every page's header, where Play Setup keeps its scope pill,
 * so the pill stays in place as pages push. While a row that changes in place has focus, the hint
 * bar reads `◂▸ Change · A Next`, because A steps that row rather than selecting it.
 *
 * The scrim is the light one with no backdrop blur. Blur is taken from the window under a panel,
 * and this panel is inside the window it would blur.
 */
@Composable
internal fun NovaPlaySetupPanel(
    panel: NovaPanelState,
    onClose: () -> Unit,
    hints: List<NovaControllerHint> = emptyList(),
    headerEnd: (@Composable () -> Unit)? = null,
    content: NovaPageContent,
) {
    val open = panel.isOpen
    var present by remember { mutableStateOf(open) }
    // Where focus goes once the panel has left the tree. Its host keeps focus inside itself while
    // it is there, so the request waits for the frame after the panel is gone.
    var giveBack by remember { mutableStateOf<NovaFocusReturn?>(null) }
    val changesInPlace = remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) present = true }
    LaunchedEffect(giveBack) {
        val target = giveBack ?: return@LaunchedEffect
        withFrameNanos { }
        giveBack = null
        // A button that is not on screen now, such as Play Setup's while a review is expanded,
        // has nothing to take focus.
        when (target) {
            is NovaFocusReturn.Compose -> runCatching { target.requester.requestFocus() }
            is NovaFocusReturn.View -> target.view?.requestFocus()
            NovaFocusReturn.None -> Unit
        }
    }
    if (!present && !open) return
    val change = NovaControllerHint(
        key = stringResource(R.string.nova_panel_key_left_right),
        label = stringResource(R.string.nova_panel_change),
    )
    val next = NovaControllerHint(
        key = stringResource(R.string.nova_panel_key_a),
        label = stringResource(R.string.nova_panel_next),
    )
    NovaPanelFrame(
        edge = NovaEdge.End,
        width = NovaPanelWidth.Wide,
        open = open,
        onDismissRequest = onClose,
        onClosed = {
            present = false
            // The window's panels do this in NovaSurfaces; an in-tree panel does it here.
            giveBack = panel.takeReturnFocus()
        },
        scrim = NovaScrim.Stream,
    ) {
        CompositionLocalProvider(LocalNovaPlaySetupChangesInPlace provides changesInPlace) {
            val inPlace = changesInPlace.value
            NovaPageStackHost(
                state = panel,
                onCloseRequest = onClose,
                hints = hints,
                leadingHints = if (inPlace) listOf(change) else emptyList(),
                selectHint = if (inPlace) next else null,
                headerEnd = headerEnd,
                content = content,
            )
        }
    }
}

/**
 * Where It Runs: where the game opens, when the host has Spaces, then one row per mode, banded
 * private first and host display second, the current one carrying the check and taking focus when
 * the page opens. One A picks and pops; a mode the host will not take stays a stop so its reason
 * can be read. [card] is the plan card, pinned above the list where the root had it.
 */
@Composable
internal fun NovaPageScope.NovaPlayInPage(
    page: PlaySetupPage.PlayIn,
    card: (@Composable () -> Unit)? = null,
) {
    val state = page.picker()
    val places = page.places()
    val modes = page.modes()
    if (state == null && places == null && modes.isEmpty()) return
    val placesTitle = stringResource(R.string.nova_space_where_it_opens)
    val list: @Composable () -> Unit = {
        if (places != null) {
            NovaSectionLabel(placesTitle)
            if (places.caption.isNotBlank()) {
                // A change in flight, or why it failed, said whole.
                Text(
                    text = places.caption,
                    style = novaPanelType.caption,
                    color = LocalNovaComposeColors.current.textSecondary,
                    modifier = Modifier.padding(bottom = NovaPanelMetrics.SpaceXs),
                )
            }
            val initialPlace = places.options.firstOrNull { it.current && it.enabled && it.onSelect != null }
                ?: places.options.firstOrNull { it.enabled && it.onSelect != null }
                ?: places.options.firstOrNull()
            places.options.forEach { place ->
                NovaPlaySetupOptionRow(
                    option = place,
                    onPick = { if (isTop) place.onSelect?.invoke() },
                    // A place the game cannot open in still has a reason to read, and while a change
                    // is in flight the places may be all this page holds.
                    focusableWhenDisabled = true,
                    modifier = (if (state == null && modes.isEmpty() && place == initialPlace) Modifier.novaInitialFocus() else Modifier)
                        .novaRestorableFocus("place:${place.label}"),
                )
            }
        }
        if (state != null) {
            NovaPlaySetupModeList(
                state = state,
                onPick = { id ->
                    if (isTop) {
                        panel.pop()
                        page.onPick(id)
                    }
                },
                onPickHostDefault = page.onPickHostDefault?.let { pick ->
                    {
                        if (isTop) {
                            panel.pop()
                            pick()
                        }
                    }
                },
                onConfigureHost = { if (isTop) page.onConfigureHost() },
                rowModifier = { key, initial ->
                    (if (initial) Modifier.novaInitialFocus() else Modifier).novaRestorableFocus(key)
                },
            )
        } else if (modes.isNotEmpty()) {
            val initial = modes.firstOrNull { it.current && it.enabled } ?: modes.firstOrNull { it.enabled }
            modes.forEach { mode ->
                NovaPlaySetupOptionRow(
                    option = mode,
                    onPick = {
                        if (isTop) {
                            panel.pop()
                            mode.onSelect?.invoke()
                        }
                    },
                    modifier = (if (mode == initial) Modifier.novaInitialFocus() else Modifier)
                        .novaRestorableFocus("mode:${mode.label}"),
                )
            }
        }
    }
    if (card != null) {
        NovaPlaySetupBody(card = card) { list() }
    } else {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .novaScrollEdgeFade(scroll)
                .verticalScroll(scroll)
                .padding(vertical = NovaPanelMetrics.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        ) {
            list()
        }
    }
}

/**
 * A row's options as a page (R2): the plan card pinned on top, previewing the option under the
 * cursor, then each band of options, then [PlaySetupPage.Options.footer]. It opens on the current
 * option. One A picks it, pops, and runs its choice; focus lands on the row that opened the page.
 * [card] draws the plan card for the option under the cursor, or for none.
 */
@Composable
internal fun NovaPageScope.NovaPlaySetupOptionsPage(
    page: PlaySetupPage.Options,
    card: (@Composable (focused: NovaPlaySetupOption?) -> Unit)? = null,
) {
    val bands = page.bands()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focused = bands.withIndex()
        .flatMap { (index, band) -> band.options.map { novaPlaySetupOptionKey(index, it) to it } }
        .firstOrNull { it.first == focusedKey }?.second
    val initial = novaPlaySetupInitialOption(bands)
    // Drawn here rather than handed to the body, so it follows the cursor on the frame focus moves.
    Column(modifier = Modifier.fillMaxWidth()) {
        if (card != null) {
            card(focused)
            Spacer(modifier = Modifier.height(NovaPanelMetrics.SpaceSm))
        }
        NovaPlaySetupOptionsList(page, bands, initial) { key, hasFocus ->
            if (hasFocus) focusedKey = key else if (focusedKey == key) focusedKey = null
        }
    }
}

@Composable
private fun NovaPageScope.NovaPlaySetupOptionsList(
    page: PlaySetupPage.Options,
    bands: List<NovaPlaySetupBand>,
    initial: String?,
    onFocus: (key: String, hasFocus: Boolean) -> Unit,
) {
    NovaPlaySetupBody(card = null) {
        NovaPlaySetupBands(
            bands = bands,
            onPick = { option ->
                if (isTop && option.onSelect != null) {
                    panel.pop()
                    option.onSelect.invoke()
                }
            },
            rowModifier = { key, _ ->
                (if (key == initial) Modifier.novaInitialFocus() else Modifier)
                    .novaRestorableFocus(key)
                    .onFocusChanged { onFocus(key, it.hasFocus) }
            },
        )
        if (page.footer.isNotBlank()) {
            Text(
                text = page.footer,
                style = novaPanelType.caption,
                color = LocalNovaComposeColors.current.textSecondary,
                modifier = Modifier.padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
            )
        }
    }
}

/** The desktop Steam decision as a page: the host's reason, then the three ways to go on. */
@Composable
internal fun NovaPageScope.NovaSteamDecisionPage(
    decision: NovaDesktopSteamLaunchDecision,
    onChoice: (NovaSteamLaunchChoice) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .novaScrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(vertical = NovaPanelMetrics.SpaceSm),
    ) {
        NovaDesktopSteamLaunchDecisionRows(
            decision = decision,
            onChoice = { choice -> if (isTop) onChoice(choice) },
        )
    }
}

/**
 * The plan's page, What Will Happen: what will happen, then each fact behind it. Every part is a
 * stop with the one focus look and nothing to do on A, so the cursor walks the plan and the page
 * scrolls with it, one part and its neighbour in view at a time; nothing is cut and nothing scrolls
 * by itself. The page opens on the statement.
 */
@Composable
internal fun NovaPageScope.NovaPlaySetupPlanPage(page: PlaySetupPage.Plan) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A part past either edge dissolves into it rather than ending in half a line.
            .novaScrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(vertical = NovaPanelMetrics.SpaceSm)
            .testTag(NOVA_PLAY_SETUP_PLAN_PAGE_TAG),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
    ) {
        NovaPlaySetupReadStop(Modifier.novaInitialFocus()) { NovaPlaySetupPlanStatement(page.plan) }
        page.plan.facts.forEach { fact ->
            NovaPlaySetupReadStop { NovaPlaySetupFact(fact) }
        }
    }
}

/** One part of the plan on its page: a stop the cursor can rest on to read it, which does nothing on A. */
@Composable
private fun NovaPlaySetupReadStop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(NovaRadius.row)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            .semantics(mergeDescendants = true) {}
            .focusable()
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/** The plan's page, for a test to find it. */
internal const val NOVA_PLAY_SETUP_PLAN_PAGE_TAG = "nova-play-setup-plan-page"
