package com.papi.nova.ui

import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/** Controller handling belongs to the modal window, never to the streamed game's input. */
internal object NovaStreamSheetFocus {
    fun onShow(dialog: Dialog, initialAction: View) {
        dialog.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            NovaControllerTouchMode.install(it)
        }
        // A prior touch must not make the first D-pad press merely reveal focus.
        initialAction.isFocusableInTouchMode = true
        if (!initialAction.requestFocus()) {
            initialAction.post {
                if (dialog.isShowing && dialog.currentFocus?.isClickable != true) initialAction.requestFocus()
            }
        }

        var pressedAction: View? = null
        var pressedConfirmKey: Int? = null
        var pressedCancelKey: Int? = null
        dialog.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        pressedAction = dialog.currentFocus?.takeIf { it.isEnabled && it.isClickable }
                        pressedConfirmKey = keyCode
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        val action = pressedAction
                        val matches = pressedConfirmKey == keyCode
                        pressedAction = null
                        pressedConfirmKey = null
                        if (dialog.isShowing && matches && !event.isCanceled && action === dialog.currentFocus) {
                            action?.performClick()
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        pressedCancelKey = keyCode
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        val matches = pressedCancelKey == keyCode
                        pressedCancelKey = null
                        if (matches && !event.isCanceled) dialog.cancel()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
