package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window

/**
 * NovaPanelWindow is a window of its own, so what a Nova screen does for its window stopped
 * at the panel's edge: the bars came back over a screen that had hidden them, and after a
 * touch the Retroid D-pad stayed dead inside it. Adopting the panel's window gives it both.
 * A panel over the stream hides the bars as the stream does, and leaves the D-pad alone: in
 * the stream it is the host's controller input.
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
