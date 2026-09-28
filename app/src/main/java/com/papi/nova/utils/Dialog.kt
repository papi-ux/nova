package com.papi.nova.utils

import android.app.Activity
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaStateOwner
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import java.util.concurrent.atomic.AtomicLong

/**
 * Nova's message helper, drawn on the activity's [NovaSurfaces].
 *
 * A message the screen cannot go on from (`endAfterDismiss`) is a full-screen Problem page: its
 * action, when there is one, is focused, and Close (also what B does) finishes the screen. Any
 * other message is a Notice page in the right-edge panel. Help shows only when a caller asks for
 * it. Every entry point may be called from any thread.
 */
object Dialog {
    private val serial = AtomicLong()

    /** Removes every message these helpers put up, in every live activity. Any thread. */
    @JvmStatic
    fun closeDialogs() {
        NovaSurfaces.clearEverywhere(NovaStateOwner.LegacyDialog)
    }

    @JvmStatic
    fun displayDialog(activity: Activity, title: String, message: String, endAfterDismiss: Boolean) {
        displayDialog(activity, title, message, endAfterDismiss, null, null)
    }

    /**
     * Shows [message] with [actionText] as its focused action. The action deliberately skips the
     * dismiss handling: with [endAfterDismiss] that handling finishes the screen, which would race
     * whatever the action is about to start. [help] adds a Help action that leads to troubleshooting.
     */
    @JvmStatic
    @JvmOverloads
    fun displayDialog(
        activity: Activity,
        title: String,
        message: String,
        endAfterDismiss: Boolean,
        actionText: CharSequence?,
        action: Runnable?,
        help: Boolean = false,
    ) {
        val labelled = actionText?.let { label -> action?.let { NovaAction(label.toString(), run = it::run) } }
        post(activity, title, message, blocking = endAfterDismiss, action = labelled, help = help) {
            if (endAfterDismiss) activity.finish()
        }
    }

    @JvmStatic
    fun displayDialog(activity: Activity, title: String, message: String, runOnDismiss: Runnable) {
        post(activity, title, message, blocking = false, action = null, help = false, onClose = runOnDismiss::run)
    }

    private fun post(
        activity: Activity,
        title: String,
        message: String,
        blocking: Boolean,
        action: NovaAction?,
        help: Boolean,
        onClose: () -> Unit,
    ) {
        if (activity.isFinishing) return
        val surfaces = NovaSurfaces.of(activity)
        val key = "nova-legacy-dialog-" + serial.incrementAndGet()
        val closeLabel = activity.getString(R.string.nova_panel_close)
        val helpLabel = activity.getString(R.string.nova_panel_help)
        val troubleshoot = {
            onClose()
            HelpLauncher.launchTroubleshooting(activity)
        }
        if (blocking) {
            // A state page stays until something leaves it, so every action takes it down first.
            fun leaving(label: String, run: () -> Unit) = NovaAction(label) {
                surfaces.dismiss(key)
                run()
            }
            val close = leaving(closeLabel, onClose)
            val primary = action?.let { leaving(it.label, it.run) }
            surfaces.show(
                NovaStatePage.Problem(
                    key = key,
                    title = title,
                    message = message,
                    primary = primary ?: close,
                    secondary = if (primary != null) listOf(close) else emptyList(),
                    help = if (help) leaving(helpLabel, troubleshoot) else null,
                    owner = NovaStateOwner.LegacyDialog,
                ),
            )
        } else {
            surfaces.present(
                NovaCommonPage.Notice(
                    key = key,
                    title = title,
                    message = message,
                    primary = action,
                    closeLabel = closeLabel,
                    onClose = onClose,
                    help = if (help) NovaAction(helpLabel, run = troubleshoot) else null,
                ),
                owner = NovaStateOwner.LegacyDialog,
            )
        }
    }
}
