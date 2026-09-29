package com.papi.nova.ui.panel

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.NovaInPlaceKeyboard
import com.papi.nova.ui.compose.NovaRadius

/** Runs before a field raises the keyboard; a companion display hands its window the IME here. */
internal val LocalNovaPrepareKeyboard = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A text field a pad can pass over, open on purpose and always leave.
 *
 * The field is read-only until it is opened: A (on release) or a tap opens it and raises the
 * keyboard, and D-pad focus alone never does. Any direction key closes it and moves focus that
 * way. While it is open, B hides only the keyboard, first in the back chain. The field closes
 * with the keyboard however the keyboard goes: a remote's or the RP6's Back key, the back gesture
 * and the keyboard's own hide key reach the keyboard first and never the window. The keyboard's
 * action key runs [onImeAction]. With [openOnStart], used when a page was opened by touch, it
 * opens as soon as it is shown. [error] shows in the destructive colour under the field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    kind: NovaFieldKind = NovaFieldKind.Text,
    maxLength: Int? = null,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    openOnStart: Boolean = false,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val prepareKeyboard = LocalNovaPrepareKeyboard.current
    val imeActionRun by rememberUpdatedState(onImeAction)
    val requester = remember { FocusRequester() }
    val latch = remember { NovaPressLatch() }
    var editing by remember { mutableStateOf(false) }
    // Whether the keyboard has been seen up since the field opened, so its show animation, which
    // starts hidden, never reads as the keyboard going away.
    var sawKeyboard by remember { mutableStateOf(false) }
    val keyboardUp = WindowInsets.isImeVisible
    val shape = RoundedCornerShape(NovaRadius.row)

    fun open() {
        prepareKeyboard()
        sawKeyboard = false
        editing = true
        requester.requestFocus()
        keyboard?.show()
    }

    fun close(direction: FocusDirection? = null) {
        editing = false
        keyboard?.hide()
        direction?.let(focusManager::moveFocus)
    }

    LaunchedEffect(keyboardUp, editing) {
        when {
            !editing -> Unit
            keyboardUp -> sawKeyboard = true
            // The keyboard hid itself: the field closes with it, so the next B pops the page.
            sawKeyboard -> editing = false
        }
    }
    // Where no keyboard shows (a hardware keyboard), B still closes the open field first.
    NovaBackHandler(active = editing && (keyboardUp || !sawKeyboard)) { close() }
    LaunchedEffect(Unit) {
        if (openOnStart) {
            withFrameNanos { }
            open()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceXs)) {
        Text(text = label, style = type.caption, color = colors.textSecondary)
        // Compose has no signed number keyboard, so the field asks the keyboard for the minus key itself.
        NovaInPlaceKeyboard(signedNumber = kind == NovaFieldKind.SignedNumber) {
            BasicTextField(
                value = value,
                onValueChange = { next -> onValueChange(maxLength?.let(next::take) ?: next) },
                readOnly = !editing,
                singleLine = true,
                textStyle = type.value.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (kind == NovaFieldKind.Password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = kind.keyboardType,
                    imeAction = imeAction,
                    showKeyboardOnFocus = false,
                ),
                keyboardActions = KeyboardActions {
                    close()
                    imeActionRun()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
                    .clip(shape)
                    .novaFocusRing(
                        shape = shape,
                        restFill = surfaces.control,
                        restBorder = if (error != null) colors.destructive else surfaces.tileBorder,
                        restBorderWidth = NovaPanelMetrics.Hairline,
                    )
                    .novaFocusHint(NovaFocusHint.Type)
                    .focusRequester(requester)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        when {
                            NovaKeys.isActivation(native.keyCode) && !editing -> {
                                when (event.type) {
                                    KeyEventType.KeyDown -> if (native.repeatCount == 0) latch.press(native.keyCode)
                                    KeyEventType.KeyUp -> if (latch.release(native.keyCode) && !native.isCanceled) open()
                                }
                                true
                            }
                            event.type != KeyEventType.KeyDown -> false
                            else -> when (event.key) {
                                Key.DirectionUp -> true.also { close(FocusDirection.Up) }
                                Key.DirectionDown -> true.also { close(FocusDirection.Down) }
                                Key.DirectionLeft -> true.also { close(FocusDirection.Left) }
                                Key.DirectionRight -> true.also { close(FocusDirection.Right) }
                                else -> false
                            }
                        }
                    }
                    // Seen before the field's own gestures, which would otherwise take the tap.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(PointerEventPass.Initial) != null && !editing) open()
                        }
                    }
                    .onFocusChanged {
                        if (!it.isFocused) {
                            latch.clear()
                            if (editing) close()
                        }
                    }
                    .semantics {
                        contentDescription = label
                        error?.let { error(it) }
                    },
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
                        contentAlignment = Alignment.CenterStart,
                    ) { inner() }
                },
            )
        }
        error?.let { Text(text = it, style = type.caption, color = colors.destructive) }
    }
}

internal val NovaFieldKind.keyboardType: KeyboardType
    get() = when (this) {
        NovaFieldKind.Text -> KeyboardType.Text
        NovaFieldKind.Number, NovaFieldKind.SignedNumber -> KeyboardType.Number
        // Most keyboards leave out a key the field does not ask for: 12.5 could not be typed.
        NovaFieldKind.Decimal -> KeyboardType.Decimal
        NovaFieldKind.Password -> KeyboardType.Password
        NovaFieldKind.Url -> KeyboardType.Uri
    }
