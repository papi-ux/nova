package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus

/** Recheck the host's current quit authority when a previously armed split is confirmed. */
internal class NovaCommandCenterEndSession(
    private val polaris: () -> Boolean,
    private val status: () -> PolarisSessionStatus?,
    private val space: () -> Boolean,
    private val standing: () -> Boolean,
    private val close: () -> Unit,
    private val end: () -> Unit,
    private val disconnect: () -> Unit,
    private val unavailable: () -> Unit,
    private val ending: () -> Unit,
) {
    fun perform() {
        if (!standing()) return
        val current = status()
        if (space()) {
            close()
            end()
        } else if (current?.isViewer == true) {
            close()
            disconnect()
        } else if (current?.isShuttingDown == true) {
            ending()
        } else if (!enabled(polaris(), current, false)) {
            unavailable()
        } else {
            close()
            end()
        }
    }

    companion object {
        fun enabled(polaris: Boolean, status: PolarisSessionStatus?, space: Boolean): Boolean =
            space || status?.isViewer == true ||
                (status?.isShuttingDown != true &&
                    if (polaris) status?.canQuit == true else status?.canQuit != false)
    }
}
