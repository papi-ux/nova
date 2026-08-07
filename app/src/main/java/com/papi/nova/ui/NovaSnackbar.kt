package com.papi.nova.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar
import com.papi.nova.R

/**
 * Nova-themed Snackbar helper.
 *
 * Keep transient status lightweight and theme-aware. These are not drawers, so
 * they must not show up as hardcoded purple cards layered over Nova glass sheets
 * or the stream setup overlay.
 */
object NovaSnackbar {
    private const val SurfaceAlpha = 0xDC
    private const val QuietSurfaceAlpha = 0xB8
    private var activeSnackbar: Snackbar? = null

    @JvmOverloads
    fun show(
        activity: Activity,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        anchor: View? = null
    ) {
        showStyled(
            activity = activity,
            message = message,
            duration = duration,
            textColor = NovaThemeManager.getTextPrimaryColor(activity),
            surfaceAlpha = SurfaceAlpha,
            anchor = anchor
        )
    }

    fun showQuiet(activity: Activity, message: String, anchor: View? = null) {
        showStyled(
            activity = activity,
            message = message,
            duration = Snackbar.LENGTH_SHORT,
            textColor = NovaThemeManager.getTextSecondaryColor(activity),
            surfaceAlpha = QuietSurfaceAlpha,
            anchor = anchor
        )
    }

    fun showError(activity: Activity, message: String, anchor: View? = null) {
        showStyled(
            activity = activity,
            message = message,
            duration = Snackbar.LENGTH_LONG,
            textColor = activity.getColor(R.color.nova_error),
            surfaceAlpha = SurfaceAlpha,
            anchor = anchor
        )
    }

    fun showSuccess(activity: Activity, message: String, anchor: View? = null) {
        showStyled(
            activity = activity,
            message = message,
            duration = Snackbar.LENGTH_SHORT,
            textColor = activity.getColor(R.color.nova_success),
            surfaceAlpha = SurfaceAlpha,
            anchor = anchor
        )
    }

    private fun showStyled(
        activity: Activity,
        message: String,
        duration: Int,
        textColor: Int,
        surfaceAlpha: Int,
        anchor: View? = null
    ) {
        // A Snackbar is drawn inside the window of the view it is given. The activity's
        // content view is the wrong window whenever a Dialog is up -- the in-stream Command
        // Center is a full-screen one -- and the snackbar would be hidden behind it.
        //
        // Callers inside a dialog pass their own view. A dismissed Dialog detaches its
        // content, so isAttachedToWindow answers "is that dialog still showing?" and the
        // same call site works whether it fires before or after the dialog closes.
        val rootView = anchor?.takeIf { it.isAttachedToWindow }
            ?: activity.findViewById<View>(android.R.id.content)
            ?: return
        activeSnackbar?.dismiss()
        val snackbar = Snackbar.make(rootView, message, duration)
        activeSnackbar = snackbar
        snackbar.setBackgroundTint(
            ColorUtils.setAlphaComponent(
                NovaThemeManager.getDialogBackgroundColor(activity),
                surfaceAlpha
            )
        )
        snackbar.setTextColor(textColor)
        snackbar.setActionTextColor(NovaThemeManager.getAccentColor(activity))
        snackbar.view.alpha = if (surfaceAlpha < SurfaceAlpha) 0.88f else 0.94f
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (activeSnackbar === transientBottomBar) {
                    activeSnackbar = null
                }
            }
        })
        snackbar.show()
    }
}
