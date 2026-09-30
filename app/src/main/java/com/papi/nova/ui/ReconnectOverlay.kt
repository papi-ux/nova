package com.papi.nova.ui

import android.app.Activity
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The stream is reconnecting: a full-screen Busy page over the stream, with Disconnect focused as
 * its way out, because a host set to unlimited retries would otherwise hold the player here for
 * good. B does what Disconnect does. Once the player has left, later attempts show nothing.
 */
class ReconnectOverlay(
    private val activity: Activity,
    private val onDisconnect: () -> Unit,
) {
    private val message = MutableStateFlow("")

    // Main thread only.
    private var shown = false
    private var leftByPlayer = false

    fun show(attempt: Int, maxAttempts: Int) {
        activity.runOnUiThread {
            if (leftByPlayer || activity.isFinishing) return@runOnUiThread
            message.value = activity.getString(
                R.string.nova_stream_reconnect_message,
                activity.getString(R.string.nova_reconnect_subtitle),
                activity.getString(R.string.nova_reconnect_attempt, attempt, maxAttempts),
            )
            if (shown) return@runOnUiThread
            shown = true
            NovaSurfaces.of(activity).show(
                NovaStatePage.Busy(
                    key = PAGE_KEY,
                    title = activity.getString(R.string.nova_reconnect_title),
                    message = message,
                    cancel = NovaAction(activity.getString(R.string.game_menu_disconnect)) {
                        leftByPlayer = true
                        dismiss()
                        onDisconnect()
                    },
                ),
            )
            LimeLog.info("Nova: Reconnect page shown (attempt $attempt)")
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            if (!shown) return@runOnUiThread
            shown = false
            NovaSurfaces.existing(activity)?.dismiss(PAGE_KEY)
            LimeLog.info("Nova: Reconnect page dismissed")
        }
    }

    val isShowing get() = shown

    private companion object {
        const val PAGE_KEY = "nova-reconnecting"
    }
}
