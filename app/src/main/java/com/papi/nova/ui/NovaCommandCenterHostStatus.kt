package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus

/**
 * What one opening of the Command Center holds of the host's status, and the one place a status
 * read lands (review finding 1).
 *
 * [status] is the status the client has now, none after a failed read. [last] is the newest one
 * the host sent in this opening, which the Doctor card keeps, a few seconds old, through a failed
 * read rather than going back to checking. [unavailable] says the newest read failed. A read lands
 * through [publish] only while this opening stands, [registry] still at [generation].
 *
 * [polaris] is decided when the Command Center opens and kept for this opening: a host that is
 * not Polaris has no Doctor, so its card is never drawn rather than drawn waiting for a reading.
 * A client alone says nothing, since a stream builds one for every host, Sunshine's included.
 */
internal class NovaCommandCenterHostStatus(
    private val api: PolarisApiClient?,
    private val registry: DoctorMenuRefreshRegistry,
    private val generation: Long,
    polarisServer: () -> Boolean,
) {
    val polaris: Boolean = api != null && (polarisServer() || api.withCurrentSessionStatus { it != null })

    var status: PolarisSessionStatus? = null
        private set
    var last: PolarisSessionStatus? = null
        private set
    var unavailable: Boolean = false
        private set

    /** Brings what the page derives from [status] up to date, after every read. */
    var derive: () -> Unit = {}

    /** Hands the page a state built from what this holds. */
    var redraw: () -> Unit = {}

    /** Takes the client's status while this opening stands, and says whether the host answered. */
    fun publish(): Boolean = registry.runIfCurrent(generation) {
        api?.withCurrentSessionStatus(::take) ?: false
    } ?: false

    /** Takes the client's status, then hands the page a new state. */
    fun refresh() {
        api?.withCurrentSessionStatus(::take)
        redraw()
    }

    private fun take(current: PolarisSessionStatus?): Boolean {
        status = current
        if (current != null) last = current
        unavailable = current == null
        derive()
        return current != null
    }
}
