package com.papi.nova.ui

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.papi.nova.R

/**
 * Nova-themed Snackbar helper.
 * Shows messages with consistent Nova styling.
 */
object NovaSnackbar {

    @JvmOverloads
    fun show(activity: Activity, message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        val rootView = activity.findViewById<View>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(rootView, message, duration)
        snackbar.setBackgroundTint(activity.getColor(R.color.nova_bg_elevated))
        snackbar.setTextColor(activity.getColor(R.color.nova_text_primary))
        snackbar.setActionTextColor(activity.getColor(R.color.nova_accent))
        snackbar.show()
    }

    fun showError(activity: Activity, message: String) {
        val rootView = activity.findViewById<View>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(activity.getColor(R.color.nova_bg_elevated))
        snackbar.setTextColor(activity.getColor(R.color.nova_error))
        snackbar.setActionTextColor(activity.getColor(R.color.nova_accent))
        snackbar.show()
    }

    fun showSuccess(activity: Activity, message: String) {
        val rootView = activity.findViewById<View>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
        snackbar.setBackgroundTint(activity.getColor(R.color.nova_bg_elevated))
        snackbar.setTextColor(activity.getColor(R.color.nova_success))
        snackbar.setActionTextColor(activity.getColor(R.color.nova_accent))
        snackbar.show()
    }
}
