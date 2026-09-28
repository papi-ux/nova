package com.papi.nova.ui.panel

import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaRadius
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay

/**
 * Whether a split confirm is armed. Hoisted, so a crowded parent can make room while it is (the
 * Command Center header collapses Close and Disconnect) and a companion deck can disarm it from
 * its own back handling.
 */
@Stable
class NovaSplitConfirmState {
    var armed: Boolean by mutableStateOf(false)
        private set

    /** Open once the guard after arming has passed; until then the destructive half ignores activation. */
    internal var guardOpen: Boolean by mutableStateOf(false)

    /** Counts arms, so each one restarts the guard. */
    internal var arms: Int by mutableIntStateOf(0)
        private set

    internal val buttonRequester = FocusRequester()
    internal val stayRequester = FocusRequester()

    /** Whether either half holds focus, so a disarm knows whether focus must go back to the button. */
    internal var pairHasFocus: Boolean = false

    /** Set by a disarm that hands focus back to the button; the component clears it once it has. */
    internal var refocus: Boolean by mutableStateOf(false)

    fun arm() {
        guardOpen = false
        refocus = false
        armed = true
        arms++
    }

    /**
     * Cancels the split. With [restoreFocus], a pair that held focus hands it back to the button,
     * so the next D-pad press moves from a visible ring, whoever disarms it (B, Stay, a companion
     * deck's back). A touch outside the pair passes false: a touch does not move focus.
     */
    fun disarm(restoreFocus: Boolean = true) {
        if (armed && restoreFocus && pairHasFocus) refocus = true
        armed = false
        guardOpen = false
    }
}

@Composable
fun rememberNovaSplitConfirmState(): NovaSplitConfirmState = remember { NovaSplitConfirmState() }

/** The slot a split confirm stands in: a button, a full row, or a deck tile. */
enum class NovaSplitShape { Button, Row, Tile }

/**
 * A destructive action that confirms in its own slot.
 *
 * A (on release) or a tap arms it: the button splits into Stay (neutral, focused) and
 * [confirmLabel] (destructive fill, with [icon]) over 160ms, with [consequence] announced
 * underneath. B, focus leaving both halves, a touch outside the pair, or the page changing
 * cancels. The destructive half ignores activation for 400ms after arming, so a single A, a held
 * A, mashed A presses or a double tap never confirm; A, Right, A does.
 */
@Composable
fun NovaSplitConfirm(
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    stayLabel: String = stringResource(R.string.nova_panel_stay),
    consequence: String? = null,
    @DrawableRes icon: Int? = null,
    shape: NovaSplitShape = NovaSplitShape.Button,
    enabled: Boolean = true,
    state: NovaSplitConfirmState = rememberNovaSplitConfirmState(),
) {
    val confirm by rememberUpdatedState(onConfirm)
    val isTop = LocalNovaPageIsTop.current
    val isTopNow by rememberUpdatedState(isTop)
    val root = LocalView.current.rootView
    var pairHadFocus by remember(state) { mutableStateOf(false) }
    var pairFocused by remember(state) { mutableStateOf(false) }
    val pairBounds = remember(state) { BoundsHolder() }
    // The button's own width, which an armed Button shape keeps when it can.
    var slotWidth by remember(state) { mutableIntStateOf(0) }

    NovaBackHandler(active = state.armed) { state.disarm() }
    LaunchedEffect(isTop) { if (!isTop) state.disarm(restoreFocus = false) }
    DisposableEffect(state) { onDispose { state.disarm(restoreFocus = false) } }
    LaunchedEffect(state.armed, state.arms, state.refocus) {
        if (state.armed) {
            pairHadFocus = false
            withFrameNanos { }
            state.stayRequester.requestFocus()
            delay(NovaPanelMetrics.SplitGuardMillis)
            state.guardOpen = true
        } else if (state.refocus) {
            withFrameNanos { }
            if (isTopNow) state.buttonRequester.requestFocus()
            state.refocus = false
        }
    }
    // Read at recomposition, so focus moving from one half to the other never reads as leaving.
    LaunchedEffect(pairFocused) {
        if (pairFocused) {
            pairHadFocus = true
        } else if (pairHadFocus && state.armed) {
            state.disarm(restoreFocus = false)
        }
    }
    if (state.armed) {
        DisposableEffect(root, state) {
            val registration = NovaSplitConfirmRegistry.register(root, { pairBounds.bounds }) {
                state.disarm(restoreFocus = false)
            }
            onDispose { registration.unregister() }
        }
    }

    val minHeight = when (shape) {
        NovaSplitShape.Button -> NovaPanelMetrics.ButtonMinHeight
        NovaSplitShape.Row -> NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current)
        NovaSplitShape.Tile -> NovaPanelMetrics.TileMinHeight
    }
    val motion = tween<Float>(NovaPanelMetrics.SplitMillis)
    Column(modifier = modifier) {
        AnimatedContent(
            targetState = state.armed,
            transitionSpec = {
                (fadeIn(motion) togetherWith fadeOut(motion)) using
                    SizeTransform(clip = false) { _, _ -> tween(NovaPanelMetrics.SplitMillis) }
            },
            label = "NovaSplitConfirm",
        ) { armed ->
            if (!armed) {
                SplitHalf(
                    text = label,
                    icon = icon,
                    destructive = true,
                    filled = false,
                    enabled = enabled,
                    minHeight = minHeight,
                    // At rest a row or a tile has the row corner of the rows and tiles around it;
                    // only a button, and the armed halves, take the button corner.
                    rowCorner = shape != NovaSplitShape.Button,
                    modifier = Modifier
                        .then(
                            if (shape == NovaSplitShape.Button) {
                                Modifier.onSizeChanged { slotWidth = it.width }
                            } else {
                                Modifier.fillMaxWidth()
                            },
                        )
                        .focusRequester(state.buttonRequester),
                    onClick = { state.arm() },
                )
            } else {
                Row(
                    modifier = Modifier
                        // A button splits in its own slot and widens only as far as two 96dp
                        // halves need; a row or a tile splits across its whole width.
                        .then(if (shape == NovaSplitShape.Button) Modifier.splitPairWidth(slotWidth) else Modifier.fillMaxWidth())
                        .onGloballyPositioned { pairBounds.bounds = it.boundsInWindow() }
                        .onFocusChanged {
                            pairFocused = it.hasFocus
                            state.pairHasFocus = it.hasFocus
                        },
                    horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SplitGap),
                ) {
                    SplitHalf(
                        text = stayLabel,
                        icon = null,
                        destructive = false,
                        filled = false,
                        enabled = true,
                        minHeight = minHeight,
                        rowCorner = false,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(state.stayRequester),
                        onClick = { state.disarm() },
                    )
                    SplitHalf(
                        text = confirmLabel,
                        icon = icon,
                        destructive = true,
                        filled = true,
                        enabled = true,
                        minHeight = minHeight,
                        rowCorner = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (state.guardOpen) {
                                state.disarm()
                                confirm()
                            }
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = state.armed && consequence != null,
            enter = fadeIn(motion) + expandVertically(),
            exit = fadeOut(motion) + shrinkVertically(),
        ) {
            Text(
                text = consequence.orEmpty(),
                style = novaPanelType.caption,
                color = LocalNovaComposeColors.current.textSecondary,
                modifier = Modifier
                    .padding(top = NovaPanelMetrics.SpaceSm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SplitHalf(
    text: String,
    @DrawableRes icon: Int?,
    destructive: Boolean,
    filled: Boolean,
    enabled: Boolean,
    minHeight: Dp,
    rowCorner: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    NovaActionSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        primary = filled,
        destructive = destructive,
        contentDescription = text,
        minHeight = minHeight,
        cornerRadius = if (rowCorner) NovaRadius.row else NovaRadius.hero,
        contentPadding = PaddingValues(
            horizontal = NovaPanelMetrics.SpaceMd,
            vertical = NovaPanelMetrics.SpaceSm,
        ),
    ) { contentColor, _ ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm, Alignment.CenterHorizontally),
        ) {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(NovaPanelMetrics.IconSize),
                )
            }
            Text(text = text, style = novaPanelType.value, color = contentColor, textAlign = TextAlign.Center)
        }
    }
}

private class BoundsHolder {
    var bounds: Rect? = null
}

/**
 * The armed pair's width for the Button shape: the button's own [slotPx], or two 96dp halves and
 * their gap when that is wider, and never wider than the slot allows, where it takes the row.
 */
private fun Modifier.splitPairWidth(slotPx: Int): Modifier = layout { measurable, constraints ->
    val minimum = (NovaPanelMetrics.SplitHalfMinWidth * 2 + NovaPanelMetrics.SplitGap).roundToPx()
    val wanted = maxOf(slotPx, minimum)
    val width = if (constraints.hasBoundedWidth) wanted.coerceAtMost(constraints.maxWidth) else wanted
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Armed split confirms, so a touch anywhere outside an armed pair cancels it. NovaActivity and
 * NovaPanelWindow feed it every touch of their windows before dispatching it; any other window
 * that hosts a split, such as the companion deck's, calls [install].
 */
object NovaSplitConfirmRegistry {
    private class Armed(root: View, val bounds: () -> Rect?, val cancel: () -> Unit) {
        val root = WeakReference(root)
    }

    /** A registration to remove when the pair disarms. */
    fun interface Registration {
        fun unregister()
    }

    private val armed = mutableListOf<Armed>()

    internal fun register(root: View, bounds: () -> Rect?, cancel: () -> Unit): Registration {
        val entry = Armed(root, bounds, cancel)
        armed += entry
        return Registration { armed -= entry }
    }

    /** Cancels every pair armed in [root]'s window that a new touch lands outside of. */
    fun onTouch(root: View, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        armed.removeAll { it.root.get() == null }
        armed.filter { entry ->
            entry.root.get() === root && entry.bounds()?.contains(Offset(event.x, event.y)) != true
        }.forEach { it.cancel() }
    }

    /**
     * Feeds every touch of [window] to [onTouch] before the window dispatches it, for a window
     * whose owner does not, such as a plain Activity. Installing it twice in a row does nothing.
     */
    fun install(window: Window) {
        val callback = window.callback ?: return
        if (callback is TouchFeed) return
        window.callback = TouchFeed(callback, window)
    }

    private class TouchFeed(
        private val base: Window.Callback,
        private val window: Window,
    ) : Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            onTouch(window.decorView, event)
            return base.dispatchTouchEvent(event)
        }
    }
}

/**
 * For View layouts: this ComposeView replaces the View button in place with a split confirm. A
 * recycled view, such as a list row, passes the item it now shows as [itemKey], so a split armed
 * for one item never carries over to the next.
 */
fun ComposeView.setNovaSplitConfirm(
    label: String,
    confirmLabel: String,
    consequence: String? = null,
    @DrawableRes icon: Int? = null,
    itemKey: Any? = null,
    onConfirm: () -> Unit,
) {
    setNovaContent {
        key(itemKey) {
            NovaSplitConfirm(
                label = label,
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                consequence = consequence,
                icon = icon,
            )
        }
    }
}
