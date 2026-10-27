package com.papi.nova.ui

import androidx.annotation.StringRes
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails

/**
 * What the dashboard says about the host under the power control, from the facts the
 * host list already keeps: whether the host answered its last check, and the game it
 * reported running.
 *
 * Only what those facts say. A host that does not answer is offline, never asleep:
 * a sleeping host, a powered-off one and an unplugged cable all look the same from
 * here. A running game is not a stream either; a game left running between sessions
 * is still a running game, so it is called that.
 */
enum class NovaHostPresence(@StringRes val label: Int) {
    AVAILABLE(R.string.pcview_host_presence_available),
    GAME_RUNNING(R.string.pcview_host_presence_game_running),
    OFFLINE(R.string.pcview_host_presence_offline),
    UNKNOWN(R.string.pcview_host_presence_unknown);

    companion object {
        /** Null when there is no host under the control to describe. */
        @JvmStatic
        fun from(host: ComputerDetails?): NovaHostPresence? = when (host?.state) {
            null -> null
            ComputerDetails.State.ONLINE -> if (host.runningGameId != 0) GAME_RUNNING else AVAILABLE
            ComputerDetails.State.OFFLINE -> OFFLINE
            ComputerDetails.State.UNKNOWN -> UNKNOWN
        }
    }
}
