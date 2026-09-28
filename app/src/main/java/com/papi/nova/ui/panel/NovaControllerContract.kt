package com.papi.nova.ui.panel

import android.view.KeyEvent
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import com.papi.nova.ui.NovaControllerTouchMode
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * The controller contract, implemented once for every Nova surface.
 *
 *  - A, Center and Enter act on release, and only on the element that received the press
 *    ([NovaKeyGate] at the window, [novaActivatable] at the element).
 *  - B, Back and Escape go back one level, on release, through the OnBackPressedDispatcher.
 *  - Back handlers run newest first, so their order is composition order. Each is enabled only
 *    while its component is active and its page is on top ([NovaBackHandler]). The order is:
 *    the keyboard, an armed split, a state page, a pushed page, the panel's root, the screen.
 *  - A hat or stick press takes a window out of touch mode ([prepareControllerWindow]).
 */
object NovaKeys {
    /** Keys that activate the focused element. BUTTON_A reaches an element only where no gate runs. */
    fun isActivation(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }

    /** Keys the gate turns into Back on release. KEYCODE_BACK is not one: the platform tracks it. */
    fun isGatedBack(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE
}

/** Remembers which key started a press, so only that key's release can finish it. */
class NovaPressLatch {
    private var pressed: Int = NONE

    /** Records a first down of [keyCode]. */
    fun press(keyCode: Int) {
        pressed = keyCode
    }

    /** Whether this release of [keyCode] finishes a recorded press. Clears the record either way. */
    fun release(keyCode: Int): Boolean {
        val matched = pressed == keyCode
        if (matched) pressed = NONE
        return matched
    }

    fun clear() {
        pressed = NONE
    }

    private companion object {
        const val NONE = KeyEvent.KEYCODE_UNKNOWN
    }
}

/** What [NovaKeyGate] does with one key event. */
enum class NovaKeyDecision {
    /** Deliver it unchanged. */
    Pass,

    /** Consume it. */
    Swallow,

    /** Consume it and go back one level. */
    Back,

    /** Deliver it as DPAD_CENTER, keeping everything else about it. */
    AsCenter,
}

/**
 * Makes A and B mean the same thing on every device key map, at a window's key dispatch.
 *
 * A becomes DPAD_CENTER, which Compose and Views both activate on. Because the gate consumes the
 * original A, the platform never generates its own fallback DPAD_CENTER for it, which is the double
 * event some screens work around today. B and Escape become Back on an uncancelled release that
 * matches a press seen here, so the release of a press that opened a surface does nothing in it.
 * KEYCODE_BACK passes: Dialog and Activity already act only on a tracked, uncancelled release.
 */
class NovaKeyGate {
    private val confirm = NovaPressLatch()
    private val back = NovaPressLatch()

    /** The pure decision for one event. It updates the gate's press records. */
    fun decide(keyCode: Int, action: Int, repeatCount: Int, canceled: Boolean): NovaKeyDecision = when {
        keyCode == KeyEvent.KEYCODE_BUTTON_A -> when (action) {
            KeyEvent.ACTION_DOWN -> if (repeatCount == 0) {
                confirm.press(keyCode)
                NovaKeyDecision.AsCenter
            } else {
                NovaKeyDecision.Swallow
            }
            // A cancelled release still goes through, marked cancelled, so the element sees it end.
            KeyEvent.ACTION_UP -> if (confirm.release(keyCode)) NovaKeyDecision.AsCenter else NovaKeyDecision.Swallow
            else -> NovaKeyDecision.Swallow
        }
        NovaKeys.isGatedBack(keyCode) -> when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0) back.press(keyCode)
                NovaKeyDecision.Swallow
            }
            KeyEvent.ACTION_UP ->
                if (back.release(keyCode) && !canceled) NovaKeyDecision.Back else NovaKeyDecision.Swallow
            else -> NovaKeyDecision.Swallow
        }
        else -> NovaKeyDecision.Pass
    }

    /**
     * Applies the gate to [event]: [deliver] sends an event on to the window's normal dispatch,
     * and [onBack] is the dispatcher's back. Returns whether the event was consumed.
     */
    fun dispatch(event: KeyEvent, onBack: () -> Unit, deliver: (KeyEvent) -> Boolean): Boolean =
        when (decide(event.keyCode, event.action, event.repeatCount, event.isCanceled)) {
            NovaKeyDecision.Pass -> deliver(event)
            NovaKeyDecision.Swallow -> true
            NovaKeyDecision.Back -> {
                onBack()
                true
            }
            NovaKeyDecision.AsCenter -> {
                deliver(event.asCenter())
                true
            }
        }

    /** Forgets presses in progress, for a window that loses focus in the middle of one. */
    fun reset() {
        confirm.clear()
        back.clear()
    }
}

/** [this] as DPAD_CENTER, with the same times, repeat, device, scan code, source, flags and meta state. */
internal fun KeyEvent.asCenter(): KeyEvent = KeyEvent(
    downTime,
    eventTime,
    action,
    KeyEvent.KEYCODE_DPAD_CENTER,
    repeatCount,
    metaState,
    deviceId,
    scanCode,
    flags,
    source,
)

/**
 * Activates on release, and only on the element that received the press.
 *
 * Center, Enter, NumPadEnter and A are handled before the element's own key handling: a down with
 * repeat 0 records the key, every down is consumed, and an uncancelled release of the recorded key
 * runs [onActivate]. Losing focus clears the record, so a press that started elsewhere never lands
 * here. While disabled it still consumes those keys and does nothing, as a disabled row must.
 *
 * Put it directly before the focus target (`focusable()`), where Compose delivers the focused
 * element's keys; [novaClickable] does. Keys reach every ancestor of the focused element first,
 * so on its own it would also answer for a focused element inside it; [novaClickable] does not,
 * and an element with focusable content uses that.
 */
fun Modifier.novaActivatable(enabled: Boolean = true, onActivate: () -> Unit): Modifier =
    this then NovaActivatableElement(enabled, onActivate, yieldsToContent = false)

/**
 * The clickable every foundation component uses: [novaActivatable] for keys, `clickable` for
 * touch, TalkBack and the press interaction, and `focusable()` so the element can hold focus in
 * touch mode. Screen code a pad can reach uses it instead of `clickable`. When focus is on an
 * element inside this one, such as a button in a card, A belongs to that element.
 *
 * [focusableWhenDisabled] keeps a disabled element a focus stop, so a row can show why it is
 * disabled and swallow A.
 */
fun Modifier.novaClickable(
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    focusableWhenDisabled: Boolean = false,
    onClick: () -> Unit,
): Modifier = this
    .then(NovaActivatableElement(enabled, onClick, yieldsToContent = true))
    .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
    .focusable(enabled = enabled || focusableWhenDisabled, interactionSource = interactionSource)
    .then(NovaContentFocusElement)

private data class NovaActivatableElement(
    val enabled: Boolean,
    val onActivate: () -> Unit,
    val yieldsToContent: Boolean,
) : ModifierNodeElement<NovaActivatableNode>() {
    override fun create() = NovaActivatableNode(enabled, onActivate, yieldsToContent)

    override fun update(node: NovaActivatableNode) {
        node.enabled = enabled
        node.onActivate = onActivate
        node.yieldsToContent = yieldsToContent
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "novaActivatable"
        properties["enabled"] = enabled
    }
}

private class NovaActivatableNode(
    var enabled: Boolean,
    var onActivate: () -> Unit,
    var yieldsToContent: Boolean,
) : Modifier.Node(), KeyInputModifierNode, FocusEventModifierNode {
    private val latch = NovaPressLatch()

    override fun onPreKeyEvent(event: ComposeKeyEvent): Boolean {
        val native = event.nativeKeyEvent
        if (!NovaKeys.isActivation(native.keyCode)) return false
        // A focused element inside this one gets the press on its own way down.
        if (yieldsToContent && contentHasFocus()) return false
        when (event.type) {
            KeyEventType.KeyDown -> if (native.repeatCount == 0) latch.press(native.keyCode)
            KeyEventType.KeyUp -> if (latch.release(native.keyCode) && !native.isCanceled && enabled) onActivate()
        }
        return true
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean = false

    override fun onFocusEvent(focusState: FocusState) {
        if (!focusState.hasFocus) latch.clear()
    }

    /** Whether focus is below this element's own focus targets, as its [NovaContentFocusNode] sees. */
    private fun contentHasFocus(): Boolean {
        var inside = false
        // The first one found is this element's own: it closes this modifier chain.
        traverseDescendants(NovaActivationTraverseKey) { node ->
            inside = (node as? NovaContentFocusNode)?.contentHasFocus == true
            TraversableNode.Companion.TraverseDescendantsAction.CancelTraversal
        }
        return inside
    }
}

private object NovaActivationTraverseKey

/** Placed after an element's own focus targets, it sees whether focus is on something inside. */
private class NovaContentFocusNode : Modifier.Node(), FocusEventModifierNode, TraversableNode {
    override val traverseKey: Any get() = NovaActivationTraverseKey
    var contentHasFocus: Boolean = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        contentHasFocus = focusState.hasFocus
    }
}

private data object NovaContentFocusElement : ModifierNodeElement<NovaContentFocusNode>() {
    override fun create() = NovaContentFocusNode()

    override fun update(node: NovaContentFocusNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "novaContentFocus"
    }
}

/**
 * A back handler that takes part in the back chain only while [active] and while its page is on
 * top, so a page leaving through its exit animation never answers B. The page is checked again
 * when B arrives, because the enabled flag only follows the next frame. It does nothing where no
 * OnBackPressedDispatcher owner exists, such as a view on the companion deck, whose back goes
 * through its own controller.
 */
@Composable
fun NovaBackHandler(active: Boolean, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current == null) return
    val mayAct = LocalNovaPageMayAct.current
    BackHandler(enabled = active && LocalNovaPageIsTop.current) {
        if (mayAct()) onBack()
    }
}

/**
 * What a Nova screen does for its own window, done for a panel window: [content] can hold focus
 * in touch mode, and a hat or stick press takes the window out of touch mode.
 */
internal fun prepareControllerWindow(window: Window, content: View) {
    content.isFocusable = true
    content.isFocusableInTouchMode = true
    NovaControllerTouchMode.install(window)
}
