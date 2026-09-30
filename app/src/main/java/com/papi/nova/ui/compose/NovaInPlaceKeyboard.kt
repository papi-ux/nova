package com.papi.nova.ui.compose

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Text fields inside [content] are typed into where they stand.
 *
 * In landscape a keyboard may swap the whole screen for its own "extract" view: a blank page
 * with a copy of the field and a Done button. On a handheld that is every text field, and it
 * hides the screen the typing is for; the Artwork Studio's search field lost the results it
 * narrows. The two flags ask the keyboard to stay a keyboard.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NovaInPlaceKeyboard(
    /**
     * The field is a number that may go below zero. Compose's number keyboard is unsigned, and
     * most keyboards then leave out the minus key, so this asks for a signed one.
     */
    signedNumber: Boolean = false,
    content: @Composable () -> Unit,
) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(
                PlatformTextInputMethodRequest { outAttributes ->
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions = novaInPlaceImeOptions(outAttributes.imeOptions)
                        outAttributes.inputType = novaInPlaceInputType(outAttributes.inputType, signedNumber)
                    }
                },
            )
        },
        content = content,
    )
}

/** Whatever the field asked for, plus: no extract view, no full-screen mode. */
internal fun novaInPlaceImeOptions(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

/** Whatever the field asked for, plus the minus key when a number field may go below zero. */
internal fun novaInPlaceInputType(inputType: Int, signedNumber: Boolean): Int =
    if (signedNumber && (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) {
        inputType or InputType.TYPE_NUMBER_FLAG_SIGNED
    } else {
        inputType
    }
