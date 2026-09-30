package com.papi.nova.utils

import android.app.Activity
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaStateOwner
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A wait the player cannot skip past, shown as a full-screen Busy page on the activity's
 * [NovaSurfaces]. [displayDialog] returns this handle, which updates and dismisses the page from
 * any thread. New code uses `NovaSurfaces.busy` instead.
 */
class SpinnerDialog private constructor(
    private val activity: Activity,
    private val key: String,
    private val message: MutableStateFlow<String>,
) {
    /** Takes the page down; a page already on screen still stays its minimum time. Any thread. */
    fun dismiss() {
        NovaSurfaces.existing(activity)?.dismiss(key)
    }

    /** Replaces the page's message. Any thread. */
    fun setMessage(message: String) {
        this.message.value = message
    }

    companion object {
        private val serial = AtomicLong()

        /**
         * Shows [title] and [message] over the screen. With [finish], the page has a focused Cancel
         * that finishes the activity, and B does the same; without it the page cannot be left and
         * absorbs A and B until it is dismissed.
         */
        @JvmStatic
        fun displayDialog(activity: Activity, title: String, message: String, finish: Boolean): SpinnerDialog {
            val key = "nova-legacy-spinner-" + serial.incrementAndGet()
            val spinner = SpinnerDialog(activity, key, MutableStateFlow(message))
            if (activity.isFinishing) return spinner
            val cancel = if (finish) {
                NovaAction(activity.getString(R.string.nova_panel_cancel)) {
                    spinner.dismiss()
                    activity.finish()
                }
            } else {
                null
            }
            NovaSurfaces.of(activity).show(
                NovaStatePage.Busy(
                    key = key,
                    title = title,
                    message = spinner.message,
                    cancel = cancel,
                    owner = NovaStateOwner.LegacySpinner,
                ),
            )
            return spinner
        }

        /** Takes down every spinner shown over [activity]. Any thread. */
        @JvmStatic
        fun closeDialogs(activity: Activity) {
            NovaSurfaces.existing(activity)?.clear(NovaStateOwner.LegacySpinner)
        }
    }
}
