package com.papi.nova.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.Button
import com.papi.nova.R

class Dialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val runOnDismiss: Runnable
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
        createdAlert.setOnShowListener(object : DialogInterface.OnShowListener {
            override fun onShow(dialog: DialogInterface) {
                val button: Button = createdAlert.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isFocusable = true
                button.isFocusableInTouchMode = true
                button.requestFocus()
            }
        })

        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            createdAlert.show()
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
            activity.runOnUiThread(
                Dialog(
                    activity,
                    title,
                    message,
                    Runnable {
                        if (endAfterDismiss) {
                            activity.finish()
                        }
                    }
                )
            )
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, runOnDismiss: Runnable) {
            activity.runOnUiThread(Dialog(activity, title, message, runOnDismiss))
        }
    }
}
