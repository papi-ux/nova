package com.papi.nova.ui.panel

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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.papi.nova.BuildConfig
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaControllerHintBar
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

    /** Whether this page is on top. A page animating out is not, and must not act. */
    val isTop: Boolean

    /** Marks the element that takes focus when the page opens: its current value, safe action or first row. */
    fun Modifier.novaInitialFocus(): Modifier

    /** Records this element, at list [index], as where focus returns when a page above it pops. */
    fun Modifier.novaRestorableFocus(key: Any, index: Int = -1): Modifier

    /** Closes the panel, waits for the host window's focus when asked, then runs [action]. */
    fun closeThen(awaitHostFocus: Boolean = false, action: () -> Unit)
}

/** Whether the page being composed is the top of its stack. False while a page animates out. */
val LocalNovaPageIsTop = compositionLocalOf { true }

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
 * Hosts a stack of pages: the page header, the back handler, the focus rules and the page motion.
 *
 * It draws every [NovaCommonPage] itself and hands other pages to [content]. B pops one page and
 * at the root calls [onCloseRequest]. L1 and R1 go to [onShoulder]; Start and Menu close. Those
 * keys act on release and are read in the bubble phase, so a focused control sees them first.
 * With [containFocus], focus cannot leave the host.
 *
 * Focus: a page opens on the element marked [NovaPageScope.novaInitialFocus], or its first
 * focusable; returning to a page restores the element marked [NovaPageScope.novaRestorableFocus]
 * that last held focus, scrolling to it first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovaPageStackHost(
    state: NovaPanelState,
    modifier: Modifier = Modifier,
    containFocus: Boolean = true,
    onShoulder: (NovaShoulder) -> Boolean = { false },
    onCloseRequest: () -> Unit = state::close,
    hints: List<NovaControllerHint> = emptyList(),
    content: NovaPageContent,
) {
    val closeRequest by rememberUpdatedState(onCloseRequest)
    val shoulder by rememberUpdatedState(onShoulder)
    val leave = remember(state) { { if (!state.pop()) closeRequest() } }
    // Registered before any page composes, so every handler a page adds is newer and runs first.
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = state.isOpen, onBack = leave)
    }
    // Keeps the last page on screen while the panel's exit motion runs.
    val retained = remember { RetainedEntry() }
    val entry = state.topEntry?.also { retained.entry = it } ?: retained.entry
    val saveable = rememberSaveableStateHolder()
    val hostView = LocalNovaHostView.current ?: LocalView.current
    val releaseLatch = remember { NovaPressLatch() }
    val select = stringResource(R.string.nova_panel_select)
    val back = stringResource(R.string.nova_panel_back)
    val keyA = stringResource(R.string.nova_panel_key_a)
    val keyB = stringResource(R.string.nova_panel_key_b)
    val allHints = remember(hints, select, back, keyA, keyB) {
        listOf(NovaControllerHint(keyA, select), NovaControllerHint(keyB, back)) + hints
    }
    val formFactor = LocalNovaFormFactor.current
    val padding = NovaPanelMetrics.panelPadding(formFactor)
    val contextPx = with(LocalDensity.current) {
        (NovaPanelMetrics.rowMinHeight(formFactor) + NovaPanelMetrics.RowGap).toPx()
    }
    val contextSpec = remember(contextPx) { NovaContextBringIntoViewSpec(contextPx) }
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
                val handled = event.key in HostKeys
                if (!handled) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (native.repeatCount == 0) releaseLatch.press(code)
                    KeyEventType.KeyUp -> if (releaseLatch.release(code) && !native.isCanceled) {
                        when (event.key) {
                            Key.ButtonL1 -> shoulder(NovaShoulder.Left)
                            Key.ButtonR1 -> shoulder(NovaShoulder.Right)
                            else -> closeRequest()
                        }
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
            val isTop = shown.id == state.topEntry?.id
            val scope = remember(shown) { NovaPageScopeImpl(state, shown, hostView, closeRequest = { closeRequest() }) }
            scope.isTop = isTop
            CompositionLocalProvider(LocalNovaPageIsTop provides isTop) {
                saveable.SaveableStateProvider(shown.id) {
                    Column(
                        modifier = Modifier
                            .semantics { paneTitle = shown.page.title }
                            // A page animating out keeps focus for a frame; nothing it holds may act.
                            .onPreviewKeyEvent { !scope.isTop }
                            .focusRequester(shown.groupRequester)
                            .onFocusChanged { scope.holdsFocus = it.hasFocus }
                            .focusGroup(),
                    ) {
                        NovaPageHeader(
                            title = shown.page.title,
                            parentTitle = state.entryBelow(shown)?.page?.title,
                            onBack = leave,
                            modifier = Modifier.padding(horizontal = padding).padding(top = padding),
                        )
                        Box(modifier = Modifier.padding(horizontal = padding)) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides contextSpec) {
                                val page = shown.page
                                if (page is NovaCommonPage) {
                                    scope.NovaCommonPageContent(page, leave)
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
        NovaControllerHintBar(
            hints = allHints,
            compact = true,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Keys the host answers in the bubble phase: shoulders switch peers, Start and Menu close. */
private val HostKeys = setOf(Key.ButtonL1, Key.ButtonR1, Key.ButtonStart, Key.Menu)

/**
 * Scrolls a focused row into view together with one row of context on the side it scrolls
 * toward, so the row after the focused one is never cut at the list's edge.
 */
@OptIn(ExperimentalFoundationApi::class)
private class NovaContextBringIntoViewSpec(private val contextPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = minOf(contextPx, ((containerSize - size) / 2f).coerceAtLeast(0f))
        val leading = offset - margin
        val trailing = offset + size + margin
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f -> leading
            else -> trailing - containerSize
        }
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
 * The page title. A pushed page shows `‹ Title` under a small label naming the page below;
 * tapping it pops. It is never a focus stop: B does the same on a pad.
 */
@Composable
private fun NovaPageHeader(title: String, parentTitle: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    val backLabel = parentTitle?.let { stringResource(R.string.nova_panel_back_to, it) }
    val back by rememberUpdatedState(onBack)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
        if (parentTitle == null) {
            Text(text = title, style = type.panelTitle, color = colors.textPrimary)
            return@Column
        }
        Text(text = parentTitle, style = type.caption, color = colors.textSecondary)
        Text(
            text = "$BackGlyph $title",
            style = type.pageTitle,
            color = colors.textPrimary,
            modifier = Modifier
                .pointerInput(Unit) { detectTapGestures(onTap = { back() }) }
                .semantics {
                    role = Role.Button
                    onClick(label = backLabel) {
                        back()
                        true
                    }
                },
        )
    }
}

internal const val BackGlyph = "‹"
internal const val OpensGlyph = "›"

private class NovaPageScopeImpl(
    override val panel: NovaPanelState,
    private val entry: NovaStackEntry,
    private val hostView: View,
    private val closeRequest: () -> Unit,
) : NovaPageScope {
    override val listState: LazyListState get() = entry.listState
    override var isTop: Boolean = true
    var holdsFocus: Boolean = false

    override fun Modifier.novaInitialFocus(): Modifier = focusRequester(entry.initialRequester)

    override fun Modifier.novaRestorableFocus(key: Any, index: Int): Modifier =
        focusRequester(entry.requesterFor(key))
            .onFocusChanged { if (it.hasFocus) entry.rememberFocus(key, index) }

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
     * retrying once. A new page, or a failed restore, focuses the marked element, and failing
     * that enters the page's first focusable.
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
        val index = entry.focusIndex
        if (index >= 0 && entry.listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            entry.listState.scrollToItem(index)
            withFrameNanos { }
        }
        val requester = entry.requesterFor(key)
        if (requester.requestFocus()) return true
        withFrameNanos { }
        return requester.requestFocus()
    }

    private suspend fun initialFocus() {
        // The marked element may sit in a list the page scrolls to on its first frame.
        repeat(2) {
            if (entry.initialRequester.requestFocus()) return
            withFrameNanos { }
        }
        entry.groupRequester.requestFocus(FocusDirection.Enter)
    }

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
