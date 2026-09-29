package com.papi.nova.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Who posted a state page, so the legacy helpers can clear their own. */
enum class NovaStateOwner { App, LegacyDialog, LegacySpinner }

/**
 * What B, Back and Escape do on a [NovaStatePage.Problem]. Every caller says which: it is never
 * taken from the order of the page's actions, and it is never a recovery such as Retry, Reconnect
 * or Unlock, which only A on the focused primary runs (R5).
 */
sealed interface NovaProblemBack {
    /** What B runs, or null when B is absorbed. */
    val action: NovaAction?

    /** Runs [action], which takes the page down and lets the screen under it carry on, as Not Now does. */
    data class Continue(override val action: NovaAction) : NovaProblemBack

    /**
     * Runs [action], which takes the page down and closes the screen under it, as Close or Back
     * does. A page whose only way on is closing passes its Close as both the primary and this.
     */
    data class Close(override val action: NovaAction) : NovaProblemBack

    /** Does nothing: the screen can neither carry on nor close until the player picks an action. */
    data object Absorb : NovaProblemBack {
        override val action: NovaAction? get() = null
    }
}

/** A blocking state, drawn full screen above everything else in the window. */
sealed interface NovaStatePage {
    val key: String
    val owner: NovaStateOwner

    /**
     * Something went wrong and the player chooses what next. [primary] is focused, so A runs it.
     * B, Back and Escape do what [back] says, which the caller states: there is no default, so a
     * page that offers only a recovery never runs it on B.
     */
    data class Problem(
        override val key: String,
        val title: String,
        val message: String,
        val primary: NovaAction,
        val back: NovaProblemBack,
        val secondary: List<NovaAction> = emptyList(),
        val eyebrow: String? = null,
        val detail: String? = null,
        val help: NovaAction? = null,
        override val owner: NovaStateOwner = NovaStateOwner.App,
    ) : NovaStatePage

    /**
     * Work the player must wait for. It appears 300ms after it is shown and, once visible, stays
     * at least 500ms. [cancel], when present, is focused and is what B does; without it the page
     * holds focus and absorbs A and B. Once visible, the cancel ignores A and B for the split
     * guard's 400ms, so a press already on its way, such as a player mashing A in game when the
     * stream drops, never ends anything.
     */
    data class Busy(
        override val key: String,
        val title: String,
        val message: StateFlow<String>,
        val progress: StateFlow<Float?>? = null,
        val cancel: NovaAction? = null,
        override val owner: NovaStateOwner = NovaStateOwner.App,
    ) : NovaStatePage

    /** A code to type on another device, such as the pairing PIN, with [close] focused. */
    data class Code(
        override val key: String,
        val title: String,
        val code: String,
        val message: String,
        val close: NovaAction,
        override val owner: NovaStateOwner = NovaStateOwner.App,
    ) : NovaStatePage
}

/**
 * Whether the state page with a key is still posted, read at the moment of a press. NovaSurfaces
 * answers from its live list; a page drawn on its own is always posted.
 */
internal val LocalNovaStatePosted = compositionLocalOf<(String) -> Boolean> { { true } }

/**
 * A full-screen state page over the window colour at 0.94, with no card: a centred column at most
 * 480dp wide, clear of the insets and the TV title-safe area, that scrolls rather than clips.
 * Focus starts on the page's action and cannot leave the page; B runs its back action.
 *
 * Its actions run only while the page is still posted. An action that should run once takes its
 * page down first, as the legacy helpers do, and a second press that lands before the page has
 * gone then does nothing. A state page is a panel host: it is drawn at the panel density for the
 * window ([NovaPanelDensityHost]).
 */
@Composable
fun NovaStateScreen(page: NovaStatePage, modifier: Modifier = Modifier) {
    NovaPanelDensityHost { NovaStateScreenBody(page, modifier) }
}

@Composable
private fun NovaStateScreenBody(page: NovaStatePage, modifier: Modifier) {
    val colors = LocalNovaComposeColors.current
    val tvSafe = LocalNovaFormFactor.current == NovaFormFactor.Television
    val focusTarget = remember(page.key) { FocusRequester() }
    val posted = LocalNovaStatePosted.current
    val act = remember(page.key, posted) { NovaStateActions { posted(page.key) } }
    LaunchedEffect(page.key) {
        // Whatever held focus underneath may still be settling; ask until the action has it.
        repeat(NovaPanelMetrics.StateFocusAttempts) {
            withFrameNanos { }
            if (focusTarget.requestFocus()) return@LaunchedEffect
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.window.copy(alpha = NovaPanelMetrics.StatePageAlpha))
            // Touches stop here rather than reaching the panel or screen underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(
                if (tvSafe) {
                    Modifier.padding(horizontal = NovaPanelMetrics.TvSafeHorizontal, vertical = NovaPanelMetrics.TvSafeVertical)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = NovaPanelMetrics.StateColumnMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(NovaPanelMetrics.SpaceXl)
                .semantics { paneTitle = page.title }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        ) {
            when (page) {
                is NovaStatePage.Problem -> ProblemContent(page, focusTarget, act)
                is NovaStatePage.Busy -> BusyContent(page, focusTarget, act)
                is NovaStatePage.Code -> CodeContent(page, focusTarget, act)
            }
        }
    }
}

private val NovaStatePage.title: String
    get() = when (this) {
        is NovaStatePage.Problem -> title
        is NovaStatePage.Busy -> title
        is NovaStatePage.Code -> title
    }

/** Runs a state page's actions while [posted] says the page is still up. */
private class NovaStateActions(private val posted: () -> Boolean) {
    fun run(action: NovaAction) {
        if (posted()) action.run()
    }
}

@Composable
private fun ProblemContent(page: NovaStatePage.Problem, focusTarget: FocusRequester, act: NovaStateActions) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    var detailShown by rememberSaveable(page.key) { mutableStateOf(false) }
    val back by rememberUpdatedState(page.back)
    // Always enabled, so an absorbed B stops here rather than reaching whatever is underneath.
    NovaBackHandler(active = true) { back.action?.let(act::run) }
    page.eyebrow?.let {
        Text(text = it.uppercase(Locale.getDefault()), style = type.sectionLabel, color = colors.accent, textAlign = TextAlign.Center)
    }
    Text(text = page.title, style = type.stateTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
    Text(text = page.message, style = type.rowTitle, color = colors.textSecondary, textAlign = TextAlign.Center)
    // The actions as one stack, a row gap apart as rows are, with Details last among them as a
    // button like Help: it sat above the recovery as a left-aligned row of another kind.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
    ) {
        StateAction(page.primary, act, primary = true, modifier = Modifier.focusRequester(focusTarget))
        page.secondary.forEach { StateAction(it, act, primary = false) }
        page.help?.let { StateAction(it, act, primary = false) }
        page.detail?.let { detail ->
            NovaPanelButton(
                text = stringResource(if (detailShown) R.string.nova_panel_hide_details else R.string.nova_panel_details),
                onClick = { detailShown = !detailShown },
                modifier = Modifier.fillMaxWidth(),
            )
            if (detailShown) {
                Text(text = detail, style = type.caption, color = colors.textMuted)
            }
        }
    }
}

@Composable
private fun BusyContent(page: NovaStatePage.Busy, focusTarget: FocusRequester, act: NovaStateActions) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val message by page.message.collectAsState()
    val progress = page.progress?.collectAsState()?.value
    val cancel = page.cancel
    val working = stringResource(R.string.nova_panel_working)
    // The page appears under thumbs still busy with whatever it interrupted. Its cancel waits out
    // the split's guard from the moment it is visible, as an armed End waits after arming.
    val guardPassed = remember(page.key) { mutableStateOf(false) }
    LaunchedEffect(page.key) {
        delay(NovaPanelMetrics.SplitGuardMillis)
        guardPassed.value = true
    }
    // Without a cancel, B is held here so the work cannot be left half done.
    NovaBackHandler(active = true) { if (guardPassed.value) cancel?.let(act::run) }
    Text(text = page.title, style = type.stateTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            color = colors.accent,
            trackColor = colors.divider,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = NovaPanelMetrics.ProgressStroke,
            modifier = Modifier.size(NovaPanelMetrics.ProgressSize).semantics { contentDescription = working },
        )
    }
    if (message.isNotEmpty()) {
        Text(
            text = message,
            style = type.rowTitle,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (cancel != null) {
        StateAction(
            cancel,
            act,
            primary = false,
            modifier = Modifier.focusRequester(focusTarget),
            guard = { guardPassed.value },
        )
    } else {
        // Holds focus so A and B land here, where they do nothing.
        Box(
            modifier = Modifier
                .focusRequester(focusTarget)
                .semantics { contentDescription = working }
                .novaClickable(onClick = {}),
        )
    }
}

@Composable
private fun CodeContent(page: NovaStatePage.Code, focusTarget: FocusRequester, act: NovaStateActions) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val close by rememberUpdatedState(page.close)
    NovaBackHandler(active = true) { act.run(close) }
    Text(text = page.title, style = type.stateTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
    Text(text = page.code, style = type.code, color = colors.textPrimary, textAlign = TextAlign.Center)
    Text(text = page.message, style = type.rowTitle, color = colors.textSecondary, textAlign = TextAlign.Center)
    StateAction(page.close, act, primary = true, modifier = Modifier.focusRequester(focusTarget))
}

@Composable
private fun StateAction(
    action: NovaAction,
    act: NovaStateActions,
    primary: Boolean,
    modifier: Modifier = Modifier,
    /** Read at the press: false swallows it. */
    guard: () -> Boolean = { true },
) {
    NovaPanelButton(
        text = action.label,
        onClick = { if (guard()) act.run(action) },
        primary = primary,
        destructive = action.destructive,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The top page of [pages], with the Busy timing: a Busy page shows 300ms after it is posted, or
 * never if it is gone by then, and once visible stays at least 500ms, even when dismissed sooner.
 * [onShowingChange] reports whether any page is on screen, so the window knows when it is idle.
 * [isPosted] reads the live list, so a page's actions stop the moment it is dismissed.
 */
@Composable
internal fun NovaStatePages(
    pages: List<NovaStatePage>,
    onShowingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isPosted: (String) -> Boolean = { key -> pages.any { it.key == key } },
) {
    var shown by remember { mutableStateOf<NovaStatePage?>(null) }
    var heldLongEnough by remember { mutableStateOf(true) }
    val showing by rememberUpdatedState(onShowingChange)
    val target = pages.lastOrNull()
    LaunchedEffect(target?.key) {
        if (shown?.key == target?.key) return@LaunchedEffect
        snapshotFlow { heldLongEnough }.first { it }
        if (target is NovaStatePage.Busy) delay(NovaPanelMetrics.BusyShowDelayMillis)
        shown = target
    }
    LaunchedEffect(shown?.key) {
        showing(shown != null)
        if (shown is NovaStatePage.Busy) {
            heldLongEnough = false
            delay(NovaPanelMetrics.BusyMinimumMillis)
        }
        heldLongEnough = true
    }
    // The latest copy of the shown page, so update() reaches it; a dismissed page keeps its last copy.
    val live = shown?.let { current -> pages.firstOrNull { it.key == current.key } ?: current }
    if (live != null) {
        CompositionLocalProvider(LocalNovaStatePosted provides isPosted) {
            NovaStateScreen(page = live, modifier = modifier)
        }
    }
}
