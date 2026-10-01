package com.papi.nova.ui.compose

import com.papi.nova.ui.novaAvoidCameraCutout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.panel.NovaBackHandler
import com.papi.nova.ui.panel.NovaKeys
import com.papi.nova.ui.panel.NovaPressLatch

/**
 * A text field a d-pad can get back out of.
 *
 * A plain BasicTextField is a trap on a handheld. Once it holds focus it takes the direction
 * keys as text navigation, so pressing down does not move to the next control and there is no
 * way off the field without a touchscreen. Nova had two search fields and only the library's
 * handled this; the settings one was the plain kind, and the settings screen is the one people
 * reach with a controller in their hands.
 *
 * The pattern, which this now owns for both:
 *
 *  - the field is `readOnly` until it is explicitly opened, so d-pad focus passes over it;
 *  - Enter, NumPadEnter, the centre button or A opens it and raises the keyboard, on release and
 *    only for a press that began here, so the release of a press that did something else never
 *    opens it (R8);
 *  - any direction key closes it and moves focus that way, so you always get out;
 *  - while it is open, B hides the keyboard and does nothing else (R4): its back handler is
 *    composed only while the field is open, so it is the newest in the back chain and runs
 *    before the screen's own, which would otherwise leave Settings with the keyboard still up;
 *  - a tap opens it too, because a touchscreen is still a touchscreen;
 *  - losing focus by any other route closes it and drops the keyboard, and so does the keyboard
 *    hiding itself, so the next B is the screen's again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovaSearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    heightDp: Int = 44,
    shape: Shape = RoundedCornerShape(NovaRadius.row),
    onSearch: () -> Unit = {},
    decorationBox: @Composable (@Composable () -> Unit) -> Unit
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val latch = remember { NovaPressLatch() }
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // Whether the keyboard has been seen up since the field opened, so its show animation, which
    // starts hidden, never reads as the keyboard going away.
    var sawKeyboard by remember { mutableStateOf(false) }
    val keyboardUp = WindowInsets.isImeVisible

    fun beginEditing() {
        sawKeyboard = false
        editing = true
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun leaveEditing(direction: FocusDirection?): Boolean {
        editing = false
        keyboardController?.hide()
        direction?.let { focusManager.moveFocus(it) }
        return true
    }

    LaunchedEffect(keyboardUp, editing) {
        when {
            !editing -> Unit
            keyboardUp -> sawKeyboard = true
            // The keyboard hid itself (its own key, a remote's Back): the field closes with it.
            sawKeyboard -> editing = false
        }
    }
    // Where no keyboard shows (a hardware keyboard), B still closes the open field first.
    if (editing && (keyboardUp || !sawKeyboard)) {
        NovaBackHandler(active = true) { leaveEditing(null) }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                editing = false
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onSearch()
            }
        ),
        modifier = modifier
            .novaAvoidCameraCutout(touchTarget = true)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when {
                    // Opens on the release of a press that began here, never on the down.
                    NovaKeys.isActivation(native.keyCode) && !editing -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> if (native.repeatCount == 0) latch.press(native.keyCode)
                            KeyEventType.KeyUp -> if (latch.release(native.keyCode) && !native.isCanceled) beginEditing()
                        }
                        true
                    }
                    event.type != KeyEventType.KeyDown -> false
                    else -> when (event.key) {
                        Key.DirectionDown -> leaveEditing(FocusDirection.Down)
                        Key.DirectionUp -> leaveEditing(FocusDirection.Up)
                        Key.DirectionLeft -> leaveEditing(FocusDirection.Left)
                        Key.DirectionRight -> leaveEditing(FocusDirection.Right)
                        else -> false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { beginEditing() })
            }
            .height(heightDp.dp)
            .novaFocusMotion(focused = focused)
            .clip(shape)
            .background(surfaces.control)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) surfaces.focusRing else surfaces.tileBorder,
                shape = shape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) {
                    latch.clear()
                    if (editing) {
                        editing = false
                        keyboardController?.hide()
                    }
                }
            }
            .semantics { this.contentDescription = contentDescription },
        decorationBox = decorationBox
    )
}
