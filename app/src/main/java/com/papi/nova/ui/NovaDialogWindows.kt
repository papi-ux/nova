package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A sheet or dialog is a window of its own, so what a Nova screen does for its window
 * stopped at the dialog's edge: the bars came back over a screen that had hidden them,
 * and after a touch the Retroid D-pad stayed dead inside the dialog. Adopting the
 * dialog's window gives it both. A dialog over the stream hides the bars as the stream
 * does, and leaves the D-pad alone: in the stream it is the host's controller input.
 */
object NovaDialogWindows {
    fun adopt(context: Context, window: Window) {
        val host = context.findActivity() ?: return
        if (NovaSystemBars.isStream(host)) {
            NovaSystemBars.applyToStreamDialog(window)
            return
        }
        if (!NovaSystemBars.isManaged(host)) return
        NovaSystemBars.applyToDialog(context, window)
        NovaControllerTouchMode.install(window)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/** For a Compose Dialog, AlertDialog or ModalBottomSheet: called first in its content. */
@Composable
fun NovaDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.dialogWindow()?.let { NovaDialogWindows.adopt(view.context, it) }
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
