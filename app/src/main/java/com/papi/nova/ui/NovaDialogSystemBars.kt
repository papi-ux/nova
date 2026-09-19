package com.papi.nova.ui

import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A Compose Dialog or ModalBottomSheet is a window of its own, so on a screen that hid
 * the bars it brought the navigation bar back. Called first in the dialog's content, it
 * gives that window the Hide System Bars setting too.
 */
@Composable
fun NovaDialogSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.dialogWindow()?.let { NovaSystemBars.applyToDialog(view.context, it) }
        onDispose { }
    }
}

private fun View.dialogWindow(): Window? {
    var candidate = parent
    while (candidate != null) {
        if (candidate is DialogWindowProvider) return candidate.window
        candidate = candidate.parent
    }
    return null
}
