package com.papi.nova.utils

import android.app.Activity
import android.app.AlertDialog
import com.papi.nova.R
import com.papi.nova.ui.NovaSheetChrome

class Dialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val runOnDismiss: Runnable,
    private val actionText: CharSequence? = null,
    private val action: Runnable? = null,
) : Runnable {
    private var alert: AlertDialog? = null

    override fun run() {
        if (activity.isFinishing) {
            return
        }

        val createdAlert = AlertDialog.Builder(activity).create()
        alert = createdAlert

        createdAlert.setTitle(title)
        createdAlert.setMessage(message)
        createdAlert.setCancelable(false)
        createdAlert.setCanceledOnTouchOutside(false)

        createdAlert.setButton(
            AlertDialog.BUTTON_POSITIVE,
            activity.resources.getText(android.R.string.ok)
        ) { _, _ ->
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                createdAlert.dismiss()
            }
            runOnDismiss.run()
        }
        if (actionText != null && action != null) {
            // Deliberately skips runOnDismiss: with endAfterDismiss the dismiss path calls
            // finish(), which would race whatever the action is about to start.
            createdAlert.setButton(
                AlertDialog.BUTTON_NEGATIVE,
                actionText
            ) { _, _ ->
                synchronized(rundownDialogs) {
                    rundownDialogs.remove(this)
                    createdAlert.dismiss()
                }
                action.run()
            }
        }
        createdAlert.setButton(
            AlertDialog.BUTTON_NEUTRAL,
            activity.resources.getText(R.string.help)
        ) { _, _ ->
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                createdAlert.dismiss()
            }

            runOnDismiss.run()
            HelpLauncher.launchTroubleshooting(activity)
        }
        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            createdAlert.show()
            NovaSheetChrome.applyMenuOpacityToLegacyAlert(createdAlert)
            createdAlert.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }
        }
    }

    companion object {
        private val rundownDialogs = ArrayList<Dialog>()

        @JvmStatic
        fun closeDialogs() {
            synchronized(rundownDialogs) {
                for (dialog in rundownDialogs) {
                    val alert = dialog.alert
                    if (alert != null && alert.isShowing) {
                        alert.dismiss()
                    }
                }
                rundownDialogs.clear()
            }
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, endAfterDismiss: Boolean) {
            displayDialog(activity, title, message, endAfterDismiss, null, null)
        }

        @JvmStatic
        fun displayDialog(
            activity: Activity,
            title: String,
            message: String,
            endAfterDismiss: Boolean,
            actionText: CharSequence?,
            action: Runnable?,
        ) {
            activity.runOnUiThread(
                Dialog(
                    activity,
                    title,
                    message,
                    Runnable {
                        if (endAfterDismiss) {
                            activity.finish()
                        }
                    },
                    actionText,
                    action,
                )
            )
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, runOnDismiss: Runnable) {
            activity.runOnUiThread(Dialog(activity, title, message, runOnDismiss))
        }
    }
}
