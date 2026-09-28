package com.papi.nova.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageContent
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelFrame
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaValueRow

/**
 * The pages of Play Setup's panel, at the end edge of the game detail window, all wide so the
 * panel keeps its width as they push and pop. Root is the rows and their legend; Where It Runs
 * and the desktop Steam decision are pages of their own, so B from either returns one step.
 * Where It Runs is also the page Polaris Sync pushes for the host's Default Display.
 */
internal sealed interface PlaySetupPage : NovaPage {
    override val width: NovaPanelWidth get() = NovaPanelWidth.Wide

    /** What the launch will do and the few real choices, for this game or for every game. */
    data class Root(override val title: String) : PlaySetupPage {
        override val key: String get() = KEY_ROOT
    }

    /**
     * Where a game runs, or the host's Default Display, as one list that opens on the current
     * choice. [picker] is read as the page composes, so it follows the host while it is open.
     * A pick pops the page and then runs [onPick].
     */
    class PlayIn(
        override val title: String,
        val picker: () -> NovaPlaySetupModePickerState?,
        val onPick: (String) -> Unit,
        val onPickHostDefault: (() -> Unit)? = null,
        val onConfigureHost: () -> Unit = {},
    ) : PlaySetupPage {
        override val key: String get() = KEY_PLAY_IN
    }

    /** Desktop Steam is running on the host: the three ways this launch can go. */
    data class SteamDecision(override val title: String) : PlaySetupPage {
        override val key: String get() = KEY_STEAM_DECISION
    }

    companion object {
        const val KEY_ROOT = "play-setup"
        const val KEY_PLAY_IN = "play-in"
        const val KEY_STEAM_DECISION = "steam-decision"
    }
}

/**
 * Play Setup's panel, in the game detail window's own tree: attached to the end edge at full
 * height and rounded on its inner edge only, wide, over the game rather than instead of it.
 * [panel] drives it: open while it has a page, and still drawn while it slides away. B pops one
 * page and at the root runs [onClose], as do the scrim and a drag toward the edge.
 *
 * The scrim is the light one with no backdrop blur. Blur is taken from the window under a
 * panel, and this panel is inside the window it would blur.
 */
@Composable
internal fun NovaPlaySetupPanel(
    panel: NovaPanelState,
    onClose: () -> Unit,
    hints: List<NovaControllerHint> = emptyList(),
    content: NovaPageContent,
) {
    val open = panel.isOpen
    var present by remember { mutableStateOf(open) }
    LaunchedEffect(open) { if (open) present = true }
    if (!present && !open) return
    NovaPanelFrame(
        edge = NovaEdge.End,
        width = NovaPanelWidth.Wide,
        open = open,
        onDismissRequest = onClose,
        onClosed = { present = false },
        scrim = NovaScrim.Stream,
    ) {
        NovaPageStackHost(state = panel, onCloseRequest = onClose, hints = hints, content = content)
    }
}

/**
 * Where It Runs: one row per mode, banded private first and host display second, the current
 * one carrying the check and taking focus when the page opens. One A picks and pops; a mode the
 * host will not take stays a stop so its reason can be read.
 */
@Composable
internal fun NovaPageScope.NovaPlayInPage(page: PlaySetupPage.PlayIn) {
    val state = page.picker() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NovaPanelMetrics.SpaceSm),
    ) {
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
    }
}

/** The desktop Steam decision as a page: the host's reason, then the three ways to go on. */
@Composable
internal fun NovaPageScope.NovaSteamDecisionPage(
    decision: NovaDesktopSteamLaunchDecision,
    onChoice: (NovaSteamLaunchChoice) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NovaPanelMetrics.SpaceSm),
    ) {
        NovaDesktopSteamLaunchDecisionRows(
            decision = decision,
            onChoice = { choice -> if (isTop) onChoice(choice) },
        )
    }
}

/**
 * This Game or Every Game, as one row whose Left and Right change it in place. Y flips it too,
 * which the panel's hint bar says.
 */
@Composable
internal fun NovaPlaySetupScopeRow(
    scope: NovaPlaySetupScope,
    onSelected: (NovaPlaySetupScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    NovaValueRow(
        title = stringResource(R.string.nova_play_setup_panel_scope),
        options = listOf(
            NovaOption(NovaPlaySetupScope.THIS_GAME, stringResource(R.string.nova_play_setup_scope_this_game)),
            NovaOption(NovaPlaySetupScope.EVERY_GAME, stringResource(R.string.nova_play_setup_every_game)),
        ),
        current = scope,
        onChange = onSelected,
        modifier = modifier.fillMaxWidth(),
    )
}
