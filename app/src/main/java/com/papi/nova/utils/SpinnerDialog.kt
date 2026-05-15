package com.papi.nova.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.LayoutInflater
import android.widget.TextView
import com.papi.nova.R

class SpinnerDialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val finish: Boolean
) : Runnable, DialogInterface.OnCancelListener {
    private var dialog: AlertDialog? = null
    private var messageView: TextView? = null

    fun dismiss() {
        activity.runOnUiThread(this)
    }

    fun setMessage(message: String) {
        activity.runOnUiThread {
            messageView?.text = message
        }
    }

    override fun run() {
        if (activity.isFinishing) {
            return
        }

        val activeDialog = dialog
        if (activeDialog == null) {
            val content = LayoutInflater.from(activity).inflate(R.layout.nova_spinner_dialog, null)
            messageView = content.findViewById<TextView>(R.id.spinner_message).apply {
                text = message
            }

            val builder = AlertDialog.Builder(activity)
            builder.setTitle(title)
            builder.setView(content)
            builder.setOnCancelListener(this)
            builder.setCancelable(finish)

            val createdDialog = builder.create()
            dialog = createdDialog

            synchronized(rundownDialogs) {
                rundownDialogs.add(this)
                createdDialog.show()
            }
        } else {
            synchronized(rundownDialogs) {
                if (rundownDialogs.remove(this) && activeDialog.isShowing) {
                    activeDialog.dismiss()
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        synchronized(rundownDialogs) {
            rundownDialogs.remove(this)
        }
        activity.finish()
    }

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        @JvmStatic
        fun closeDialogs(activity: Activity) {
            synchronized(rundownDialogs) {
                val iterator = rundownDialogs.iterator()
                while (iterator.hasNext()) {
                    val dialog = iterator.next()
                    if (dialog.activity == activity) {
                        iterator.remove()
                        val activeDialog = dialog.dialog
                        if (activeDialog != null && activeDialog.isShowing) {
                            activeDialog.dismiss()
                        }
                    }
                }
            }
        }
    }
}
