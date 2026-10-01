package com.papi.nova.ui.panel

import com.papi.nova.ui.novaAvoidCameraCutout

import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.papi.nova.BuildConfig
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaRadius
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Draws an owner's page. [NovaCommonPage]s never reach it; the host draws those itself. */
typealias NovaPageContent = @Composable NovaPageScope.(NovaPage) -> Unit

enum class NovaShoulder { Left, Right }

/** What a page can reach while the host composes it. */
@Stable
interface NovaPageScope {
    val panel: NovaPanelState

    /** This page's list state, kept with its stack entry so a pop returns to the same place. */
    val listState: LazyListState

    /**
     * Whether this page is on top and not covered by a state page. A page animating out is not,
     * and must not act. It is read live, so composition follows it and an event handler can
     * check it at the moment of the event.
     */
    val isTop: Boolean

    /** Marks the element that takes focus when the page opens: its current value, safe action or first row. */
    fun Modifier.novaInitialFocus(): Modifier

    /**
     * Names where focus starts when this page first opens, before any row is composed: the element
     * marked [novaRestorableFocus] with [key], at [index] in [listState], or -1 when it is not in
     * that list. A long lazy list does not compose its current row until it scrolls there, so the
     * row's own [novaInitialFocus] cannot answer in time; this can. The host scrolls [index] into
     * view with a row of context above it, waits for the row, then focuses it. If it takes no
     * focus, as when that value has gone, the list goes back to its top and the element marked
     * [novaInitialFocus] takes focus, and failing that the page's first focusable. A row that is
     * disabled still takes it, to show why. Call it while composing the page; returning to the
     * page restores what last held focus instead.
     */
    fun novaInitialFocusAt(key: Any, index: Int = -1)

    /** Records this element, at list [index], as where focus returns when a page above it pops. */
    fun Modifier.novaRestorableFocus(key: Any, index: Int = -1): Modifier

    /** Closes the panel, waits for the host window's focus when asked, then runs [action]. */
    fun closeThen(awaitHostFocus: Boolean = false, action: () -> Unit)
}

/**
 * Whether the page being composed is the top of its stack. False while a page animates out, and
 * while a state page covers the panel.
 */
val LocalNovaPageIsTop = compositionLocalOf { true }

/**
 * Answers, at the moment of an event, whether the page being composed may still act. A back
 * handler's enabled flag follows only the next composition, so a second B in the same frame
 * would otherwise reach the handler of a page that has already left.
 */
internal val LocalNovaPageMayAct = compositionLocalOf<() -> Boolean> { { true } }

/**
 * True while a state page is on screen above the panel in the same window, or posted and about to
 * show, as a Busy page is for its first 300ms. Only one surface is active: a covered panel takes
 * no keys, no Back, no touches (its scrim and drag included), no focus and no accessibility
 * actions, which all belong to the state page. Its armed splits disarm and its pending presses are
 * forgotten, so a press that began on the panel never finishes on either surface. When the last
 * state page goes, the top page takes focus back on the element that held it.
 */
internal val LocalNovaPanelCovered = compositionLocalOf { false }

/**
 * The view whose window [NovaPageScope.closeThen] waits on: the activity or stream window under a
 * panel window. In-tree hosts leave it unset and wait on their own window.
 */
internal val LocalNovaHostView = staticCompositionLocalOf<View?> { null }

/** False inside a portrait sheet, which wraps its content instead of filling the height. */
internal val LocalNovaPanelFillsHeight = staticCompositionLocalOf { true }

/**
 * Changes when a state page above the panel goes away, so the top page takes focus back: the
 * element that held it is gone with the state page, and focus is never left on nothing.
 */
internal val LocalNovaFocusRefresh = compositionLocalOf { 0 }

/**
 * What A does on the element that has focus, where it is not Select, so the hint bar says so. A
 * value that changes in place steps on A ([Next]), flips ([Toggle]), takes an exact value
 * ([TypeValue]) or does nothing more ([Change]); those lead the bar with `◂▸ Change`. A text field
 * types on A ([Type]), and a stop that is only there to be read, or a row that cannot act, has
 * nothing on A at all ([Read]), so the bar leaves A out rather than promise a Select.
 */
enum class NovaFocusHint(internal val changesInPlace: Boolean) {
    Next(true),
    Toggle(true),
    TypeValue(true),
    Change(true),
    Type(false),
    Read(false),
}

/** The hint of the element that has focus in a page host, claimed and released by [novaFocusHint]. */
@Stable
internal class NovaFocusHintState {
    private var owner: Any? = null
    var hint: NovaFocusHint? by mutableStateOf(null)
        private set

    fun claim(owner: Any, hint: NovaFocusHint?) {
        this.owner = owner
        this.hint = hint
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        hint = null
    }
}

internal val LocalNovaFocusHint = staticCompositionLocalOf<NovaFocusHintState?> { null }

/**
 * While this element has focus, the page host's hint bar says [hint] for A, and leads with
 * `◂▸ Change` where the element changes in place; null says Select. It follows the focus of the
 * focus target after it in the chain, as [novaFocusRing] does. Outside a page host it does nothing.
 */
fun Modifier.novaFocusHint(hint: NovaFocusHint?): Modifier = this then NovaFocusHintElement(hint)

private data class NovaFocusHintElement(val hint: NovaFocusHint?) : ModifierNodeElement<NovaFocusHintNode>() {
    override fun create() = NovaFocusHintNode(hint)

    override fun update(node: NovaFocusHintNode) {
        node.update(hint)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "novaFocusHint"
    }
}

private class NovaFocusHintNode(private var hint: NovaFocusHint?) :
    Modifier.Node(), FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var claimed: NovaFocusHintState? = null

    fun update(hint: NovaFocusHint?) {
        this.hint = hint
        claimed?.claim(this, hint)
    }

    override fun onFocusEvent(focusState: FocusState) {
        val state = currentValueOf(LocalNovaFocusHint) ?: return
        if (focusState.hasFocus) {
            state.claim(this, hint)
            claimed = state
        } else {
            state.release(this)
            claimed = null
        }
    }

    override fun onDetach() {
        // A row that leaves with focus, as when Play Setup's Y swaps every row, takes its hint along.
        claimed?.release(this)
        claimed = null
    }
}

/**
 * Hosts a stack of pages: the page header, the back handler, the focus rules and the page motion.
 *
 * It draws every [NovaCommonPage] itself and hands other pages to [content]. B pops one page and
 * at the root calls [onCloseRequest]. L1 and R1 go to [onShoulder] when there is one, and pass to
 * the screen around the host when there is not; Start and Menu close. Those keys act on release
 * and are read in the bubble phase, so a focused control sees them first. With [containFocus],
 * focus cannot leave the host.
 *
 * Focus: a page opens on the element it names with [NovaPageScope.novaInitialFocusAt], scrolled
 * to before its row composes, or else on the element marked [NovaPageScope.novaInitialFocus], or
 * else its first focusable; returning to a page restores the element marked
 * [NovaPageScope.novaRestorableFocus] that last held focus, scrolling to it first.
 *
 * The hint bar reads [leadingHints], then A with [selectHint]'s label or Select, B Back, then
 * [hints]. The element with focus can change that through [novaFocusHint]: a value that changes in
 * place leads with `◂▸ Change` and says what A does there, and a stop only there to be read drops A.
 * [headerEnd] is drawn at the end of every page's header line, such as Play Setup's scope pill.
 * With [remoteKeys], A and B are named as a TV remote's OK and Back, for a screen whose last key
 * came from a remote.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovaPageStackHost(
    state: NovaPanelState,
    modifier: Modifier = Modifier,
    containFocus: Boolean = true,
    onShoulder: ((NovaShoulder) -> Unit)? = null,
    onCloseRequest: () -> Unit = state::close,
    hints: List<NovaControllerHint> = emptyList(),
    leadingHints: List<NovaControllerHint> = emptyList(),
    selectHint: NovaControllerHint? = null,
    headerEnd: (@Composable () -> Unit)? = null,
    remoteKeys: Boolean = false,
    /** Hears the height of the hint bar with its margins, for a screen that lines up beside the pages. */
    onHintBarBlock: ((androidx.compose.ui.unit.Dp) -> Unit)? = null,
    content: NovaPageContent,
) {
    val closeRequest by rememberUpdatedState(onCloseRequest)
    val shoulder by rememberUpdatedState(onShoulder)
    val leave = remember(state) { { if (!state.pop()) closeRequest() } }
    val covered = LocalNovaPanelCovered.current
    val coveredNow = rememberUpdatedState(covered)
    // Registered before any page composes, so every handler a page adds is newer and runs first.
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = state.isOpen && !covered, onBack = leave)
    }
    // Keeps the last page on screen while the panel's exit motion runs.
    val retained = remember { RetainedEntry() }
    val entry = state.topEntry?.also { retained.entry = it } ?: retained.entry
    val saveable = rememberSaveableStateHolder()
    val hostView = LocalNovaHostView.current ?: LocalView.current
    val releaseLatch = remember { NovaPressLatch() }
    // A Start or shoulder press the panel saw go down belongs to the surface that saw it: covered
    // or uncovered, its release does nothing here.
    LaunchedEffect(covered) { releaseLatch.clear() }
    val select = stringResource(R.string.nova_panel_select)
    val back = stringResource(if (remoteKeys) R.string.nova_controller_hint_remote_back_label else R.string.nova_panel_back)
    val keyA = stringResource(if (remoteKeys) R.string.nova_controller_hint_remote_center else R.string.nova_panel_key_a)
    val keyB = stringResource(if (remoteKeys) R.string.nova_controller_hint_remote_back else R.string.nova_panel_key_b)
    val change = NovaControllerHint(stringResource(R.string.nova_panel_key_left_right), stringResource(R.string.nova_panel_change))
    val aLabels = mapOf(
        NovaFocusHint.Next to stringResource(R.string.nova_panel_next),
        NovaFocusHint.Toggle to stringResource(R.string.nova_panel_toggle),
        NovaFocusHint.TypeValue to stringResource(R.string.nova_panel_type_value),
        NovaFocusHint.Type to stringResource(R.string.nova_panel_type),
    )
    val focusHints = remember { NovaFocusHintState() }
    val focusHint = focusHints.hint
    val allHints = remember(hints, leadingHints, selectHint, select, back, keyA, keyB, focusHint, change, aLabels) {
        val a = when (focusHint) {
            null -> selectHint ?: NovaControllerHint(keyA, select)
            NovaFocusHint.Read, NovaFocusHint.Change -> null
            else -> NovaControllerHint(keyA, aLabels.getValue(focusHint))
        }
        val lead = if (focusHint?.changesInPlace == true) listOf(change) else emptyList()
        lead + leadingHints + listOfNotNull(a, NovaControllerHint(keyB, back)) + hints
    }
    val formFactor = LocalNovaFormFactor.current
    val panelDensity = LocalNovaPanelDensity.current
    val padding = NovaPanelMetrics.panelPadding(formFactor)
    val focusRefresh = LocalNovaFocusRefresh.current

    Column(
        modifier = modifier
            .then(
                if (containFocus) Modifier.focusProperties { onExit = { cancelFocusChange() } } else Modifier
            )
            .focusGroup()
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                val code = native.keyCode
                val side = when (event.key) {
                    Key.ButtonL1 -> NovaShoulder.Left
                    Key.ButtonR1 -> NovaShoulder.Right
                    else -> null
                }
                // Shoulders stay the screen's unless the owner takes them.
                val handled = if (side != null) shoulder != null else event.key in CloseKeys
                if (!handled) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (native.repeatCount == 0) releaseLatch.press(code)
                    KeyEventType.KeyUp -> if (releaseLatch.release(code) && !native.isCanceled) {
                        if (side != null) shoulder?.invoke(side) else closeRequest()
                    }
                }
                true
            },
    ) {
        val pageModifier = if (LocalNovaPanelFillsHeight.current) {
            Modifier.weight(1f)
        } else {
            Modifier.weight(1f, fill = false)
        }
        val layoutDirection = LocalLayoutDirection.current
        val pushOffset = with(LocalDensity.current) { NovaPanelMetrics.PagePushOffset.roundToPx() }
        AnimatedContent(
            targetState = entry,
            modifier = pageModifier,
            contentKey = { it?.id },
            transitionSpec = {
                pageTransition(
                    push = (targetState?.id ?: 0L) >= (initialState?.id ?: 0L),
                    fromLeft = (state.edge == NovaEdge.End) == (layoutDirection == LayoutDirection.Ltr),
                    offsetPx = pushOffset,
                )
            },
            label = "NovaPageStack",
        ) { shown ->
            if (shown == null) return@AnimatedContent
            val scope = remember(shown) {
                NovaPageScopeImpl(state, shown, hostView, coveredNow, closeRequest = { closeRequest() }, leave = leave)
            }
            val isTop = scope.isTop
            CompositionLocalProvider(
                LocalNovaPageIsTop provides isTop,
                LocalNovaPageMayAct provides scope.mayAct,
                LocalNovaFocusHint provides focusHints,
            ) {
                saveable.SaveableStateProvider(shown.id) {
                    Column(
                        modifier = Modifier
                            .semantics { paneTitle = shown.page.title }
                            // A page animating out keeps focus and stays under the finger for its
                            // exit; read at the event, not at the last frame, nothing it holds acts.
                            .onPreviewKeyEvent { !scope.isTop }
                            .pointerInput(scope) { swallowUnless { scope.isTop } }
                            .focusRequester(shown.groupRequester)
                            .onFocusChanged { scope.holdsFocus = it.hasFocus }
                            .focusGroup(),
                    ) {
                        NovaPageHeader(
                            title = shown.page.title,
                            parentTitle = state.entryBelow(shown)?.page?.title,
                            onBack = { scope.exit.back() },
                            end = headerEnd,
                            modifier = Modifier
                                // Over the page, for touch: the back target reaches past a compact
                                // line into the page's top padding, and must take a tap there first.
                                .zIndex(1f)
                                .padding(horizontal = padding)
                                .padding(top = NovaPanelMetrics.headerTopPadding(formFactor, panelDensity)),
                        )
                        Box(modifier = Modifier.padding(horizontal = padding)) {
                            NovaRowContextScrolling {
                                val page = shown.page
                                if (page is NovaCommonPage) {
                                    scope.NovaCommonPageContent(page, scope.exit)
                                } else {
                                    scope.content(page)
                                }
                            }
                        }
                    }
                    LaunchedEffect(shown.id, isTop, focusRefresh) {
                        if (isTop) scope.settleFocus()
                    }
                }
            }
        }
        val hintDensity = LocalDensity.current
        NovaPanelHintBar(
            hints = allHints,
            modifier = Modifier
                .then(
                    if (onHintBarBlock != null) {
                        Modifier.onSizeChanged { with(hintDensity) { onHintBarBlock(it.height.toDp()) } }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = padding, vertical = NovaPanelMetrics.hintBarMargin(formFactor, panelDensity)),
        )
    }
}

/**
 * The one controller hint bar: every panel draws it under its pages, and a screen outside a panel
 * that shows controller hints (the Artwork studio, the Space chooser) draws this same bar. A key
 * chip and its label for each hint, in the panel type, so a television reads them 2sp larger and
 * a compact panel's keys stay at 12sp. Its hints start 12dp in, on the text line of the rows
 * above it. They wrap onto a second line when they must, rather than scrolling sideways and
 * cutting the last hint at the edge.
 */
@Composable
fun NovaPanelHintBar(hints: List<NovaControllerHint>, modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val shape = RoundedCornerShape(NovaRadius.hero)
    val chip = RoundedCornerShape(NovaRadius.row)
    val separator = stringResource(R.string.nova_panel_hint_separator)
    val description = remember(hints, separator) { hints.joinToString(separator) { "${it.key} ${it.label}" } }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surfaces.panel.copy(alpha = NovaPanelMetrics.HintBarAlpha * LocalNovaMenuOpacityScale.current))
            .border(NovaPanelMetrics.Hairline, surfaces.panelBorder, shape)
            .semantics { contentDescription = description }
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceXs),
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs),
            ) {
                Text(
                    text = hint.key,
                    // A key named in letters and digits, such as L1/R1, reads as one word, not spaced
                    // out; the ◂▸ arrows keep their spacing, which unspaced ran into one diamond.
                    style = if (hint.key.length > 1 && hint.key.any { it.isLetterOrDigit() }) {
                        type.hintKey.copy(letterSpacing = 0.sp)
                    } else {
                        type.hintKey
                    },
                    color = colors.onAccent,
                    modifier = Modifier
                        .clip(chip)
                        .background(colors.accent)
                        .padding(horizontal = NovaPanelMetrics.SpaceSm, vertical = NovaPanelMetrics.HintChipPadding),
                )
                Text(text = hint.label, style = type.caption, color = colors.textSecondary)
            }
        }
    }
}

/** Keys that close the panel from anywhere in it, answered in the bubble phase. */
private val CloseKeys = setOf(Key.ButtonStart, Key.Menu)

/** Consumes every pointer event before the content sees it whenever [acts] says no. */
private suspend fun PointerInputScope.swallowUnless(acts: () -> Boolean) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!acts()) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * How a host-drawn page acts and leaves. Each checks, at the moment of the press, that the page
 * may still act: a second press that lands before the page has gone, or a tap on a page sliding
 * out, does nothing.
 */
internal class NovaPageExit(
    private val mayAct: () -> Boolean,
    private val answer: () -> Unit,
    private val leave: () -> Unit,
) {
    /** Runs [action] in place, while the page is on top. */
    fun act(action: () -> Unit) {
        if (mayAct()) action()
    }

    /** The page's own answer: pops the page, or closes the panel at the root, then runs [action]. */
    fun leaveThen(action: () -> Unit = {}) {
        if (!mayAct()) return
        answer()
        leave()
        action()
    }

    /** Leaves without answering, as the header does: a Confirm left this way stays. */
    fun back() {
        if (mayAct()) leave()
    }
}

private class RetainedEntry {
    var entry: NovaStackEntry? = null
}

/** A pushed page slides [offsetPx] in from the panel's inner side and fades; a pop reverses it. */
private fun pageTransition(push: Boolean, fromLeft: Boolean, offsetPx: Int): ContentTransform {
    val sign = if (fromLeft) -1 else 1
    val enterFrom = if (push) sign * offsetPx else -sign * offsetPx
    val fade = tween<Float>(NovaPanelMetrics.PageFadeMillis)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(NovaPanelMetrics.PageFadeMillis)
    return (slideInHorizontally(slide) { enterFrom } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { -enterFrom } + fadeOut(fade))
}

/**
 * The page title, on one line at every density: the root's title, or `‹ Title` on a pushed page,
 * at least [NovaPanelMetrics.headerHeight] tall, so nothing under it moves when a page opens.
 * Tapping a pushed page's title pops it. It is never a focus stop: B does the same on a pad. The
 * page below is named to accessibility, as the tap's label. On a television and in tall windows a
 * pushed page once had a second line above its title naming the page below, and its rows jumped
 * down by that line whenever a page opened.
 */
@Composable
private fun NovaPageHeader(
    title: String,
    parentTitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    end: (@Composable () -> Unit)? = null,
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val backLabel = parentTitle?.let { stringResource(R.string.nova_panel_back_to, it) }
    val back by rememberUpdatedState(onBack)
    val height = NovaPanelMetrics.headerHeight(LocalNovaFormFactor.current, LocalNovaPanelDensity.current)
    // The touch B, though never a focus stop. A compact line is 40dp, and the target reaches past it
    // above and below to the full header's 48dp, so a finger has 48dp and the line keeps its height (C24).
    val reach = (NovaPanelMetrics.HeaderHeight - height) / 2
    val backTarget = Modifier
        .testTag(NovaPageBackTag)
        .pointerInput(Unit) { detectTapGestures(onTap = { back() }) }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(label = backLabel) {
                back()
                true
            }
        }
    // The line keeps the room under the title inside itself, so the first tile never sits flush
    // against it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = height),
    ) {
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
            if (parentTitle == null) {
                Text(text = title, modifier = Modifier.novaAvoidCameraCutout(), style = type.panelTitle, color = colors.textPrimary)
            } else {
                // The ‹ hangs into the gutter, so the title starts on the text line of the rows
                // under it, as the mockup draws it. As tall as the line, for the touch B.
                NovaPageBack(title = title, modifier = Modifier.novaAvoidCameraCutout().novaTouchReach(reach, backTarget).heightIn(min = height))
            }
        }
        end?.let {
            Spacer(Modifier.width(NovaPanelMetrics.SpaceSm))
            it()
        }
    }
}

/**
 * A pushed page's title after its accent `‹`, which hangs [NovaPageBackHang] into the gutter so the
 * title starts on the rows' text line.
 */
@Composable
private fun NovaPageBack(title: String, modifier: Modifier) {
    val colors = LocalNovaComposeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.offset(x = -NovaPageBackHang),
    ) {
        NovaChevron(back = true, tint = colors.accent)
        Text(text = title, style = novaPanelType.pageTitle, color = colors.textPrimary)
    }
}

/** How far a page's `‹` stands out into the gutter: its 18dp less the 12dp gutter. */
private val NovaPageBackHang = NovaPanelMetrics.CurrentMarkSize - NovaPanelMetrics.SpaceMd

/** A pushed page's header back target: its `‹` and its title, for a test to find it. */
internal const val NovaPageBackTag = "nova-page-back"

internal const val BackGlyph = "‹"
internal const val OpensGlyph = "›"

private class NovaPageScopeImpl(
    override val panel: NovaPanelState,
    private val entry: NovaStackEntry,
    private val hostView: View,
    private val covered: State<Boolean>,
    private val closeRequest: () -> Unit,
    leave: () -> Unit,
) : NovaPageScope {
    override val listState: LazyListState get() = entry.listState

    // Snapshot reads: composition follows them, and an event reads them as they are now.
    override val isTop: Boolean get() = panel.topEntry?.id == entry.id && !covered.value
    val mayAct: () -> Boolean = { isTop }
    val exit = NovaPageExit(mayAct, answer = { entry.answered = true }, leave = leave)
    var holdsFocus: Boolean = false

    override fun Modifier.novaInitialFocus(): Modifier = focusRequester(entry.initialRequester)

    override fun novaInitialFocusAt(key: Any, index: Int) {
        entry.startKey = key
        entry.startIndex = index
    }

    override fun Modifier.novaRestorableFocus(key: Any, index: Int): Modifier =
        focusRequester(entry.requesterFor(key))
            .onFocusChanged {
                if (it.hasFocus) {
                    entry.rememberFocus(key, index)
                } else if (entry.letGo(key) && isTop) {
                    // Focus moved on within this page, perhaps to an element that is not
                    // restorable, so this key no longer says where it is. A page covered by a
                    // state page, or by a page pushed over it, keeps it for its return.
                    entry.forgetFocus(key)
                }
            }

    override fun closeThen(awaitHostFocus: Boolean, action: () -> Unit) {
        closeRequest()
        val scope = hostView.findViewTreeLifecycleOwner()?.lifecycleScope
        if (!awaitHostFocus || scope == null) {
            action()
            return
        }
        scope.launch {
            withTimeoutOrNull(NovaPanelMetrics.HostFocusTimeoutMillis) { hostView.awaitWindowFocus() }
            action()
        }
    }

    /**
     * Returning to a page restores the element that last held focus, scrolling to it first and
     * retrying once. A new page, or a failed restore, focuses the element the page named, then the
     * marked element, and failing that enters the page's first focusable.
     */
    suspend fun settleFocus() {
        withFrameNanos { }
        val restored = entry.shown && restoreFocus()
        if (!restored) initialFocus()
        entry.shown = true
        if (BuildConfig.DEBUG) {
            withFrameNanos { }
            if (!holdsFocus) Log.w(TAG, "No element holds focus on page ${entry.page.key}")
        }
    }

    private suspend fun restoreFocus(): Boolean {
        val key = entry.focusKey ?: return false
        return focusListItem(key, entry.focusIndex, rowAbove = false)
    }

    private suspend fun initialFocus() {
        entry.startKey?.let { key ->
            if (focusListItem(key, entry.startIndex, rowAbove = true)) return
            // Gone, or unable to hold focus: the fallbacks start from the top of the list.
            if (entry.startIndex > 0 && listLaidOut()) {
                entry.listState.scrollToItem(0)
                withFrameNanos { }
            }
        }
        // The marked element may sit in a list the page scrolls to on its first frame.
        repeat(2) {
            if (entry.initialRequester.requestFocus()) return
            withFrameNanos { }
        }
        entry.groupRequester.requestFocus(FocusDirection.Enter)
    }

    /**
     * Brings list [index] into view when none of it shows, with the row above it for context when
     * [rowAbove], waits a frame for its row to compose, then focuses [key]'s element, asking again
     * a frame later. A page that never laid out its list is not scrolled: a scroll would wait for
     * a first layout that never comes.
     */
    private suspend fun focusListItem(key: Any, index: Int, rowAbove: Boolean): Boolean {
        if (index >= 0 && listLaidOut() && entry.listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            entry.listState.scrollToItem(if (rowAbove) (index - 1).coerceAtLeast(0) else index)
            withFrameNanos { }
        }
        val requester = entry.requesterFor(key)
        if (requester.requestFocus()) return true
        withFrameNanos { }
        return requester.requestFocus()
    }

    private fun listLaidOut(): Boolean = entry.listState.layoutInfo.totalItemsCount > 0

    private companion object {
        const val TAG = "NovaPanel"
    }
}

/** Suspends until this view's window has focus. */
private suspend fun View.awaitWindowFocus() {
    if (hasWindowFocus()) return
    suspendCancellableCoroutine { continuation ->
        val observer = viewTreeObserver
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        continuation.invokeOnCancellation {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}
